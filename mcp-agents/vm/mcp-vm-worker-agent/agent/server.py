#!/usr/bin/env python3
"""
mcp-vm-installer-agent: remote-ops agent for a VM, over MCP and REST.

  MCP (streamable HTTP):  http://<host>:<port>/mcp      token scope "mcp"
  REST API:               http://<host>:<port>/api/*    token scope "rest"
  Token admin UI:         http://<host>:<port>/ui       admin password + network allowlist
  Health (no auth):       http://<host>:<port>/health

Tokens are stored hashed (sha256) in tokens.json; the plaintext is shown once, at creation.
Every action is appended to audit.log, tagged with the name of the token that performed it.

CLI (run from the install dir as the service user):
  venv/bin/python server.py token list
  venv/bin/python server.py token create NAME [--scopes rest,mcp] [--ttl-days N] [--quiet]
  venv/bin/python server.py token revoke ID
  venv/bin/python server.py token delete ID
  venv/bin/python server.py token import NAME --file PATH [--scopes rest,mcp]
  venv/bin/python server.py admin-password        (prompts, or reads AGENT_ADMIN_PASSWORD)
"""
from __future__ import annotations

import argparse
import asyncio
import contextvars
import errno
import fcntl
import getpass
import hashlib
import hmac
import importlib.util
import inspect
import ipaddress
import json
import logging
import os
import re
import secrets
import shutil
import signal
import socket
import sys
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

import uvicorn
from mcp.server.fastmcp import FastMCP
from starlette.requests import Request
from starlette.responses import HTMLResponse, JSONResponse, RedirectResponse

VERSION = "2.2.0"
FEATURES = ["fs", "pty"]  # optional APIs, advertised in /health and server_info
BASE = Path(__file__).resolve().parent
PLUGINS, JOBS = BASE / "plugins", BASE / "jobs"
TOKENS_FILE, ADMIN_FILE = BASE / "tokens.json", BASE / "admin.json"
SECRET_FILE, LOCK_FILE, UI_FILE = BASE / "secret.key", BASE / ".state.lock", BASE / "ui.html"
AUDIT_FILE = BASE / "audit.log"
for _d in (PLUGINS, JOBS):
    _d.mkdir(exist_ok=True)


def _nets(value: str) -> list:
    return [ipaddress.ip_network(c.strip(), strict=False) for c in value.split(",") if c.strip()]


AGENT_NAME = os.environ.get("AGENT_NAME") or socket.gethostname()
HOST = os.environ.get("AGENT_HOST", "0.0.0.0")
PORT = int(os.environ.get("AGENT_PORT", "8765"))
DEFAULT_CWD = os.environ.get("AGENT_DEFAULT_CWD") or str(Path.home())
UI_ALLOW = _nets(os.environ.get("AGENT_UI_ALLOW",
                                "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"))
TLS_CERT, TLS_KEY = os.environ.get("AGENT_TLS_CERT", ""), os.environ.get("AGENT_TLS_KEY", "")
SESSION_TTL = int(os.environ.get("AGENT_SESSION_TTL", str(8 * 3600)))
SCOPES = ("rest", "mcp")
TOKEN_PREFIX = "mva_"
COOKIE = "mva_session"
MAX_OUT = 100_000
STARTED = time.time()
NAME_RE = re.compile(r"^[a-z][a-z0-9_]{0,40}$")

# ----------------------------------------------------------------- audit log
CURRENT_TOKEN: contextvars.ContextVar[str] = contextvars.ContextVar("current_token", default="-")


class _TokenTag(logging.Filter):
    def filter(self, record):
        record.tok = CURRENT_TOKEN.get()
        return True


audit = logging.getLogger("audit")
audit.setLevel(logging.INFO)
audit.propagate = False
_h = logging.FileHandler(AUDIT_FILE)
_h.setFormatter(logging.Formatter("%(asctime)s [%(tok)s] %(message)s"))
_h.addFilter(_TokenTag())
audit.addHandler(_h)

_settings = dict(host=HOST, port=PORT, stateless_http=True, json_response=True)
try:  # newer SDKs reject non-localhost Host headers unless this is disabled
    from mcp.server.transport_security import TransportSecuritySettings
    _settings["transport_security"] = TransportSecuritySettings(enable_dns_rebinding_protection=False)
except ImportError:
    pass
mcp = FastMCP(AGENT_NAME, **_settings)


# ----------------------------------------------------------------- state files
@contextmanager
def _locked():
    with open(LOCK_FILE, "a") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(f, fcntl.LOCK_UN)


def _read_json(path: Path, default):
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        return default


def _write_json(path: Path, data) -> None:
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)


