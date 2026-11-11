package com.ailabs.vmadmin.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ailabs.vmadmin.Audit;
import com.ailabs.vmadmin.agent.AgentClient;
import com.ailabs.vmadmin.agent.AgentException;
import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.fleet.Host;
import com.ailabs.vmadmin.secrets.TokenStore;
import com.ailabs.vmadmin.sync.Diff;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per-host features built on the agent's command API: systemd services, packages, log tails, token
 * rotation, file sync with diff, and the web-terminal proxy. Inputs that end up in a shell command are
 * validated against strict patterns and single-quoted.
 */
@Service
public class HostAdmin {

    static final Pattern UNIT = Pattern.compile("^[A-Za-z0-9@._:\\\\-]{1,200}$");
    static final Pattern PKG = Pattern.compile("^[A-Za-z0-9.+_:-]{1,100}$");
    static final Set<String> SERVICE_ACTIONS = Set.of("start", "stop", "restart", "reload", "enable", "disable");
    static final Set<String> PRIORITIES = Set.of("emerg", "alert", "crit", "err", "warning", "notice", "info", "debug");
    static final int TAIL_CHUNK = 65536;

    private final Operations ops;
    private final FleetService fleet;
    private final TokenStore tokens;

    public HostAdmin(Operations ops, TokenStore tokens) {
        this.ops = ops;
        this.fleet = ops.fleet();
        this.tokens = tokens;
    }

    public static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String need(String value, Pattern p, String what) {
        if (value == null || !p.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + what + ": " + value);
        }
        return value;
    }

    private static String sudoHint(String out) {
        return out != null && (out.contains("a password is required") || out.contains("a terminal is required"))
                ? out + "\nThe agent user needs passwordless sudo for this (e.g. a sudoers rule for systemctl / apt-get)."
                : out;
    }

    // ------------------------------------------------------------------ services

    static final String SERVICES_CMD = "systemctl list-units --type=service --all --no-legend --plain --no-pager 2>&1; "
            + "echo '@@FILES@@'; systemctl list-unit-files --type=service --no-legend --no-pager 2>&1";

    public List<Map<String, String>> services(String host) {
        String out = fleet.client(host).run(SERVICES_CMD, "/", 30).path("output").asText("");
        return parseServices(out);
    }

    static List<Map<String, String>> parseServices(String out) {
        Map<String, Map<String, String>> units = new TreeMap<>();
        String[] parts = out.split("@@FILES@@", 2);
        for (String line : parts[0].split("\n")) {
            String[] c = line.trim().split("\\s+", 5);
            if (c.length < 4 || !c[0].endsWith(".service")) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("unit", c[0]);
            m.put("load", c[1]);
            m.put("active", c[2]);
            m.put("sub", c[3]);
            m.put("description", c.length > 4 ? c[4] : "");
            m.put("enabled", "");
            units.put(c[0], m);
        }
        if (parts.length > 1) {
            for (String line : parts[1].split("\n")) {
                String[] c = line.trim().split("\\s+");
                if (c.length < 2 || !c[0].endsWith(".service") || c[0].contains("@.")) {
                    continue;
                }
                Map<String, String> m = units.computeIfAbsent(c[0], k -> {
                    Map<String, String> n = new LinkedHashMap<>();
                    n.put("unit", k);
                    n.put("load", "loaded");
                    n.put("active", "inactive");
                    n.put("sub", "dead");
                    n.put("description", "");
                    return n;
                });
                m.put("enabled", c[1]);
            }
        }
        return new ArrayList<>(units.values());
    }

    public Map<String, Object> serviceStatus(String host, String unit) {
        need(unit, UNIT, "unit");
        JsonNode r = fleet.client(host).run("systemctl status " + sq(unit) + " --no-pager -l -n 40 2>&1", "/", 30);
        return Map.of("unit", unit, "output", r.path("output").asText(""), "exit_code", r.path("exit_code").asInt(-1));
    }

    public Map<String, Object> serviceAction(String host, String unit, String action, boolean confirm) {
        need(unit, UNIT, "unit");
        if (!SERVICE_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("action must be one of " + SERVICE_ACTIONS);
        }
        JsonNode r = ops.run(host, "sudo -n systemctl " + action + " " + sq(unit) + " 2>&1", "/", 120, confirm);
        String state = fleet.client(host).run("systemctl is-active " + sq(unit) + "; systemctl is-enabled " + sq(unit) + " 2>/dev/null", "/", 20)
                .path("output").asText("").trim().replace('\n', ' ');
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unit", unit);
        out.put("action", action);
        out.put("exit_code", r.path("exit_code").asInt(-1));
        out.put("output", sudoHint(r.path("output").asText("")));
        out.put("state", state);
        return out;
    }

    // ------------------------------------------------------------------ packages

    static final String UPGRADABLE_CMD = "if command -v apt-get >/dev/null; then echo PM=apt; apt list --upgradable 2>/dev/null | tail -n +2; "
            + "elif command -v dnf >/dev/null; then echo PM=dnf; dnf -q check-update 2>/dev/null; "
            + "elif command -v yum >/dev/null; then echo PM=yum; yum -q check-update 2>/dev/null; else echo PM=none; fi; true";
    static final Pattern APT_LINE = Pattern.compile("^([^/\\s]+)/(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+\\[upgradable from: ([^\\]]+)\\]");

    public Map<String, Object> upgradable(String host) {
        String out = fleet.client(host).run(UPGRADABLE_CMD, "/", 120).path("output").asText("");
        return parseUpgradable(out);
    }

    static Map<String, Object> parseUpgradable(String out) {
        String pm = "none";
        List<Map<String, String>> pkgs = new ArrayList<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("PM=")) {
                pm = line.substring(3);
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            if ("apt".equals(pm)) {
                Matcher m = APT_LINE.matcher(line);
                if (m.find()) {
                    pkgs.add(Map.of("name", m.group(1), "repo", m.group(2), "version", m.group(3), "current", m.group(5)));
                }
            } else if ("dnf".equals(pm) || "yum".equals(pm)) {
                String[] c = line.split("\\s+");
                if (c.length == 3 && !line.startsWith("Obsoleting") && c[0].contains(".")) {
                    pkgs.add(Map.of("name", c[0].substring(0, c[0].lastIndexOf('.')), "repo", c[2], "version", c[1], "current", ""));
                }
            }
        }
        return Map.of("manager", pm, "packages", pkgs);
    }

    public List<Map<String, String>> installed(String host, String query) {
        need(query, PKG, "package search");
        String q = sq(query);
        String cmd = "if command -v dpkg-query >/dev/null; then dpkg-query -W -f='${binary:Package}\\t${Version}\\t${db:Status-Abbrev}\\n' 2>/dev/null; "
                + "elif command -v rpm >/dev/null; then rpm -qa --qf '%{NAME}\\t%{VERSION}-%{RELEASE}\\tii\\n'; fi | grep -i -F -- " + q + " | head -200";
        String out = fleet.client(host).run(cmd, "/", 60).path("output").asText("");
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] c = line.split("\t");
            if (c.length >= 2) {
                rows.add(Map.of("name", c[0], "version", c[1], "status", c.length > 2 ? c[2].trim() : ""));
            }
        }
        return rows;
    }

    /** Package changes run as background jobs (they can take minutes); follow them in the Jobs tab. */
    public JsonNode packageAction(String host, String manager, String action, String pkg, boolean confirm) {
        boolean apt = "apt".equals(manager);
        if (!apt && !"dnf".equals(manager) && !"yum".equals(manager)) {
            throw new IllegalArgumentException("unsupported package manager " + manager);
        }
        String cmd = switch (action) {
            case "refresh" -> apt ? "sudo -n apt-get update" : "sudo -n " + manager + " -q makecache";
            case "upgrade" -> {
                String p = sq(need(pkg, PKG, "package"));
                yield apt ? "sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y --only-upgrade " + p
                        : "sudo -n " + manager + " -y upgrade " + p;
            }
            case "upgrade-all" -> {
                if (!confirm) {
                    throw new Operations.NeedsConfirmation("upgrades every package on the host");
                }
                yield apt ? "sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -y upgrade" : "sudo -n " + manager + " -y upgrade";
            }
            default -> throw new IllegalArgumentException("action must be refresh, upgrade or upgrade-all");
        };
        return ops.startJob(host, cmd + " 2>&1", "/", true);
    }

    // ------------------------------------------------------------------ logs

    static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9=;_-]{1,400}$");

    public Map<String, Object> journal(String host, String unit, String priority, String cursor, int lines) {
        StringBuilder cmd = new StringBuilder("journalctl --no-pager -o short-iso --show-cursor");
        if (unit != null && !unit.isBlank()) {
            cmd.append(" -u ").append(sq(need(unit, UNIT, "unit")));
        }
        if (priority != null && !priority.isBlank()) {
            if (!PRIORITIES.contains(priority)) {
                throw new IllegalArgumentException("priority must be one of " + PRIORITIES);
            }
            cmd.append(" -p ").append(priority);
        }
        if (cursor != null && !cursor.isBlank()) {
            cmd.append(" --after-cursor=").append(sq(need(cursor, CURSOR, "cursor")));
        } else {
            cmd.append(" -n ").append(Math.max(10, Math.min(lines, 2000)));
        }
        String out = fleet.client(host).run(cmd + " 2>&1", "/", 30).path("output").asText("");
        List<String> rows = new ArrayList<>();
        String next = cursor;
        for (String line : out.split("\n")) {
            if (line.startsWith("-- cursor: ")) {
                next = line.substring(11).trim();
            } else if (!line.isEmpty() && !line.equals("-- No entries --")) {
                rows.add(line);
            }
        }
        return Map.of("lines", rows, "cursor", next == null ? "" : next);
    }

    /** Follow a file by byte offset; {@code offset < 0} starts at the last 64 KB. Handles truncation. */
    public Map<String, Object> fileTail(String host, String path, long offset) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        String cmd = "f=" + sq(path) + "; s=$(stat -L -c %s -- \"$f\") || exit 1; o=" + offset + "; "
                + "if [ \"$o\" -lt 0 ] || [ \"$o\" -gt \"$s\" ]; then o=$(( s > " + TAIL_CHUNK + " ? s - " + TAIL_CHUNK + " : 0 )); fi; "
                + "n=$(( s - o > " + TAIL_CHUNK + " ? " + TAIL_CHUNK + " : s - o )); echo \"@@$o@@$n@@$s@@\"; "
                + "[ \"$n\" -gt 0 ] && tail -c +$((o+1)) -- \"$f\" | head -c \"$n\"; true";
        JsonNode r = fleet.client(host).run(cmd, "/", 30);
        String out = r.path("output").asText("");
        Matcher m = Pattern.compile("^@@(\\d+)@@(\\d+)@@(\\d+)@@\n?").matcher(out);
        if (r.path("exit_code").asInt(1) != 0 || !m.find()) {
            throw new AgentException(404, out.isBlank() ? "cannot read " + path : out.trim());
        }
        long from = Long.parseLong(m.group(1));
        long n = Long.parseLong(m.group(2));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("data", out.substring(m.end()));
        res.put("from", from);
        res.put("offset", from + n);
        res.put("size", Long.parseLong(m.group(3)));
        res.put("reset", offset >= 0 && from != offset);
        return res;
    }

    // ------------------------------------------------------------------ token rotation

    /**
     * Mint a new token on the VM, switch to it, then revoke the old one. Only for hosts whose token is
     * kept in tokens.yml ({@code store:}); for vault:/env:/path: references, rotate at the source.
     */
    public Map<String, Object> rotateToken(String host) {
        AgentClient c = fleet.writable(host);
        Host h = c.host();
        if (!("store:" + host).equals(h.tokenRef())) {
            throw new IllegalArgumentException("Rotation is automatic only for pasted tokens (store:" + host + "); rotate "
                    + h.tokenRef() + " where it is kept");
        }
        String old = tokens.get(host).orElseThrow(() -> new IllegalStateException("no stored token for " + host));
        FleetService.Status st = fleet.refresh(host);
        if (st.base() == null) {
            throw new AgentException(502, host + " is not reachable: " + st.message());
        }
        String base = sq(st.base());
        String name = "vm-admin-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        JsonNode created = c.run("cd " + base + " && ./venv/bin/python server.py token create " + sq(name) + " --scopes rest,mcp --quiet", "/", 60);
        String fresh = created.path("output").asText("").trim();
        fresh = fresh.contains("\n") ? fresh.substring(fresh.lastIndexOf('\n') + 1).trim() : fresh;
        if (created.path("exit_code").asInt(1) != 0 || !fresh.startsWith("mva_")) {
            throw new AgentException(502, "Could not create a token on " + host + " (is the agent installed with its venv in "
                    + st.base() + "?)");
        }
        String newToken = fresh;
        new AgentClient(h, () -> newToken).info(Duration.ofSeconds(10));  // proves the new token works
        tokens.put(host, newToken);
        fleet.forget(host);
        Audit.log(host, "TOKEN_ROTATE", "new token " + name + " in use");
        String revoke = "cd " + base + " && id=$(./venv/bin/python -c 'import json,sys; d=json.load(open(\"tokens.json\")); "
                + "print(next(r[\"id\"] for r in d[\"tokens\"] if r[\"hash\"]==sys.argv[1]))' " + sha256(old) + ") && ./venv/bin/python server.py token revoke \"$id\"";
        JsonNode rv = fleet.client(host).run(revoke, "/", 60);
        boolean revoked = rv.path("exit_code").asInt(1) == 0;
        Audit.log(host, "TOKEN_ROTATE", revoked ? "old token revoked" : "old token NOT revoked: " + rv.path("output").asText(""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("newTokenName", name);
        out.put("oldRevoked", revoked);
        if (!revoked) {
            out.put("message", "The new token is in use, but revoking the old one failed; revoke it in the agent's token UI.");
        }
        return out;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ file sync

    public Map<String, Object> syncPreview(String path, String content, List<String> hosts) {
        if (path == null || path.isBlank() || content == null) {
            throw new IllegalArgumentException("path and content are required");
        }
        return fleet.fanOut(hosts, h -> {
            Map<String, Object> r = new LinkedHashMap<>();
            String current = null;
            try {
                JsonNode f = fleet.client(h).read(path, 2_000_000);
                if (f.path("binary").asBoolean() || f.path("truncated").asBoolean()) {
                    r.put("status", "error");
                    r.put("error", "existing file is binary or larger than 2 MB");
                    return r;
                }
                current = f.path("content").asText("");
            } catch (AgentException e) {
                if (e.status() != 404) {
                    throw e;
                }
            }
            if (current == null) {
                r.put("status", "new");
                r.put("diff", Diff.unified("/dev/null", path, "", content));
            } else if (current.equals(content)) {
                r.put("status", "same");
            } else {
                r.put("status", "changed");
                r.put("diff", Diff.unified(path + " (now)", path + " (new)", current, content));
            }
            r.put("readOnly", fleet.store().require(h).readOnly());
            return r;
        });
    }

    public Map<String, Object> syncApply(String path, String content, List<String> hosts, boolean backup) {
        Audit.log(String.join(",", hosts), "SYNC", path + " (" + content.length() + " chars)");
        return fleet.fanOut(hosts, h -> ops.saveText(h, path, content, backup));
    }

    // ------------------------------------------------------------------ web terminal

    public JsonNode ptyOpen(String host, String cwd, int cols, int rows) {
        AgentClient c = fleet.writable(host);
        JsonNode r = c.ptyOpen(cwd, cols, rows);
        Audit.log(host, "TERMINAL_OPEN", "id=" + r.path("id").asText() + " cwd=" + (cwd == null ? "-" : cwd));
        return r;
    }

    public JsonNode ptyOutput(String host, String id, long offset) {
        return fleet.writable(host).ptyOutput(id, offset, 20);
    }

    public JsonNode ptyInput(String host, String id, String b64) {
        return fleet.writable(host).ptyInput(id, b64);
    }

    public JsonNode ptyResize(String host, String id, int cols, int rows) {
        return fleet.writable(host).ptyResize(id, cols, rows);
    }

    public JsonNode ptyClose(String host, String id) {
        Audit.log(host, "TERMINAL_CLOSE", "id=" + id);
        return fleet.writable(host).ptyClose(id);
    }
}