def _sha256(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()


class TokenStore:
    """Hashed bearer tokens in tokens.json. Safe to edit from the CLI while the server runs
    (file lock + reload on mtime change)."""

    def __init__(self):
        self.recs: dict = {}
        self.by_hash: dict = {}
        self.mtime = -1
        self.used: dict = {}  # id -> last-used ts, flushed to disk at most once a minute
        self.last_flush = 0.0

    @staticmethod
    def _stat():
        try:
            return TOKENS_FILE.stat().st_mtime_ns
        except FileNotFoundError:
            return None

    def _load(self, force: bool = False):
        m = self._stat()
        if not force and m == self.mtime:
            return
        self.recs = {r["id"]: r for r in _read_json(TOKENS_FILE, {}).get("tokens", [])}
        self.mtime = m
        self.by_hash = {r["hash"]: r for r in self.recs.values()}

    @contextmanager
    def _txn(self):
        with _locked():
            self._load(force=True)
            for tid, ts in self.used.items():
                if tid in self.recs:
                    self.recs[tid]["last_used"] = max(ts, self.recs[tid].get("last_used") or 0)
            self.used.clear()
            yield
            _write_json(TOKENS_FILE, {"version": 1, "tokens": list(self.recs.values())})
            self.mtime = self._stat()
            self.by_hash = {r["hash"]: r for r in self.recs.values()}

    @staticmethod
    def status(rec: dict) -> str:
        if rec.get("revoked"):
            return "revoked"
        if rec.get("expires") and rec["expires"] < time.time():
            return "expired"
        return "active"

    def public(self, rec: dict) -> dict:
        r = {k: v for k, v in rec.items() if k != "hash"}
        r["last_used"] = max(self.used.get(rec["id"], 0), rec.get("last_used") or 0) or None
        r["status"] = self.status(rec)
        return r

    def list(self) -> list:
        self._load()
        return sorted((self.public(r) for r in self.recs.values()), key=lambda r: -r["created"])

    def create(self, name: str, scopes, ttl_days=None, plaintext: str | None = None):
        name = (name or "").strip()
        if not 1 <= len(name) <= 60:
            raise ValueError("name must be 1-60 characters")
        scopes = sorted(set(scopes or []))
        if not scopes or any(s not in SCOPES for s in scopes):
            raise ValueError(f"scopes must be a non-empty subset of {list(SCOPES)}")
        if ttl_days in ("", None):
            ttl_days = None
        else:
            ttl_days = int(ttl_days)
            if not 1 <= ttl_days <= 3650:
                raise ValueError("ttl_days must be between 1 and 3650")
        tok = plaintext or TOKEN_PREFIX + secrets.token_urlsafe(32)
        if len(tok) < 20:
            raise ValueError("token must be at least 20 characters")
        now = int(time.time())
        rec = {"id": secrets.token_hex(4), "name": name, "hash": _sha256(tok), "hint": tok[:8] + "...",
               "scopes": scopes, "created": now,
               "expires": now + ttl_days * 86400 if ttl_days else None,
               "last_used": None, "revoked": None}
        with self._txn():
            if rec["hash"] in self.by_hash:
                raise ValueError("that token is already registered")
            self.recs[rec["id"]] = rec
        audit.info("TOKEN create id=%s name=%r scopes=%s ttl_days=%s", rec["id"], name, ",".join(scopes), ttl_days)
        return self.public(rec), tok

    def revoke(self, tid: str) -> dict:
        with self._txn():
            rec = self.recs.get(tid)
            if rec is None:
                raise KeyError(tid)
            rec["revoked"] = rec.get("revoked") or int(time.time())
        audit.info("TOKEN revoke id=%s name=%r", tid, rec["name"])
        return self.public(rec)

    def delete(self, tid: str) -> dict:
        with self._txn():
            rec = self.recs.pop(tid, None)
            if rec is None:
                raise KeyError(tid)
        audit.info("TOKEN delete id=%s name=%r", tid, rec["name"])
        return {"deleted": tid}

    def verify(self, tok: str, scope: str):
        if not tok:
            return None
        self._load()
        rec = self.by_hash.get(_sha256(tok))
        if rec is None or self.status(rec) != "active" or scope not in rec["scopes"]:
            return None
        now = time.time()
        self.used[rec["id"]] = int(now)
        if now - self.last_flush > 60:
            self.last_flush = now
            try:
                with self._txn():
                    pass
            except Exception as e:  # never fail a request because of bookkeeping
                audit.info("TOKEN last_used flush failed: %r", e)
        return rec


TOKENS = TokenStore()

# ----------------------------------------------------------------- admin password + UI sessions
PBKDF2_ITER = 310_000


def _pbkdf2(pw: str, salt: bytes, iterations: int) -> str:
    return hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, iterations).hex()


def admin_record():
    return _read_json(ADMIN_FILE, None)


def set_admin_password(pw: str) -> None:
    if len(pw) < 10:
        raise ValueError("password must be at least 10 characters")
    salt = secrets.token_bytes(16)
    with _locked():
        _write_json(ADMIN_FILE, {"algo": "pbkdf2_sha256", "iter": PBKDF2_ITER, "salt": salt.hex(),
                                 "hash": _pbkdf2(pw, salt, PBKDF2_ITER), "changed": int(time.time())})
    audit.info("ADMIN password set (all UI sessions invalidated)")


def check_admin_password(pw: str) -> bool:
    a = admin_record()
    if not a or not pw:
        return False
    return hmac.compare_digest(_pbkdf2(pw, bytes.fromhex(a["salt"]), a["iter"]), a["hash"])


def _secret() -> bytes:
    try:
        fd = os.open(SECRET_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(secrets.token_bytes(32))
    except FileExistsError:
        pass
    return SECRET_FILE.read_bytes()


def _session_key() -> bytes:
    # mixing in the password hash means a password change logs out every session
    return hashlib.sha256(_secret() + (admin_record() or {}).get("hash", "").encode()).digest()


def make_session() -> str:
    payload = f"{int(time.time()) + SESSION_TTL}.{secrets.token_hex(8)}"
    return payload + "." + hmac.new(_session_key(), payload.encode(), hashlib.sha256).hexdigest()


def valid_session(value: str) -> bool:
    try:
        exp, nonce, sig = value.split(".")
        good = hmac.new(_session_key(), f"{exp}.{nonce}".encode(), hashlib.sha256).hexdigest()
        return hmac.compare_digest(sig, good) and int(exp) > time.time()
    except Exception:
        return False


_FAILS: dict = {}  # ip -> (failures, blocked_until)


def _login_blocked(ip: str) -> bool:
    n, until = _FAILS.get(ip, (0, 0))
    return n >= 5 and time.time() < until


def _login_failed(ip: str) -> None:
    n = _FAILS.get(ip, (0, 0))[0] + 1
    _FAILS[ip] = (n, time.time() + 60 if n >= 5 else 0)


def _ui_allowed(ip: str) -> bool:
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if getattr(a, "ipv4_mapped", None):
        a = a.ipv4_mapped
    return any(a in n for n in UI_ALLOW if n.version == a.version)


# ----------------------------------------------------------------- command helpers
def _trim(s: str) -> str:
    if len(s) <= MAX_OUT:
        return s
    h = MAX_OUT // 2
    return f"{s[:h]}\n...[{len(s) - MAX_OUT} chars truncated]...\n{s[-h:]}"


async def _run(command: str, cwd: str = "", timeout_seconds: int = 600) -> dict:
    cwd = cwd or DEFAULT_CWD
    audit.info("RUN cwd=%s timeout=%s cmd=%s", cwd, timeout_seconds, command)
    t0 = time.time()
    p = await asyncio.create_subprocess_exec(
        "bash", "-lc", command, cwd=cwd,
        stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT, start_new_session=True)
    try:
        out, _ = await asyncio.wait_for(p.communicate(), timeout_seconds)
    except asyncio.TimeoutError:
        os.killpg(p.pid, signal.SIGKILL)
        await p.wait()
        audit.info("RUN timeout after %ss", timeout_seconds)
        return {"exit_code": None, "timed_out": True,
                "output": f"Timed out after {timeout_seconds}s and was killed. Use start_job for long commands."}
    res = {"exit_code": p.returncode, "duration_s": round(time.time() - t0, 1),
           "output": _trim(out.decode(errors="replace"))}
    audit.info("RUN exit=%s duration=%ss", res["exit_code"], res["duration_s"])
    return res


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


async def _start_job(command: str, cwd: str = "") -> dict:
    jid = time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6]
    d = JOBS / jid
    d.mkdir()
    (d / "command").write_text(command)
    audit.info("JOB start id=%s cmd=%s", jid, command)
    with open(d / "output.log", "wb") as log:
        p = await asyncio.create_subprocess_exec(
            "bash", "-c", 'bash -lc "$1"; echo $? > "$2"', "_", command, str(d / "exit_code"),
            cwd=cwd or DEFAULT_CWD, stdin=asyncio.subprocess.DEVNULL,
            stdout=log, stderr=asyncio.subprocess.STDOUT,
            start_new_session=True)  # own session: survives server restarts
    (d / "pid").write_text(str(p.pid))
    return {"job_id": jid, "pid": p.pid, "log": str(d / "output.log")}


def _job_status(job_id: str, tail_lines: int = 200) -> dict:
    d = JOBS / job_id
    if "/" in job_id or not (d / "pid").exists():
        return {"error": f"unknown job {job_id}"}
    pid = int((d / "pid").read_text())
    ec = d / "exit_code"
    lines = (d / "output.log").read_text(errors="replace").splitlines()
    return {"job_id": job_id, "command": (d / "command").read_text(),
            "running": not ec.exists() and _pid_alive(pid),
            "exit_code": int(ec.read_text()) if ec.exists() and ec.read_text().strip() else None,
            "total_lines": len(lines),
            "tail": _trim("\n".join(lines[-tail_lines:])) if tail_lines else ""}


def _list_jobs() -> list:
    out = []
    for d in sorted(JOBS.iterdir(), reverse=True)[:50]:
        if (d / "pid").exists():
            s = _job_status(d.name, 0)
            out.append({k: s[k] for k in ("job_id", "command", "running", "exit_code")})
    return out


def _kill_job(job_id: str) -> dict:
    d = JOBS / job_id
    if "/" in job_id or not (d / "pid").exists():
        return {"error": f"unknown job {job_id}"}
    pid = int((d / "pid").read_text())
    audit.info("JOB kill id=%s pid=%s", job_id, pid)
    try:
        os.killpg(pid, signal.SIGTERM)
        return {"killed": job_id}
    except OSError as e:
        return {"error": str(e)}


# ----------------------------------------------------------------- plugins
LOADED: dict = {}


def _remove_tool(name: str):
    try:
        mcp.remove_tool(name)
    except Exception:
        getattr(mcp, "_tool_manager")._tools.pop(name, None)


def _load_plugin(path: Path):
    name = path.stem
    spec = importlib.util.spec_from_file_location(f"plugin_{name}", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    if not callable(getattr(mod, "run", None)):
        raise ValueError("plugin must define a function run(...)")
    _remove_tool(f"plugin_{name}")
    mcp.add_tool(mod.run, name=f"plugin_{name}",
                 description=getattr(mod, "DESCRIPTION", None) or mod.run.__doc__ or name)
    LOADED[name] = mod


async def _call_plugin(name: str, arguments: dict):
    if name not in LOADED:
        return {"error": f"plugin {name} not loaded", "loaded": sorted(LOADED)}
    audit.info("PLUGIN call %s args=%s", name, json.dumps(arguments)[:2000])
    res = LOADED[name].run(**(arguments or {}))
    return await res if inspect.isawaitable(res) else res


# ----------------------------------------------------------------- MCP tools
@mcp.tool()
async def run_command(command: str, cwd: str = "", timeout_seconds: int = 600) -> dict:
    """Run a bash command (login shell) on this VM and return exit code + combined output.
    For anything longer than a few minutes use start_job instead."""
    return await _run(command, cwd, timeout_seconds)


@mcp.tool()
async def start_job(command: str, cwd: str = "") -> dict:
    """Start a long-running bash command in the background. Returns a job_id; poll with job_status.
    Jobs keep running across server restarts/self-updates."""
    return await _start_job(command, cwd)


@mcp.tool()
async def job_status(job_id: str, tail_lines: int = 200) -> dict:
    """Get running state, exit code and the last N output lines of a background job."""
    return _job_status(job_id, tail_lines)


@mcp.tool()
async def list_jobs() -> list:
    """List the 50 most recent background jobs."""
    return _list_jobs()


@mcp.tool()
async def kill_job(job_id: str) -> dict:
    """Send SIGTERM to a background job's whole process group."""
    return _kill_job(job_id)


@mcp.tool()
async def read_file(path: str, max_bytes: int = 200_000) -> str:
    """Read a text file on this VM."""
    audit.info("READ %s", path)
    return Path(path).expanduser().read_bytes()[:max_bytes].decode(errors="replace")


@mcp.tool()
async def list_dir(path: str = "", hidden: bool = True) -> dict:
    """List a directory on this VM: name, type, size, mode, owner, mtime of each entry (dirs first)."""
    return await asyncio.to_thread(_fs_list, path or DEFAULT_CWD, hidden)


@mcp.tool()
async def write_file(path: str, content: str, backup: bool = True) -> dict:
    """Write a text file on this VM (creates parent dirs). Keeps <file>.bak-<ts> if backup=True."""
    p = Path(path).expanduser()
    audit.info("WRITE %s (%d bytes)", p, len(content))
    p.parent.mkdir(parents=True, exist_ok=True)
    bak = None
    if backup and p.exists():
        bak = f"{p}.bak-{time.strftime('%Y%m%d-%H%M%S')}"
        shutil.copy2(p, bak)
    p.write_text(content)
    return {"written": str(p), "bytes": len(content), "backup": bak}


@mcp.tool()
async def install_plugin(name: str, code: str) -> dict:
    """Add or replace a tool at runtime. `code` is Python defining DESCRIPTION (str) and run(...) with typed
    params (sync or async). It is exposed as MCP tool plugin_<name> (visible after the client reconnects)
    and is callable immediately via call_plugin. Rolled back automatically if it fails to load."""
    if not NAME_RE.match(name):
        return {"error": "name must match ^[a-z][a-z0-9_]{0,40}$"}
    try:
        compile(code, f"{name}.py", "exec")
    except SyntaxError as e:
        return {"error": f"syntax error: {e}"}
    path = PLUGINS / f"{name}.py"
    old = path.read_text() if path.exists() else None
    path.write_text(code)
    audit.info("PLUGIN install %s (%d bytes)", name, len(code))
    try:
        _load_plugin(path)
    except Exception as e:
        if old is None:
            path.unlink()
        else:
            path.write_text(old)
            _load_plugin(path)
        return {"error": f"load failed, rolled back: {e!r}"}
    return {"installed": name, "tool": f"plugin_{name}"}


@mcp.tool()
async def remove_plugin(name: str) -> dict:
    """Remove a plugin tool."""
    path = PLUGINS / f"{name}.py"
    if not NAME_RE.match(name) or not path.exists():
        return {"error": f"no plugin {name}"}
    path.unlink()
    _remove_tool(f"plugin_{name}")
    LOADED.pop(name, None)
    audit.info("PLUGIN remove %s", name)
    return {"removed": name}


@mcp.tool()
async def list_plugins() -> dict:
    """List loaded plugins and their descriptions."""
    return {n: getattr(m, "DESCRIPTION", "") for n, m in sorted(LOADED.items())}


@mcp.tool()
async def call_plugin(name: str, arguments: dict = {}):  # noqa: B006
    """Call a plugin by name with a dict of arguments (works without reconnecting the client)."""
    return await _call_plugin(name, arguments)


@mcp.tool()
async def update_server(code: str, ui_html: str = "") -> dict:
    """Replace this agent's server.py (and optionally ui.html) and restart. The current files are kept as
    *.prev; if the new server.py crashes within 15s of starting, run.sh restores it automatically."""
    try:
        compile(code, "server.py", "exec")
    except SyntaxError as e:
        return {"error": f"syntax error, not updated: {e}"}
    me = BASE / "server.py"
    shutil.copy2(me, BASE / "server.py.prev")
    me.write_text(code)
    if ui_html:
        if UI_FILE.exists():
            shutil.copy2(UI_FILE, BASE / "ui.html.prev")
        UI_FILE.write_text(ui_html)
    audit.info("SELF-UPDATE server.py %d bytes, ui.html %d bytes, restarting", len(code), len(ui_html))
    asyncio.get_running_loop().call_later(1.0, os._exit, 3)
    return {"updated": True, "restarting_in_s": 1, "previous_version": VERSION}


@mcp.tool()
async def server_info() -> dict:
    """Agent name, version, uptime, default cwd, plugins, token count, python version."""
    return {"agent": "mcp-vm-installer-agent", "name": AGENT_NAME, "version": VERSION,
            "uptime_s": int(time.time() - STARTED), "default_cwd": DEFAULT_CWD, "host": socket.gethostname(),
            "plugins": sorted(LOADED), "active_tokens": sum(t["status"] == "active" for t in TOKENS.list()),
            "python": sys.version.split()[0], "base": str(BASE), "tls": bool(TLS_CERT), "features": FEATURES}


# ----------------------------------------------------------------- REST API (scope: rest)
async def _body(req: Request) -> dict:
    try:
        b = await req.json()
        return b if isinstance(b, dict) else {}
    except Exception:
        return {}


@mcp.custom_route("/health", methods=["GET"])
async def rest_health(req: Request):
    return JSONResponse({"ok": True, "agent": "mcp-vm-installer-agent", "version": VERSION, "features": FEATURES})


@mcp.custom_route("/api/run", methods=["POST"])
async def rest_run(req: Request):
    b = await _body(req)
    if not b.get("command"):
        return JSONResponse({"error": "command required"}, 400)
    return JSONResponse(await _run(b["command"], b.get("cwd", ""), int(b.get("timeout_seconds", 600))))


@mcp.custom_route("/api/jobs", methods=["GET", "POST"])
async def rest_jobs(req: Request):
    if req.method == "GET":
        return JSONResponse(_list_jobs())
    b = await _body(req)
    if not b.get("command"):
        return JSONResponse({"error": "command required"}, 400)
    return JSONResponse(await _start_job(b["command"], b.get("cwd", "")))


@mcp.custom_route("/api/jobs/{job_id}", methods=["GET", "DELETE"])
async def rest_job(req: Request):
    jid = req.path_params["job_id"]
    if req.method == "DELETE":
        return JSONResponse(_kill_job(jid))
    return JSONResponse(_job_status(jid, int(req.query_params.get("tail", 200))))


@mcp.custom_route("/api/plugins/{name}", methods=["POST"])
async def rest_plugin(req: Request):
    return JSONResponse(await _call_plugin(req.path_params["name"], await _body(req)))


@mcp.custom_route("/api/info", methods=["GET"])
async def rest_info(req: Request):
    return JSONResponse(await server_info())


# ----------------------------------------------------------------- file API (scope: rest)
# Used by mcp-vm-admin-agent's file browser. Paths are absolute, or relative to AGENT_DEFAULT_CWD.
MAX_UPLOAD = int(os.environ.get("AGENT_MAX_UPLOAD_MB", "100")) * 1024 * 1024
MAX_LIST = 5000
# never deleted, even with recursive=1
PROTECTED = {"/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib32", "/lib64", "/opt", "/proc",
             "/root", "/run", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/var", str(Path.home()), str(BASE)}


def _fs_path(raw) -> Path:
    raw = str(raw or "").strip()
    if not raw:
        raise ValueError("path required")
    if "\0" in raw:
        raise ValueError("bad path")
    p = Path(raw).expanduser()
    if not p.is_absolute():
        p = Path(DEFAULT_CWD) / p
    return Path(os.path.normpath(p))


_NAMES: dict = {}


def _owner(kind: str, num: int) -> str:
    key = (kind, num)
    if key not in _NAMES:
        try:
            import grp
            import pwd
            _NAMES[key] = pwd.getpwuid(num).pw_name if kind == "u" else grp.getgrgid(num).gr_name
        except (KeyError, ImportError):
            _NAMES[key] = str(num)
    return _NAMES[key]


def _entry(p: Path, name: str | None = None) -> dict:
    import stat as st
    s = os.lstat(p)
    kind = "dir" if st.S_ISDIR(s.st_mode) else "file" if st.S_ISREG(s.st_mode) \
        else "link" if st.S_ISLNK(s.st_mode) else "other"
    e = {"name": name if name is not None else (p.name or str(p)), "type": kind, "size": s.st_size,
         "mode": st.filemode(s.st_mode), "mtime": int(s.st_mtime),
         "owner": _owner("u", s.st_uid), "group": _owner("g", s.st_gid)}
    if kind == "link":
        try:
            e["target"] = os.readlink(p)
            e["target_type"] = "dir" if p.is_dir() else "file" if p.exists() else "missing"
        except OSError:
            e["target_type"] = "missing"
    return e


def _fs_error(e: Exception) -> JSONResponse:
    code = {FileNotFoundError: 404, PermissionError: 403, FileExistsError: 409,
            IsADirectoryError: 400, NotADirectoryError: 400, ValueError: 400}.get(type(e), 400)
    if isinstance(e, OSError) and e.errno == errno.ENOTEMPTY:
        code = 409
    msg = f"{e.strerror}: {e.filename}" if isinstance(e, OSError) and e.strerror else str(e)
    return JSONResponse({"error": msg}, code)


def _fs_list(path: str, hidden: bool = True) -> dict:
    p = _fs_path(path)
    if not p.is_dir():
        raise NotADirectoryError(20, "Not a directory", str(p))
    entries, truncated = [], False
    with os.scandir(p) as it:
        for de in it:
            if not hidden and de.name.startswith("."):
                continue
            if len(entries) >= MAX_LIST:
                truncated = True
                break
            try:
                entries.append(_entry(Path(de.path), de.name))
            except OSError:
                entries.append({"name": de.name, "type": "other", "error": "cannot stat"})
    entries.sort(key=lambda e: (not (e["type"] == "dir" or e.get("target_type") == "dir"), e["name"].lower()))
    audit.info("FS list %s (%d entries)", p, len(entries))
    return {"path": str(p), "parent": str(p.parent) if p != p.parent else None,
            "entries": entries, "truncated": truncated}


def _fs_read(path: str, max_bytes: int = 1_000_000) -> dict:
    p = _fs_path(path)
    max_bytes = max(1, min(int(max_bytes), 5_000_000))
    if p.is_dir():
        raise IsADirectoryError(21, "Is a directory", str(p))
    size = p.stat().st_size
    with open(p, "rb") as f:
        data = f.read(max_bytes)
    audit.info("FS read %s (%d of %d bytes)", p, len(data), size)
    res = {"path": str(p), "size": size, "truncated": size > len(data), "binary": False, "content": None}
    if b"\0" in data[:8192]:
        res["binary"] = True
        return res
    try:
        res["content"] = data.decode("utf-8")
    except UnicodeDecodeError as e:
        if res["truncated"] and e.start >= len(data) - 4:  # cut through a multi-byte character
            res["content"] = data[:e.start].decode("utf-8")
        else:
            res["binary"] = True
    return res


def _fs_write(path: str, data: bytes, backup: bool = True, overwrite: bool = True, mkdirs: bool = False) -> dict:
    p = _fs_path(path)
    if p.is_dir():
        raise IsADirectoryError(21, "Is a directory", str(p))
    exists = p.exists()
    if exists and not overwrite:
        raise FileExistsError(17, "File exists", str(p))
    if mkdirs:
        p.parent.mkdir(parents=True, exist_ok=True)
    bak = None
    if exists and backup:
        bak = f"{p}.bak-{time.strftime('%Y%m%d-%H%M%S')}"
        shutil.copy2(p, bak)
    mode = p.stat().st_mode & 0o7777 if exists else None
    tmp = p.with_name(f".{p.name}.upload-{secrets.token_hex(4)}")
    try:
        with open(tmp, "wb") as f:
            f.write(data)
        if mode is not None:
            os.chmod(tmp, mode)
        os.replace(tmp, p)
    finally:
        if tmp.exists():
            tmp.unlink()
    audit.info("FS write %s (%d bytes) backup=%s", p, len(data), bak)
    return {"written": str(p), "bytes": len(data), "backup": bak}


def _fs_mkdir(path: str) -> dict:
    p = _fs_path(path)
    p.mkdir(parents=True, exist_ok=False)
    audit.info("FS mkdir %s", p)
    return {"created": str(p)}


def _fs_rename(src: str, dst: str, overwrite: bool = False) -> dict:
    a, b = _fs_path(src), _fs_path(dst)
    if str(a) in PROTECTED:
        raise PermissionError(1, "Protected path", str(a))
    if not os.path.lexists(a):
        raise FileNotFoundError(2, "No such file or directory", str(a))
    if os.path.lexists(b) and not overwrite:
        raise FileExistsError(17, "File exists", str(b))
    os.replace(a, b) if overwrite else os.rename(a, b)
    audit.info("FS rename %s -> %s", a, b)
    return {"from": str(a), "to": str(b)}


def _fs_delete(path: str, recursive: bool = False) -> dict:
    p = _fs_path(path)
    if str(p) in PROTECTED or str(p.resolve()) in PROTECTED:
        raise PermissionError(1, "Protected path, refusing to delete", str(p))
    if p.is_symlink() or (p.exists() and not p.is_dir()):
        p.unlink()
    elif p.is_dir():
        if recursive:
            shutil.rmtree(p)
        else:
            p.rmdir()  # OSError "Directory not empty" unless recursive
    else:
        raise FileNotFoundError(2, "No such file or directory", str(p))
    audit.info("FS delete %s recursive=%s", p, recursive)
    return {"deleted": str(p)}


def _flag(req: Request, name: str, default: bool) -> bool:
    v = req.query_params.get(name)
    return default if v is None else v.lower() in ("1", "true", "yes")


@mcp.custom_route("/api/fs/list", methods=["GET"])
async def rest_fs_list(req: Request):
    try:
        return JSONResponse(await asyncio.to_thread(
            _fs_list, req.query_params.get("path") or DEFAULT_CWD, _flag(req, "hidden", True)))
    except (OSError, ValueError) as e:
        return _fs_error(e)


@mcp.custom_route("/api/fs/stat", methods=["GET"])
async def rest_fs_stat(req: Request):
    try:
        p = _fs_path(req.query_params.get("path"))
        return JSONResponse({"path": str(p), **_entry(p)})
    except (OSError, ValueError) as e:
        return _fs_error(e)


@mcp.custom_route("/api/fs/read", methods=["GET"])
async def rest_fs_read(req: Request):
    try:
        return JSONResponse(await asyncio.to_thread(
            _fs_read, req.query_params.get("path"), int(req.query_params.get("max_bytes", 1_000_000))))
    except (OSError, ValueError) as e:
        return _fs_error(e)


@mcp.custom_route("/api/fs/download", methods=["GET"])
async def rest_fs_download(req: Request):
    from starlette.responses import FileResponse
    try:
        p = _fs_path(req.query_params.get("path"))
        if not p.is_file():
            raise FileNotFoundError(2, "Not a regular file", str(p))
        with open(p, "rb"):  # permission check before streaming starts
            pass
    except (OSError, ValueError) as e:
        return _fs_error(e)
    audit.info("FS download %s (%d bytes)", p, p.stat().st_size)
    return FileResponse(p, filename=p.name, media_type="application/octet-stream")


@mcp.custom_route("/api/fs/file", methods=["PUT"])
async def rest_fs_write(req: Request):
    if int(req.headers.get("content-length") or 0) > MAX_UPLOAD:
        return JSONResponse({"error": f"file larger than {MAX_UPLOAD // 1048576} MB"}, 413)
    data = bytearray()
    async for chunk in req.stream():
        data += chunk
        if len(data) > MAX_UPLOAD:
            return JSONResponse({"error": f"file larger than {MAX_UPLOAD // 1048576} MB"}, 413)
    try:
        return JSONResponse(await asyncio.to_thread(
            _fs_write, req.query_params.get("path"), bytes(data), _flag(req, "backup", True),
            _flag(req, "overwrite", True), _flag(req, "mkdirs", False)))
    except (OSError, ValueError) as e:
        return _fs_error(e)


@mcp.custom_route("/api/fs/mkdir", methods=["POST"])
async def rest_fs_mkdir(req: Request):
    try:
        return JSONResponse(_fs_mkdir((await _body(req)).get("path")))
    except (OSError, ValueError) as e:
        return _fs_error(e)


@mcp.custom_route("/api/fs/rename", methods=["POST"])
async def rest_fs_rename(req: Request):
    b = await _body(req)
    try:
        return JSONResponse(_fs_rename(b.get("from"), b.get("to"), bool(b.get("overwrite"))))
    except (OSError, ValueError) as e:
        return _fs_error(e)


@mcp.custom_route("/api/fs", methods=["DELETE"])
async def rest_fs_delete(req: Request):
    try:
        return JSONResponse(await asyncio.to_thread(
            _fs_delete, req.query_params.get("path"), _flag(req, "recursive", False)))
    except (OSError, ValueError) as e:
        return _fs_error(e)


# ----------------------------------------------------------------- terminal sessions (scope: rest)
# Interactive shells for mcp-vm-admin-agent's web terminal, over plain HTTP: input is POSTed, output is
# long-polled by byte offset. Only the stdlib pty module is used, so no extra dependencies on the VM.
# Session open/close is audited; keystrokes are not (they can contain passwords).
PTY_MAX = int(os.environ.get("AGENT_PTY_MAX", "8"))
PTY_IDLE = int(os.environ.get("AGENT_PTY_IDLE_S", "1800"))
PTY_BUF = 1_000_000
PTYS: dict = {}


class _Pty:
    def __init__(self, cwd: str, cols: int, rows: int):
        import pty
        import termios  # noqa: F401  (fail early on platforms without a tty layer)
        self.id = secrets.token_hex(6)
        self.buf = bytearray()
        self.base = 0  # stream offset of buf[0]
        self.closed = False
        self.exit_code = None
        self.touched = time.time()
        self.event = asyncio.Event()
        env = {k: v for k, v in os.environ.items() if not k.startswith("AGENT_")}
        env.update(TERM="xterm-256color", COLORTERM="truecolor", LANG=env.get("LANG", "C.UTF-8"))
        pid, fd = pty.fork()
        if pid == 0:  # child
            try:
                os.chdir(cwd)
            except OSError:
                os.chdir(str(Path.home()))
            try:
                os.execvpe("bash", ["bash", "-l"], env)
            finally:
                os._exit(127)
        self.pid, self.fd = pid, fd
        os.set_blocking(fd, False)
        self.resize(cols, rows)
        asyncio.get_running_loop().add_reader(fd, self._readable)

    def _readable(self):
        try:
            data = os.read(self.fd, 65536)
        except BlockingIOError:
            return
        except OSError:
            data = b""
        if not data:
            self._close()
            return
        self.buf += data
        if len(self.buf) > PTY_BUF:
            drop = len(self.buf) - PTY_BUF
            del self.buf[:drop]
            self.base += drop
        self.event.set()
        self.event = asyncio.Event()

    def _close(self):
        if self.closed:
            return
        self.closed = True
        try:
            asyncio.get_running_loop().remove_reader(self.fd)
        except Exception:
            pass
        try:
            os.close(self.fd)
        except OSError:
            pass
        try:
            _, status = os.waitpid(self.pid, os.WNOHANG)
            self.exit_code = os.waitstatus_to_exitcode(status) if status else None
        except ChildProcessError:
            pass
        self.event.set()
        audit.info("PTY close id=%s exit=%s", self.id, self.exit_code)

    def resize(self, cols: int, rows: int):
        import struct
        import termios
        cols, rows = max(10, min(int(cols), 500)), max(4, min(int(rows), 200))
        if not self.closed:
            fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

    def write(self, data: bytes):
        self.touched = time.time()
        view = memoryview(data)
        deadline = time.time() + 2
        while view and not self.closed:
            try:
                n = os.write(self.fd, view)
                view = view[n:]
            except BlockingIOError:
                if time.time() > deadline:
                    raise OSError("terminal is not reading input")
                time.sleep(0.01)

    def kill(self):
        if not self.closed:
            try:
                os.killpg(os.getpgid(self.pid), signal.SIGHUP)
            except OSError:
                pass
            self._close()

    async def read(self, offset: int, wait: float) -> dict:
        self.touched = time.time()
        end = self.base + len(self.buf)
        if offset >= end and not self.closed and wait > 0:
            ev = self.event
            try:
                await asyncio.wait_for(ev.wait(), wait)
            except asyncio.TimeoutError:
                pass
            end = self.base + len(self.buf)
        start = max(offset, self.base)
        chunk = bytes(self.buf[start - self.base:]) if start < end else b""
        import base64
        return {"data": base64.b64encode(chunk).decode(), "offset": end, "skipped": start - offset if offset < self.base else 0,
                "closed": self.closed and start + len(chunk) >= end, "exit_code": self.exit_code}


def _pty_reap():
    now = time.time()
    for pid_, p in list(PTYS.items()):
        if p.closed and now - p.touched > 60:
            PTYS.pop(pid_, None)
        elif not p.closed and now - p.touched > PTY_IDLE:
            audit.info("PTY idle timeout id=%s", pid_)
            p.kill()


def _pty_get(req: Request):
    p = PTYS.get(req.path_params["pid"])
    if p is None:
        raise KeyError(req.path_params["pid"])
    return p


@mcp.custom_route("/api/pty", methods=["GET", "POST"])
async def rest_pty_open(req: Request):
    _pty_reap()
    if req.method == "GET":
        return JSONResponse([{"id": p.id, "closed": p.closed, "idle_s": int(time.time() - p.touched)} for p in PTYS.values()])
    if sum(not p.closed for p in PTYS.values()) >= PTY_MAX:
        return JSONResponse({"error": f"too many open terminals (max {PTY_MAX})"}, 429)
    b = await _body(req)
    try:
        p = _Pty(str(b.get("cwd") or DEFAULT_CWD), int(b.get("cols", 120)), int(b.get("rows", 32)))
    except (OSError, ImportError, ValueError) as e:
        return JSONResponse({"error": f"cannot open terminal: {e}"}, 500)
    PTYS[p.id] = p
    audit.info("PTY open id=%s pid=%s cwd=%s", p.id, p.pid, b.get("cwd") or DEFAULT_CWD)
    return JSONResponse({"id": p.id, "pid": p.pid})


@mcp.custom_route("/api/pty/{pid}/output", methods=["GET"])
async def rest_pty_output(req: Request):
    try:
        p = _pty_get(req)
    except KeyError:
        return JSONResponse({"error": "no such terminal"}, 404)
    wait = max(0.0, min(float(req.query_params.get("wait", 20)), 25.0))
    return JSONResponse(await p.read(int(req.query_params.get("offset", 0)), wait))


@mcp.custom_route("/api/pty/{pid}/input", methods=["POST"])
async def rest_pty_input(req: Request):
    try:
        p = _pty_get(req)
    except KeyError:
        return JSONResponse({"error": "no such terminal"}, 404)
    if p.closed:
        return JSONResponse({"error": "terminal has exited"}, 410)
    b = await _body(req)
    import base64
    try:
        data = base64.b64decode(b["b64"]) if "b64" in b else str(b.get("data", "")).encode()
        await asyncio.to_thread(p.write, data)
    except (OSError, ValueError, KeyError) as e:
        return JSONResponse({"error": str(e)}, 400)
    return JSONResponse({"ok": True})


@mcp.custom_route("/api/pty/{pid}/resize", methods=["POST"])
async def rest_pty_resize(req: Request):
    try:
        p = _pty_get(req)
        b = await _body(req)
        p.resize(b.get("cols", 120), b.get("rows", 32))
    except KeyError:
        return JSONResponse({"error": "no such terminal"}, 404)
    except (OSError, ValueError, TypeError) as e:
        return JSONResponse({"error": str(e)}, 400)
    return JSONResponse({"ok": True})


@mcp.custom_route("/api/pty/{pid}", methods=["DELETE"])
async def rest_pty_close(req: Request):
    try:
        p = _pty_get(req)
    except KeyError:
        return JSONResponse({"error": "no such terminal"}, 404)
    p.kill()
    return JSONResponse({"closed": p.id})


# ----------------------------------------------------------------- token admin UI (session cookie)
SEC_HEADERS = {
    "Cache-Control": "no-store", "X-Frame-Options": "DENY", "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                               "connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; "
                               "frame-ancestors 'none'",
}


def _ip(req: Request) -> str:
    return req.client.host if req.client else "?"


def _ui_guard(req: Request, need_session: bool = True):
    # custom header on every state-changing call: cross-site forms/fetches cannot set it (CSRF)
    if req.method not in ("GET", "HEAD") and req.headers.get("x-mva-ui") != "1":
        return JSONResponse({"error": "missing X-MVA-UI header"}, 403)
    if need_session and not valid_session(req.cookies.get(COOKIE, "")):
        return JSONResponse({"error": "login required"}, 401)
    return None


def _audit_tail(n: int = 60) -> list:
    try:
        with open(AUDIT_FILE, "rb") as f:
            f.seek(0, os.SEEK_END)
            f.seek(max(0, f.tell() - 64_000))
            return f.read().decode(errors="replace").splitlines()[-n:]
    except FileNotFoundError:
        return []


@mcp.custom_route("/", methods=["GET"])
async def root(req: Request):
    return RedirectResponse("/ui")


@mcp.custom_route("/ui", methods=["GET"])
async def ui_page(req: Request):
    return HTMLResponse(UI_FILE.read_text(), headers=SEC_HEADERS)


@mcp.custom_route("/ui/api/session", methods=["GET"])
async def ui_session(req: Request):
    return JSONResponse({"authenticated": valid_session(req.cookies.get(COOKIE, "")),
                         "admin_configured": admin_record() is not None,
                         "name": AGENT_NAME, "version": VERSION}, headers={"Cache-Control": "no-store"})


@mcp.custom_route("/ui/api/login", methods=["POST"])
async def ui_login(req: Request):
    if err := _ui_guard(req, need_session=False):
        return err
    ip = _ip(req)
    if _login_blocked(ip):
        return JSONResponse({"error": "Too many failed attempts. Wait a minute and try again."}, 429)
    pw = str((await _body(req)).get("password", ""))
    if not await asyncio.to_thread(check_admin_password, pw):
        _login_failed(ip)
        audit.info("UI login FAILED from %s", ip)
        return JSONResponse({"error": "Wrong password."}, 401)
    _FAILS.pop(ip, None)
    audit.info("UI login from %s", ip)
    r = JSONResponse({"ok": True})
    r.set_cookie(COOKIE, make_session(), max_age=SESSION_TTL, httponly=True, samesite="strict",
                 secure=bool(TLS_CERT), path="/ui")
    return r


@mcp.custom_route("/ui/api/logout", methods=["POST"])
async def ui_logout(req: Request):
    if err := _ui_guard(req, need_session=False):
        return err
    r = JSONResponse({"ok": True})
    r.delete_cookie(COOKIE, path="/ui")
    return r


@mcp.custom_route("/ui/api/tokens", methods=["GET", "POST"])
async def ui_tokens(req: Request):
    if err := _ui_guard(req):
        return err
    if req.method == "GET":
        return JSONResponse(TOKENS.list(), headers={"Cache-Control": "no-store"})
    b = await _body(req)
    try:
        rec, tok = TOKENS.create(b.get("name", ""), b.get("scopes") or [], b.get("ttl_days"))
    except (ValueError, TypeError) as e:
        return JSONResponse({"error": str(e)}, 400)
    return JSONResponse({"token": tok, "record": rec}, headers={"Cache-Control": "no-store"})


@mcp.custom_route("/ui/api/tokens/{tid}/revoke", methods=["POST"])
async def ui_token_revoke(req: Request):
    if err := _ui_guard(req):
        return err
    try:
        return JSONResponse(TOKENS.revoke(req.path_params["tid"]))
    except KeyError:
        return JSONResponse({"error": "no such token"}, 404)


@mcp.custom_route("/ui/api/tokens/{tid}", methods=["DELETE"])
async def ui_token_delete(req: Request):
    if err := _ui_guard(req):
        return err
    try:
        return JSONResponse(TOKENS.delete(req.path_params["tid"]))
    except KeyError:
        return JSONResponse({"error": "no such token"}, 404)


@mcp.custom_route("/ui/api/password", methods=["POST"])
async def ui_password(req: Request):
    if err := _ui_guard(req):
        return err
    b = await _body(req)
    if not await asyncio.to_thread(check_admin_password, str(b.get("current", ""))):
        return JSONResponse({"error": "Current password is wrong."}, 401)
    try:
        await asyncio.to_thread(set_admin_password, str(b.get("new", "")))
    except ValueError as e:
        return JSONResponse({"error": str(e)}, 400)
    r = JSONResponse({"ok": True})  # old sessions are now invalid; hand this browser a fresh one
    r.set_cookie(COOKIE, make_session(), max_age=SESSION_TTL, httponly=True, samesite="strict",
                 secure=bool(TLS_CERT), path="/ui")
    return r


@mcp.custom_route("/ui/api/overview", methods=["GET"])
async def ui_overview(req: Request):
    if err := _ui_guard(req):
        return err
    return JSONResponse({"info": await server_info(), "audit": _audit_tail(),
                         "ui_allow": [str(n) for n in UI_ALLOW]}, headers={"Cache-Control": "no-store"})


# ----------------------------------------------------------------- auth middleware
class Auth:
    """/health and / are public; /ui/* is limited to AGENT_UI_ALLOW networks (and uses its own
    session login); /mcp needs a token with scope 'mcp'; everything else needs scope 'rest'."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        path = scope["path"]
        ip = (scope.get("client") or ("?",))[0]
        if path in ("/health", "/"):
            return await self.app(scope, receive, send)
        if path == "/ui" or path.startswith("/ui/"):
            if not _ui_allowed(ip):
                audit.info("UI denied from %s (not in AGENT_UI_ALLOW)", ip)
                return await JSONResponse({"error": "UI not allowed from this address"}, 403)(scope, receive, send)
            CURRENT_TOKEN.set("ui")
            return await self.app(scope, receive, send)
        need = "mcp" if path == "/mcp" or path.startswith("/mcp/") else "rest"
        hdr = dict(scope["headers"]).get(b"authorization", b"").decode(errors="replace")
        tok = hdr[7:].strip() if hdr[:7].lower() == "bearer " else ""
        rec = TOKENS.verify(tok, need)
        if rec is None:
            audit.info("AUTH FAIL from %s path=%s scope=%s", ip, path, need)
            return await JSONResponse({"error": "unauthorized", "required_scope": need}, 401)(scope, receive, send)
        CURRENT_TOKEN.set(rec["name"])
        await self.app(scope, receive, send)


# ----------------------------------------------------------------- CLI + main
def _fmt_ts(ts) -> str:
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(ts)) if ts else "-"


def cli(argv: list) -> int:
    CURRENT_TOKEN.set("cli")
    ap = argparse.ArgumentParser(prog="server.py", description="mcp-vm-installer-agent")
    sub = ap.add_subparsers(dest="cmd", required=True)
    tok = sub.add_parser("token", help="manage API tokens").add_subparsers(dest="tcmd", required=True)
    tok.add_parser("list")
    c = tok.add_parser("create")
    c.add_argument("name")
    c.add_argument("--scopes", default="rest", help="comma list of: rest,mcp (default rest)")
    c.add_argument("--ttl-days", type=int, default=None)
    c.add_argument("--quiet", action="store_true", help="print only the token")
    for n in ("revoke", "delete"):
        tok.add_parser(n).add_argument("id")
    i = tok.add_parser("import", help="register an existing token (e.g. from an older install)")
    i.add_argument("name")
    i.add_argument("--file", required=True)
    i.add_argument("--scopes", default="rest,mcp")
    sub.add_parser("admin-password", help="set the UI admin password (prompt or AGENT_ADMIN_PASSWORD)")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "admin-password":
            pw = os.environ.get("AGENT_ADMIN_PASSWORD")
            if not pw:
                pw = getpass.getpass("New admin password (min 10 chars): ")
                if pw != getpass.getpass("Repeat: "):
                    print("Passwords do not match.", file=sys.stderr)
                    return 1
            set_admin_password(pw)
            print("Admin password set. Existing UI sessions are logged out.")
        elif a.tcmd == "list":
            rows = TOKENS.list()
            print(f"{'ID':9} {'NAME':24} {'SCOPES':9} {'STATUS':8} {'CREATED':17} {'EXPIRES':17} LAST USED")
            for r in rows:
                print(f"{r['id']:9} {r['name'][:24]:24} {','.join(r['scopes']):9} {r['status']:8} "
                      f"{_fmt_ts(r['created']):17} {_fmt_ts(r['expires']):17} {_fmt_ts(r['last_used'])}")
            if not rows:
                print("(no tokens)")
        elif a.tcmd == "create":
            rec, plain = TOKENS.create(a.name, a.scopes.split(","), a.ttl_days)
            if a.quiet:
                print(plain)
            else:
                print(f"Created token {rec['id']} ({a.name}, scopes {','.join(rec['scopes'])}). "
                      f"Shown once, store it now:\n\n{plain}\n")
        elif a.tcmd == "revoke":
            TOKENS.revoke(a.id)
            print(f"Revoked {a.id}.")
        elif a.tcmd == "delete":
            TOKENS.delete(a.id)
            print(f"Deleted {a.id}.")
        elif a.tcmd == "import":
            rec, _ = TOKENS.create(a.name, a.scopes.split(","), None,
                                   plaintext=Path(a.file).expanduser().read_text().strip())
            print(f"Imported as {rec['id']} ({a.name}, scopes {','.join(rec['scopes'])}).")
    except KeyError as e:
        print(f"No token with id {e}.", file=sys.stderr)
        return 1
    except ValueError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    return 0


def serve() -> None:
    _secret()
    for f in sorted(PLUGINS.glob("*.py")):
        try:
            _load_plugin(f)
        except Exception as e:
            audit.info("PLUGIN load failed %s: %r", f.name, e)
    if admin_record() is None:
        audit.info("WARNING: admin password not set, UI login disabled. Run: venv/bin/python server.py admin-password")
    active = sum(t["status"] == "active" for t in TOKENS.list())
    audit.info("START version=%s name=%s port=%s tls=%s plugins=%s active_tokens=%d ui_allow=%s",
               VERSION, AGENT_NAME, PORT, bool(TLS_CERT), sorted(LOADED), active,
               ",".join(str(n) for n in UI_ALLOW))
    tls = dict(ssl_certfile=TLS_CERT, ssl_keyfile=TLS_KEY) if TLS_CERT else {}
    uvicorn.run(Auth(mcp.streamable_http_app()), host=HOST, port=PORT, log_level="info", **tls)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] != "serve":
        sys.exit(cli(sys.argv[1:]))
    serve()
