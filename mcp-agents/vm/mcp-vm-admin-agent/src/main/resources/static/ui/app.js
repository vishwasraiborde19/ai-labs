'use strict';
/* VM Control Centre UI. Plain DOM, no innerHTML with data: everything goes through h(). */

const S = {
  session: null, fleet: { hosts: [], version: null }, view: 'fleet', host: null, tab: 'overview',
  files: {}, term: {}, hidden: true, timers: [],
};

// ------------------------------------------------------------------ helpers
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style') el.style.cssText = v;  // CSSOM, allowed by the CSP (style attributes are not)
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'value') el.value = v;
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : String(kid));
  }
  return el;
}
const $ = (id) => document.getElementById(id);

class ApiError extends Error {
  constructor(status, data) { super((data && data.error) || ('HTTP ' + status)); this.status = status; this.data = data || {}; }
}

async function api(method, path, body, raw) {
  const opt = { method, headers: { 'X-VM-Admin': '1' } };
  if (raw !== undefined) { opt.body = raw; opt.headers['Content-Type'] = 'application/octet-stream'; }
  else if (body !== undefined) { opt.body = JSON.stringify(body); opt.headers['Content-Type'] = 'application/json'; }
  const r = await fetch('/ui/api' + path, opt);
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text.slice(0, 300) }; }
  if (!r.ok) throw new ApiError(r.status, data);
  return data;
}
const enc = encodeURIComponent;
const hp = (name) => '/hosts/' + enc(name);

function toast(msg, bad) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast show' + (bad ? ' bad' : '');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => { t.className = 'toast'; }, bad ? 6000 : 2500);
}
const fail = (e) => toast(e.message || String(e), true);

function fmtBytes(n) {
  if (n == null || isNaN(n)) return '';
  n = Number(n);
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return (i ? n.toFixed(n < 10 ? 1 : 0) : n) + ' ' + u[i];
}
function fmtDur(s) {
  if (s == null || isNaN(s)) return '';
  s = Number(s);
  const d = Math.floor(s / 86400), hr = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
  return d ? `${d}d ${hr}h` : hr ? `${hr}h ${m}m` : m ? `${m}m` : `${s}s`;
}
function ago(iso) {
  if (!iso) return 'never';
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  return s < 5 ? 'just now' : fmtDur(s) + ' ago';
}
const fmtTime = (epoch) => epoch ? new Date(epoch * 1000).toLocaleString() : '';
function cmpVer(a, b) {
  const pa = String(a || '0').split('.').map(Number), pb = String(b || '0').split('.').map(Number);
  for (let i = 0; i < 3; i++) { if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) - (pb[i] || 0); }
  return 0;
}
function joinPath(dir, name) { return (dir === '/' ? '' : dir.replace(/\/+$/, '')) + '/' + name; }
function parentOf(p) { const i = p.replace(/\/+$/, '').lastIndexOf('/'); return i <= 0 ? '/' : p.slice(0, i); }
function save(key, v) { try { localStorage.setItem('vmadmin.' + key, JSON.stringify(v)); } catch { /* private mode */ } }
function load(key, d) { try { const v = localStorage.getItem('vmadmin.' + key); return v ? JSON.parse(v) : d; } catch { return d; } }

function hostRec(name) { return S.fleet.hosts.find((x) => x.host.name === name); }
function stateOf(rec) { return (rec && rec.status && rec.status.state) || 'unknown'; }
function outdated(rec) {
  const b = S.session && S.session.bundledAgentVersion;
  return b && rec && rec.status && rec.status.version && cmpVer(rec.status.version, b) < 0;
}
function hasFiles(rec) { return rec && rec.status && (rec.status.features || []).includes('fs'); }
function clearTimers() {
  S.timers.forEach(clearInterval); S.timers = [];
  (S.cleanups || []).splice(0).forEach((fn) => { try { fn(); } catch { /* ignore */ } });
}

// ------------------------------------------------------------------ dialogs
const dlg = () => $('dlg');
function openDialog(title, body, foot, wide) {
  const d = dlg();
  d.className = wide ? 'wide' : '';
  d.replaceChildren(
    h('div', { class: 'dlg-head' }, h('h3', {}, title), h('button', { class: 'ghost', 'aria-label': 'Close', onclick: () => d.close() }, '✕')),
    h('div', { class: 'dlg-body' }, body),
    foot ? h('div', { class: 'dlg-foot' }, foot) : null);
  if (!d.open) d.showModal();
  return d;
}
function closeDialog() { if (dlg().open) dlg().close(); }

/** Resolves true when the user confirms. With `typed`, they must type that word first. */
function confirmBox(title, message, okLabel, typed) {
  return new Promise((resolve) => {
    const input = typed ? h('input', { type: 'text', autocomplete: 'off', 'aria-label': 'Type to confirm' }) : null;
    const ok = h('button', { class: 'primary danger', disabled: !!typed }, okLabel || 'Confirm');
    if (input) input.addEventListener('input', () => { ok.disabled = input.value.trim() !== typed; });
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); closeDialog(); } };
    ok.onclick = () => finish(true);
    const d = openDialog(title, [
      h('div', { class: 'warnbox' }, message),
      input ? h('div', { class: 'field' }, h('label', {}, `Type ${typed} to confirm`), input) : null,
    ], [h('button', { onclick: () => finish(false) }, 'Cancel'), ok]);
    d.addEventListener('close', () => finish(false), { once: true });
    setTimeout(() => (input || ok).focus(), 30);
  });
}

/** Calls fn(false); if the server says the command is risky (428), asks and calls fn(true). */
async function withConfirm(fn, targetLabel) {
  try {
    return await fn(false);
  } catch (e) {
    if (e.status !== 428) throw e;
    const ok = await confirmBox('Risky command', [
      h('p', { style: 'margin:0 0 6px' }, h('strong', {}, 'This command ' + e.data.reason + '.')),
      h('p', { style: 'margin:0' }, 'It will run on ', h('strong', {}, targetLabel), '. Are you sure?'),
    ], 'Run it', targetLabel.includes(' ') ? 'yes' : targetLabel);
    if (!ok) return null;
    return fn(true);
  }
}

// ------------------------------------------------------------------ data
async function loadFleet() {
  S.fleet = await api('GET', '/fleet');
  renderSide();
}

// ------------------------------------------------------------------ sidebar
function renderSide() {
  const q = ($('filter').value || '').toLowerCase();
  const list = $('hostlist');
  list.replaceChildren();
  const groups = {};
  for (const r of S.fleet.hosts) {
    const hst = r.host;
    const hay = [hst.name, hst.url, hst.group, ...(hst.tags || [])].join(' ').toLowerCase();
    if (q && !hay.includes(q)) continue;
    (groups[hst.group || ''] = groups[hst.group || ''] || []).push(r);
  }
  const keys = Object.keys(groups).sort((a, b) => (a === '') - (b === '') || a.localeCompare(b));
  for (const g of keys) {
    if (keys.length > 1 || g) list.append(h('li', { class: 'group-label' }, g || 'Ungrouped'));
    for (const r of groups[g]) {
      const st = stateOf(r);
      list.append(h('li', {}, h('button', {
        class: S.view === 'host' && S.host === r.host.name ? 'on' : '',
        title: `${r.host.name}: ${st}`,
        onclick: () => openHost(r.host.name),
      }, h('span', { class: 'dot ' + st, 'aria-hidden': 'true' }), h('span', { class: 'nm' }, r.host.name),
      r.host.readOnly ? h('span', { class: 'grp' }, 'RO') : null)));
    }
  }
  if (!S.fleet.hosts.length) list.append(h('li', { class: 'muted', style: 'padding:8px' }, 'No hosts yet.'));
}

function setView(v) {
  S.view = v;
  clearTimers();
  document.querySelectorAll('#views button').forEach((b) => b.classList.toggle('on', b.dataset.view === v));
  $('side').classList.remove('open');
  renderSide();
  ({ fleet: renderFleet, broadcast: renderBroadcast, audit: renderAudit, host: renderHost,
    sync: renderSync, schedules: renderSchedules, alerts: renderAlerts })[v]();
  $('main').focus({ preventScroll: true });
}
function openHost(name, tab) {
  S.host = name;
  S.tab = tab || (S.view === 'host' ? S.tab : 'overview');
  setView('host');
}

// ------------------------------------------------------------------ fleet view
function renderFleet() {
  const main = $('main');
  const hosts = S.fleet.hosts;
  const count = (s) => hosts.filter((r) => stateOf(r) === s).length;
  const refresh = h('button', { onclick: async () => {
    refresh.disabled = true;
    try { await api('POST', '/fleet/refresh'); await loadFleet(); renderFleet(); } catch (e) { fail(e); } finally { refresh.disabled = false; }
  } }, 'Check all now');
  const rows = hosts.map((r) => {
    const st = r.status || {};
    const hst = r.host;
    return h('tr', { class: 'click', onclick: () => openHost(hst.name) },
      h('td', {}, h('span', { class: 'row' }, h('span', { class: 'dot ' + stateOf(r) }), h('span', { class: 'state ' + stateOf(r) }, stateOf(r)))),
      h('td', {}, h('strong', {}, hst.name), h('div', {},
        hst.group ? h('span', { class: 'chip' }, hst.group) : null,
        (hst.tags || []).map((t) => h('span', { class: 'chip' }, '#' + t)),
        hst.readOnly ? h('span', { class: 'chip ro' }, 'read-only') : null)),
      h('td', { class: 'mono' }, hst.url),
      h('td', {}, st.version || '', outdated(r) ? h('div', {}, h('span', { class: 'chip ro' }, 'upgrade available')) : null),
      h('td', { class: 'num' }, fmtDur(st.uptimeS)),
      h('td', { class: 'num' }, st.latencyMs != null ? st.latencyMs + ' ms' : ''),
      h('td', { class: 'muted', title: st.message || '' }, ago(st.checkedAt), st.message && stateOf(r) !== 'online' ? h('div', { class: 'err', style: 'font-size:12px' }, st.message) : null));
  });
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Fleet'),
      h('span', { class: 'meta' }, `${hosts.length} hosts · ${count('online')} online · ${count('offline')} offline` +
        (count('unauthorized') + count('error') ? ` · ${count('unauthorized') + count('error')} need attention` : '')),
      h('span', { class: 'spacer' }), refresh, h('button', { class: 'primary', onclick: () => hostForm() }, 'Add host')),
    hosts.length ? h('div', { class: 'card', style: 'padding:6px 8px' }, h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, ['Status', 'Host', 'Agent URL', 'Agent', 'Uptime', 'Latency', 'Checked'].map((x) => h('th', {}, x)))),
      h('tbody', {}, rows))))
      : h('div', { class: 'card' }, h('h3', {}, 'Add your first VM'),
        h('p', {}, 'Each VM runs mcp-vm-worker-agent. Install it on the VM, create a token with the ',
          h('code', {}, 'rest'), ' scope (add ', h('code', {}, 'mcp'), ' to allow one-click upgrades), then click Add host and paste the URL and token.'),
        h('pre', { class: 'out' }, './deploy.sh user@vm --name web-01\n# or on the VM:\n~/mcp-vm-installer-agent/venv/bin/python ~/mcp-vm-installer-agent/server.py token create control-centre --scopes rest,mcp')),
    h('p', { class: 'hint' }, 'Fleet file: ', h('span', { class: 'mono' }, S.fleet.file || ''), '. Status is re-checked in the background every 30 seconds.'));
}

// ------------------------------------------------------------------ host form
function hostForm(existing) {
  const x = existing || { tags: [] };
  const usesStore = !existing || (x.tokenRef || '').startsWith('store:');
  const f = {
    name: h('input', { type: 'text', value: x.name || '', placeholder: 'web-01', autocomplete: 'off' }),
    url: h('input', { type: 'text', value: x.url || '', placeholder: 'http://192.168.1.50:8765', autocomplete: 'off' }),
    token: h('input', { type: 'password', autocomplete: 'new-password', placeholder: existing && usesStore ? 'Leave empty to keep the current token' : 'mva_...' }),
    tokenRef: h('input', { type: 'text', value: usesStore ? '' : (x.tokenRef || ''), placeholder: 'vault:vms/web-01#token  or  env:WEB01_TOKEN' }),
    group: h('input', { type: 'text', value: x.group || '', placeholder: 'prod' }),
    tags: h('input', { type: 'text', value: (x.tags || []).join(', '), placeholder: 'db, eu-west' }),
    readOnly: h('input', { type: 'checkbox', checked: !!x.readOnly }),
    insecure: h('input', { type: 'checkbox', checked: !!x.insecureSkipTlsVerify }),
    ca: h('input', { type: 'text', value: (x.caCertRef || '').replace(/^path:/, ''), placeholder: '/home/me/agent-cert.pem' }),
    notes: h('textarea', { rows: 2 }, x.notes || ''),
  };
  const out = h('div', { class: 'hint', role: 'status' });
  const build = () => ({
    name: f.name.value.trim(), url: f.url.value.trim(),
    tokenRef: f.token.value.trim() ? null : (f.tokenRef.value.trim() || (existing && usesStore ? x.tokenRef : null)),
    group: f.group.value.trim() || null, tags: f.tags.value.split(',').map((s) => s.trim()).filter(Boolean),
    readOnly: f.readOnly.checked, insecureSkipTlsVerify: f.insecure.checked,
    caCertRef: f.ca.value.trim() ? 'path:' + f.ca.value.trim() : null, notes: f.notes.value.trim() || null,
  });
  const test = h('button', { onclick: async () => {
    out.className = 'hint'; out.textContent = 'Testing…'; test.disabled = true;
    try {
      const r = await api('POST', '/fleet/test', { host: build(), token: f.token.value.trim() || null });
      out.className = 'hint okc';
      out.textContent = `Connected in ${r.latencyMs} ms. Agent ${r.version} on ${r.hostname || r.agentName}; token accepted.` +
        (r.files ? '' : ' This agent has no file API yet; upgrade it after adding.');
    } catch (e) { out.className = 'hint err'; out.textContent = e.message; } finally { test.disabled = false; }
  } }, 'Test connection');
  const saveBtn = h('button', { class: 'primary', onclick: async () => {
    saveBtn.disabled = true;
    try {
      await api('POST', '/fleet/hosts', { host: build(), token: f.token.value.trim() || null,
        originalName: existing ? x.name : null, version: S.fleet.version });
      closeDialog();
      await loadFleet();
      toast('Saved ' + build().name);
      openHost(build().name, existing ? S.tab : 'overview');
    } catch (e) { out.className = 'hint err'; out.textContent = e.message; } finally { saveBtn.disabled = false; }
  } }, existing ? 'Save' : 'Add host');
  const del = existing ? h('button', { class: 'danger', style: 'margin-right:auto', onclick: async () => {
    if (!await confirmBox('Remove host', `Remove ${x.name} from the control centre? The VM and its agent are not touched.`, 'Remove')) return;
    try {
      await api('DELETE', '/fleet/hosts/' + enc(x.name) + '?version=' + enc(S.fleet.version));
      await loadFleet(); toast('Removed ' + x.name); setView('fleet');
    } catch (e) { fail(e); }
  } }, 'Remove') : null;
  const field = (label, el, hint) => h('div', { class: 'field' }, h('label', {}, label), el, hint ? h('p', { class: 'hint' }, hint) : null);
  openDialog(existing ? 'Edit ' + x.name : 'Add host', [
    h('div', { class: 'grid g2' }, field('Name', f.name), field('Agent URL', f.url)),
    field('Agent token', f.token, 'Kept in tokens.yml (mode 600) on this machine, never shown again. Needs scope rest; add mcp for one-click upgrades.'),
    h('details', { open: !usesStore }, h('summary', { class: 'muted', style: 'cursor:pointer;margin-bottom:8px' }, 'Or use a token reference (Vault, env, file)'),
      field('Token reference', f.tokenRef, 'vault:path#field, env:VAR or path:/file. Ignored when a token is pasted above.')),
    h('div', { class: 'grid g2' }, field('Group', f.group), field('Tags (comma separated)', f.tags)),
    h('div', { class: 'checks field' },
      h('label', {}, f.readOnly, 'Read-only (browse and read, no commands or changes)'),
      h('label', {}, f.insecure, 'Skip TLS verification (labs)')),
    field('CA certificate (for agents installed with --tls)', f.ca),
    field('Notes', f.notes),
    out,
  ], [del, existing && usesStore && !x.readOnly ? h('button', { onclick: () => rotateToken(x.name) }, 'Rotate token') : null,
    test, h('button', { onclick: closeDialog }, 'Cancel'), saveBtn]);
  setTimeout(() => (existing ? f.url : f.name).focus(), 30);
}

// ------------------------------------------------------------------ host view
function renderHost() {
  const rec = hostRec(S.host);
  const main = $('main');
  if (!rec) { setView('fleet'); return; }
  const hst = rec.host, st = rec.status || {};
  const tabs = [['overview', 'Overview'], ['files', 'Files'], ['shell', 'Shell'], ['terminal', 'Run'], ['jobs', 'Jobs'],
    ['services', 'Services'], ['packages', 'Packages'], ['logs', 'Logs'], ['log', 'Agent log']];
  const body = h('div');
  const upgrade = outdated(rec) && !hst.readOnly ? h('button', { class: 'primary', onclick: () => doUpgrade(hst.name) },
    `Upgrade agent to ${S.session.bundledAgentVersion}`) : null;
  main.replaceChildren(
    h('div', { class: 'page-head' },
      h('span', { class: 'dot ' + stateOf(rec) }),
      h('h2', {}, hst.name),
      h('span', { class: 'meta' }, h('span', { class: 'state ' + stateOf(rec) }, stateOf(rec)), st.version ? ' · agent ' + st.version : '',
        ' · ', h('span', { class: 'mono' }, hst.url)),
      hst.group ? h('span', { class: 'chip' }, hst.group) : null,
      (hst.tags || []).map((t) => h('span', { class: 'chip' }, '#' + t)),
      hst.readOnly ? h('span', { class: 'chip ro' }, 'read-only') : null,
      h('span', { class: 'spacer' }),
      upgrade,
      h('button', { onclick: async () => { try { await api('GET', hp(hst.name) + '/status'); await loadFleet(); renderHost(); } catch (e) { fail(e); } } }, 'Re-check'),
      h('button', { onclick: () => hostForm(hst) }, 'Settings')),
    st.message && stateOf(rec) !== 'online' ? h('div', { class: 'warnbox' }, st.message) : null,
    h('div', { class: 'tabs', role: 'tablist' }, tabs.map(([id, label]) => h('button', {
      role: 'tab', class: S.tab === id ? 'on' : '', 'aria-selected': String(S.tab === id),
      onclick: () => { S.tab = id; clearTimers(); renderHost(); },
    }, label))),
    body);
  ({ overview: tabOverview, files: tabFiles, shell: tabShell, terminal: tabTerminal, jobs: tabJobs, services: tabServices,
    packages: tabPackages, logs: tabLogs, log: tabLog })[S.tab](body, rec);
}

async function doUpgrade(name) {
  const rec = hostRec(name);
  const ok = await confirmBox('Upgrade agent', [
    h('p', { style: 'margin:0 0 6px' }, `Replace the agent on ${name} (${rec.status.version}) with ${S.session.bundledAgentVersion} and restart it.`),
    h('p', { style: 'margin:0' }, 'Running jobs keep going. If the new version crashes on start, the VM rolls back automatically. Needs a token with the mcp scope.'),
  ], 'Upgrade');
  if (!ok) return;
  toast('Upgrading ' + name + '… this takes up to a minute');
  try {
    const r = await api('POST', hp(name) + '/upgrade');
    await loadFleet();
    if (r.ok) toast(`${name} now runs ${r.now}`); else toast(r.message || 'Upgrade not confirmed', true);
    renderHost();
  } catch (e) { fail(e); }
}

// ---------- overview
async function tabOverview(body, rec) {
  body.replaceChildren(h('p', { class: 'muted' }, 'Loading system information…'));
  if (stateOf(rec) !== 'online') { body.replaceChildren(h('p', { class: 'muted' }, 'The host is not online, so there is nothing to show.')); return; }
  let s;
  try { s = await api('GET', hp(rec.host.name) + '/sysinfo'); } catch (e) { body.replaceChildren(h('p', { class: 'err' }, e.message)); return; }
  if (S.view !== 'host' || S.tab !== 'overview') return;
  const memT = Number(s.mem_total_kb) * 1024, memA = Number(s.mem_avail_kb) * 1024;
  const memPct = memT ? Math.round((memT - memA) / memT * 100) : 0;
  const [dT, dU] = String(s.disk_root || '').split(' ').map((n) => Number(n) * 1024);
  const diskPct = dT ? Math.round(dU / dT * 100) : 0;
  const swT = Number(s.swap_total_kb) * 1024, swF = Number(s.swap_free_kb) * 1024;
  const load1 = parseFloat(String(s.load || '').split(' ')[0]);
  const cpus = Number(s.cpus) || 1;
  const bar = (pct) => h('div', { class: 'bar', role: 'img', 'aria-label': pct + '% used' }, h('i', { class: pct >= 90 ? 'hot' : '', style: `width:${Math.min(100, pct)}%` }));
  const stat = (k, v, sub, extra) => h('div', { class: 'stat' }, h('div', { class: 'k' }, k), h('div', { class: 'v' }, v || '—'), sub ? h('div', { class: 's' }, sub) : null, extra || null);
  body.replaceChildren(
    h('div', { class: 'grid g4' },
      stat('Operating system', s.os, `${s.kernel || ''} · ${s.arch || ''}`),
      stat('Uptime', fmtDur(s.uptime_s), s.reboot_required === 'yes' ? 'Reboot required' : 'No reboot pending'),
      stat('Load (1, 5, 15 min)', s.load, `${cpus} CPUs · ${Math.round(load1 / cpus * 100) || 0}% of capacity`, bar(Math.round(load1 / cpus * 100) || 0)),
      stat('Memory', `${memPct}% used`, `${fmtBytes(memT - memA)} of ${fmtBytes(memT)}`, bar(memPct)),
      stat('Root disk', `${diskPct}% used`, `${fmtBytes(dU)} of ${fmtBytes(dT)}`, bar(diskPct)),
      stat('Swap', swT ? fmtBytes(swT - swF) + ' used' : 'none', swT ? 'of ' + fmtBytes(swT) : ''),
      stat('Failed services', s.failed_units, Number(s.failed_units) ? 'See Terminal → Failed services' : 'All units healthy'),
      stat('Agent runs as', s.user, s.hostname)),
    h('div', { class: 'card', style: 'margin-top:14px' }, h('h3', {}, 'Network'), h('div', { class: 'mono' }, s.ips || '—')),
    rec.host.notes ? h('div', { class: 'card' }, h('h3', {}, 'Notes'), h('div', { style: 'white-space:pre-wrap' }, rec.host.notes)) : null);
  overviewExtras(body, rec);
}

// ---------- files
function tabFiles(body, rec) {
  const name = rec.host.name;
  if (stateOf(rec) !== 'online') { body.replaceChildren(h('p', { class: 'muted' }, 'The host is not online.')); return; }
  if (!hasFiles(rec)) {
    body.replaceChildren(h('div', { class: 'card' }, h('h3', {}, 'File browsing needs agent 2.1.0 or newer'),
      h('p', {}, `${name} runs agent ${rec.status.version}, which has no file API.`),
      !rec.host.readOnly && outdated(rec) ? h('button', { class: 'primary', onclick: () => doUpgrade(name) }, 'Upgrade agent') : null));
    return;
  }
  const st = S.files[name] || (S.files[name] = { path: rec.status.defaultCwd || '/' });
  const ro = rec.host.readOnly;
  const pathIn = h('input', { type: 'text', class: 'mono', value: st.path, 'aria-label': 'Path' });
  const listBox = h('div');
  const go = (p) => { st.path = p; pathIn.value = p; refresh(); };
  pathIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(pathIn.value.trim() || '/'); });
  const crumbs = h('div', { class: 'crumbs' });
  const fileInput = h('input', { type: 'file', multiple: true, hidden: true, onchange: () => upload([...fileInput.files]) });
  const hiddenBox = h('input', { type: 'checkbox', checked: S.hidden, onchange: () => { S.hidden = hiddenBox.checked; refresh(); } });

  async function refresh() {
    listBox.replaceChildren(h('p', { class: 'muted' }, 'Loading…'));
    let r;
    try { r = await api('GET', `${hp(name)}/fs/list?path=${enc(st.path)}&hidden=${S.hidden}`); } catch (e) {
      listBox.replaceChildren(h('p', { class: 'err' }, e.message), h('button', { onclick: () => go(parentOf(st.path)) }, 'Go up'));
      return;
    }
    st.path = r.path; pathIn.value = r.path;
    crumbs.replaceChildren();
    const parts = r.path.split('/').filter(Boolean);
    crumbs.append(h('button', { onclick: () => go('/') }, '/'));
    parts.forEach((p, i) => {
      const target = '/' + parts.slice(0, i + 1).join('/');
      crumbs.append(h('button', { onclick: () => go(target) }, p), i < parts.length - 1 ? h('span', { class: 'muted' }, '/') : null);
    });
    const rows = r.entries.map((e) => {
      const isDir = e.type === 'dir' || e.target_type === 'dir';
      const full = joinPath(r.path, e.name);
      const open = () => (isDir ? go(full) : openFile(name, full, ro, refresh));
      return h('tr', { class: 'click', ondblclick: open },
        h('td', {}, h('span', { class: 'fname' }, h('span', { class: 'ficon' + (isDir ? ' dir' : ''), 'aria-hidden': 'true' }, isDir ? '■' : e.type === 'link' ? '↪' : '□'),
          h('button', { class: 'ghost', style: 'padding:0;text-align:left', onclick: open }, e.name),
          e.target ? h('span', { class: 'link-target' }, '→ ' + e.target) : null)),
        h('td', { class: 'num' }, isDir ? '' : fmtBytes(e.size)),
        h('td', { class: 'mono' }, e.mode || ''),
        h('td', { class: 'muted' }, e.owner ? `${e.owner}:${e.group}` : ''),
        h('td', { class: 'muted' }, fmtTime(e.mtime)),
        h('td', { class: 'act' },
          !isDir ? h('button', { class: 'small ghost', title: 'Download', onclick: (ev) => { ev.stopPropagation(); download(name, full); } }, 'Download') : null,
          !ro ? h('button', { class: 'small ghost', onclick: (ev) => { ev.stopPropagation(); rename(full); } }, 'Rename') : null,
          !ro ? h('button', { class: 'small ghost danger', onclick: (ev) => { ev.stopPropagation(); remove(full, isDir && e.type === 'dir'); } }, 'Delete') : null));
    });
    listBox.replaceChildren(
      r.truncated ? h('p', { class: 'hint' }, 'Showing the first 5000 entries.') : null,
      h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, ['Name', 'Size', 'Mode', 'Owner', 'Modified', ''].map((x) => h('th', {}, x)))),
        h('tbody', {}, r.path !== '/' ? h('tr', { class: 'click', onclick: () => go(parentOf(r.path)) },
          h('td', { colspan: 6 }, h('span', { class: 'fname' }, h('span', { class: 'ficon dir' }, '↑'), '..'))) : null,
        rows.length ? rows : h('tr', {}, h('td', { colspan: 6, class: 'empty' }, 'Empty directory'))))));
  }

  async function rename(full) {
    const to = await promptBox('Rename or move', 'New path', full);
    if (!to || to === full) return;
    try { await api('POST', hp(name) + '/fs/rename', { path: full, to }); toast('Renamed'); refresh(); } catch (e) { fail(e); }
  }
  async function remove(full, isDir) {
    const ok = await confirmBox('Delete', isDir ? `Delete the folder ${full} and everything in it?` : `Delete ${full}?`, 'Delete', isDir ? full.split('/').pop() : null);
    if (!ok) return;
    try { await api('POST', hp(name) + '/fs/delete', { path: full, recursive: isDir }); toast('Deleted'); refresh(); } catch (e) { fail(e); }
  }
  async function upload(files) {
    for (const file of files) {
      const target = joinPath(st.path, file.name);
      const send = (overwrite) => api('PUT', `${hp(name)}/fs/file?path=${enc(target)}&overwrite=${overwrite}`, undefined, file);
      try {
        toast(`Uploading ${file.name} (${fmtBytes(file.size)})…`);
        try { await send(false); } catch (e) {
          if (e.status !== 409) throw e;
          if (!await confirmBox('Replace file', `${target} already exists. Replace it? The old one is kept as a .bak copy.`, 'Replace')) continue;
          await send(true);
        }
        toast('Uploaded ' + file.name);
      } catch (e) { fail(e); }
    }
    fileInput.value = '';
    refresh();
  }

  const drop = h('div', { class: 'dropzone' }, 'Drop files here to upload them to this folder');
  if (!ro) {
    drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
    drop.addEventListener('dragleave', () => drop.classList.remove('over'));
    drop.addEventListener('drop', (e) => { e.preventDefault(); drop.classList.remove('over'); upload([...e.dataTransfer.files]); });
  }
  body.replaceChildren(
    h('div', { class: 'card' },
      h('div', { class: 'row', style: 'margin-bottom:10px' },
        h('button', { onclick: () => go(parentOf(st.path)), title: 'Up one folder' }, '↑ Up'),
        h('div', { class: 'grow' }, pathIn),
        h('button', { onclick: refresh }, 'Refresh'),
        h('label', { class: 'checks', style: 'margin:0' }, h('span', { class: 'row', style: 'gap:6px;color:var(--fg)' }, hiddenBox, 'Hidden files'))),
      h('div', { class: 'row', style: 'margin-bottom:10px' }, crumbs, h('span', { class: 'spacer', style: 'flex:1' }),
        !ro ? h('button', { onclick: async () => {
          const n = await promptBox('New folder', 'Folder name', '');
          if (!n) return;
          try { await api('POST', hp(name) + '/fs/mkdir', { path: joinPath(st.path, n) }); refresh(); } catch (e) { fail(e); }
        } }, 'New folder') : null,
        !ro ? h('button', { onclick: async () => {
          const n = await promptBox('New file', 'File name', '');
          if (!n) return;
          const p = joinPath(st.path, n);
          try { await api('PUT', `${hp(name)}/fs/file?path=${enc(p)}&overwrite=false`, undefined, ''); openFile(name, p, false, refresh); refresh(); } catch (e) { fail(e); }
        } }, 'New file') : null,
        !ro ? h('button', { onclick: () => fileInput.click() }, 'Upload') : null, fileInput),
      listBox),
    !ro ? drop : null);
  refresh();
}

function promptBox(title, label, value) {
  return new Promise((resolve) => {
    const input = h('input', { type: 'text', class: 'mono', value: value || '' });
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); closeDialog(); } };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') finish(input.value.trim()); });
    const d = openDialog(title, h('div', { class: 'field' }, h('label', {}, label), input),
      [h('button', { onclick: () => finish(null) }, 'Cancel'), h('button', { class: 'primary', onclick: () => finish(input.value.trim()) }, 'OK')]);
    d.addEventListener('close', () => finish(null), { once: true });
    setTimeout(() => { input.focus(); input.select(); }, 30);
  });
}

async function download(host, path) {
  try {
    const r = await fetch(`/ui/api${hp(host)}/fs/download?path=${enc(path)}`, { headers: { 'X-VM-Admin': '1' } });
    if (!r.ok) { let m = 'HTTP ' + r.status; try { m = (await r.json()).error || m; } catch { /* not json */ } throw new Error(m); }
    const url = URL.createObjectURL(await r.blob());
    const a = h('a', { href: url, download: path.split('/').pop() });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  } catch (e) { fail(e); }
}

async function openFile(host, path, ro, onSaved) {
  openDialog(path, h('p', { class: 'muted' }, 'Loading…'), null, true);
  let r;
  try { r = await api('GET', `${hp(host)}/fs/read?path=${enc(path)}`); } catch (e) { openDialog(path, h('p', { class: 'err' }, e.message), [h('button', { onclick: closeDialog }, 'Close')], true); return; }
  if (r.binary) {
    openDialog(path, h('p', {}, `Binary file, ${fmtBytes(r.size)}. Download it to open it locally.`),
      [h('button', { onclick: closeDialog }, 'Close'), h('button', { class: 'primary', onclick: () => download(host, path) }, 'Download')], true);
    return;
  }
  const editable = !ro && !r.truncated;
  const ta = h('textarea', { class: 'editor', spellcheck: 'false', readonly: !editable }, r.content);
  const backup = h('input', { type: 'checkbox', checked: true });
  const status = h('span', { class: 'hint', style: 'margin-right:auto' },
    `${fmtBytes(r.size)}` + (r.truncated ? ' · showing the first 1 MB, read-only' : '') + (ro ? ' · host is read-only' : ''));
  ta.addEventListener('keydown', (e) => {
    if (e.key === 'Tab' && editable) { e.preventDefault(); ta.setRangeText('    ', ta.selectionStart, ta.selectionEnd, 'end'); }
    if ((e.ctrlKey || e.metaKey) && e.key === 's' && editable) { e.preventDefault(); saveBtn.click(); }
  });
  const saveBtn = h('button', { class: 'primary', onclick: async () => {
    saveBtn.disabled = true;
    try {
      const w = await api('POST', hp(host) + '/fs/save', { path, content: ta.value, backup: backup.checked });
      toast('Saved' + (w.backup ? ' (backup ' + w.backup.split('/').pop() + ')' : ''));
      closeDialog(); if (onSaved) onSaved();
    } catch (e) { fail(e); } finally { saveBtn.disabled = false; }
  } }, 'Save');
  openDialog(path, ta, [status,
    editable ? h('label', { class: 'checks', style: 'margin:0' }, h('span', { class: 'row', style: 'gap:6px;color:var(--fg)' }, backup, 'Keep backup')) : null,
    h('button', { onclick: () => download(host, path) }, 'Download'),
    h('button', { onclick: closeDialog }, editable ? 'Cancel' : 'Close'),
    editable ? saveBtn : null], true);
  setTimeout(() => ta.focus(), 30);
}

// ---------- terminal
function tabTerminal(body, rec) {
  const name = rec.host.name;
  const ro = rec.host.readOnly;
  const t = S.term[name] || (S.term[name] = { lines: [], cwd: rec.status.defaultCwd || '', hist: load('hist.' + name, []), hi: -1 });
  const out = h('pre', { class: 'term', 'aria-live': 'polite' });
  const paint = () => {
    out.replaceChildren(...(t.lines.length ? t.lines : [{ c: 'dim', s: ro ? 'This host is read-only: commands are disabled.' : 'Commands run as the agent user through a login shell. Output appears when the command ends; use Run in background for long tasks.' }])
      .map((l) => h('span', { class: l.c || '' }, l.s + '\n')));
    out.scrollTop = out.scrollHeight;
  };
  const cmd = h('input', { type: 'text', class: 'mono', placeholder: ro ? 'read-only host' : 'command, e.g. systemctl status nginx', disabled: ro, autocomplete: 'off', 'aria-label': 'Command' });
  const cwd = h('input', { type: 'text', class: 'mono', value: t.cwd, placeholder: 'working directory', 'aria-label': 'Working directory' });
  const timeout = h('input', { type: 'number', min: 1, max: 3600, value: 120, style: 'width:90px', 'aria-label': 'Timeout seconds' });
  const runBtn = h('button', { class: 'primary', disabled: ro }, 'Run');
  const bgBtn = h('button', { disabled: ro, title: 'Start as a background job' }, 'Run in background');

  async function run(command, background) {
    command = (command || '').trim();
    if (!command) return;
    t.cwd = cwd.value.trim();
    t.hist = [command, ...t.hist.filter((x) => x !== command)].slice(0, 50); t.hi = -1;
    save('hist.' + name, t.hist);
    t.lines.push({ c: 'cmd', s: `${t.cwd || '~'} $ ${command}` + (background ? '   (background)' : '') });
    paint(); runBtn.disabled = bgBtn.disabled = true; cmd.value = '';
    try {
      const body = { command, cwd: t.cwd || null, timeoutSeconds: Number(timeout.value) || 120 };
      const r = await withConfirm((confirm) => api('POST', hp(name) + (background ? '/jobs' : '/run'), { ...body, confirm }), name);
      if (!r) { t.lines.push({ c: 'dim', s: 'cancelled' }); return; }
      if (background) { t.lines.push({ c: 'good', s: `started job ${r.job_id} (pid ${r.pid}); see the Jobs tab` }); return; }
      if (r.output) t.lines.push({ s: r.output.replace(/\n$/, '') });
      t.lines.push(r.timed_out ? { c: 'bad', s: 'timed out and was killed' }
        : { c: r.exit_code === 0 ? 'good' : 'bad', s: `exit ${r.exit_code} · ${r.duration_s}s` });
    } catch (e) { t.lines.push({ c: 'bad', s: e.message }); } finally {
      if (t.lines.length > 400) t.lines.splice(0, t.lines.length - 400);
      paint(); runBtn.disabled = bgBtn.disabled = ro; cmd.focus();
    }
  }
  runBtn.onclick = () => run(cmd.value, false);
  bgBtn.onclick = () => run(cmd.value, true);
  cmd.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') run(cmd.value, false);
    else if (e.key === 'ArrowUp' && t.hist.length) { e.preventDefault(); t.hi = Math.min(t.hi + 1, t.hist.length - 1); cmd.value = t.hist[t.hi]; }
    else if (e.key === 'ArrowDown') { e.preventDefault(); t.hi = Math.max(t.hi - 1, -1); cmd.value = t.hi < 0 ? '' : t.hist[t.hi]; }
  });
  body.replaceChildren(
    out,
    h('div', { class: 'row', style: 'margin-top:10px' }, h('div', { class: 'grow', style: 'flex:3' }, cmd), runBtn, bgBtn),
    h('div', { class: 'row', style: 'margin-top:8px' },
      h('span', { class: 'muted' }, 'cwd'), h('div', { class: 'grow' }, cwd),
      h('span', { class: 'muted' }, 'timeout (s)'), timeout,
      h('button', { class: 'ghost', onclick: () => { t.lines = []; paint(); } }, 'Clear')),
    h('div', { class: 'snips' }, (S.session.snippets || []).map((s) => h('button', { class: 'small', disabled: ro, title: s.command, onclick: () => run(s.command, false) }, s.name))));
  paint();
  if (!ro) setTimeout(() => cmd.focus(), 30);
}

// ---------- jobs
function tabJobs(body, rec) {
  const name = rec.host.name, ro = rec.host.readOnly;
  const list = h('div'), detail = h('div');
  async function refresh() {
    let jobs;
    try { jobs = await api('GET', hp(name) + '/jobs'); } catch (e) { list.replaceChildren(h('p', { class: 'err' }, e.message)); return; }
    list.replaceChildren(jobs.length ? h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, ['Job', 'Command', 'State', ''].map((x) => h('th', {}, x)))),
      h('tbody', {}, jobs.map((j) => h('tr', { class: 'click', onclick: () => show(j.job_id) },
        h('td', { class: 'mono' }, j.job_id),
        h('td', { class: 'mono', style: 'max-width:420px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap', title: j.command }, j.command),
        h('td', {}, j.running ? h('span', { class: 'state online' }, 'running') : h('span', { class: 'state ' + (j.exit_code === 0 ? 'online' : 'offline') }, 'exit ' + j.exit_code)),
        h('td', { class: 'act' }, j.running && !ro ? h('button', { class: 'small danger', onclick: (e) => { e.stopPropagation(); kill(j.job_id); } }, 'Stop') : null))))))
      : h('p', { class: 'empty' }, 'No background jobs. Start one from the Terminal tab with Run in background.'));
  }
  async function kill(id) {
    if (!await confirmBox('Stop job', `Send SIGTERM to job ${id} on ${name}?`, 'Stop')) return;
    try { await api('DELETE', `${hp(name)}/jobs/${enc(id)}`); toast('Stopped ' + id); refresh(); } catch (e) { fail(e); }
  }
  let timer = null;
  async function show(id) {
    if (timer) { clearInterval(timer); S.timers = S.timers.filter((x) => x !== timer); }
    const pre = h('pre', { class: 'term' });
    const head = h('div', { class: 'row', style: 'margin-bottom:8px' });
    detail.replaceChildren(h('div', { class: 'card' }, head, pre));
    const tick = async () => {
      try {
        const j = await api('GET', `${hp(name)}/jobs/${enc(id)}?tail=500`);
        head.replaceChildren(h('strong', { class: 'mono' }, id), h('span', { class: 'mono muted', style: 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis' }, j.command),
          j.running ? h('span', { class: 'state online' }, 'running, refreshing every 2s') : h('span', { class: 'state ' + (j.exit_code === 0 ? 'online' : 'offline') }, 'exit ' + j.exit_code),
          h('span', { class: 'muted' }, `${j.total_lines} lines`),
          j.running && !ro ? h('button', { class: 'small danger', onclick: () => kill(id) }, 'Stop') : null);
        const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 20;
        pre.textContent = j.tail || '(no output yet)';
        if (atBottom) pre.scrollTop = pre.scrollHeight;
        if (!j.running && timer) { clearInterval(timer); refresh(); }
      } catch (e) { pre.textContent = e.message; if (timer) clearInterval(timer); }
    };
    await tick();
    timer = setInterval(tick, 2000);
    S.timers.push(timer);
  }
  body.replaceChildren(h('div', { class: 'card' }, h('div', { class: 'row', style: 'margin-bottom:8px' }, h('h3', { style: 'margin:0;flex:1' }, 'Background jobs'), h('button', { onclick: refresh }, 'Refresh')), list), detail);
  refresh();
}

// ---------- agent log
async function tabLog(body, rec) {
  body.replaceChildren(h('p', { class: 'muted' }, 'Loading…'));
  try {
    const r = await api('GET', hp(rec.host.name) + '/agent-audit?lines=300');
    body.replaceChildren(h('p', { class: 'hint' }, 'Everything any token did on this VM, from ', h('span', { class: 'mono' }, r.file), '. Newest at the bottom.'),
      h('pre', { class: 'term', style: 'max-height:70vh' }, r.lines.join('\n')));
    const pre = body.querySelector('pre'); pre.scrollTop = pre.scrollHeight;
  } catch (e) { body.replaceChildren(h('p', { class: 'err' }, e.message)); }
}

// ------------------------------------------------------------------ broadcast
function renderBroadcast() {
  const main = $('main');
  const hosts = S.fleet.hosts;
  const boxes = {};
  const pick = h('div', { class: 'checks', style: 'flex-direction:column;gap:6px;max-height:260px;overflow:auto' }, hosts.map((r) => {
    boxes[r.host.name] = h('input', { type: 'checkbox', disabled: r.host.readOnly || stateOf(r) !== 'online' });
    return h('label', {}, boxes[r.host.name], h('span', { class: 'dot ' + stateOf(r) }), r.host.name,
      r.host.group ? h('span', { class: 'chip' }, r.host.group) : null, r.host.readOnly ? h('span', { class: 'chip ro' }, 'read-only') : null);
  }));
  const groups = [...new Set(hosts.map((r) => r.host.group).filter(Boolean))];
  const selectWhere = (pred) => hosts.forEach((r) => { const b = boxes[r.host.name]; if (!b.disabled) b.checked = pred(r); });
  const cmd = h('textarea', { rows: 3, placeholder: 'e.g. df -h /', 'aria-label': 'Command' });
  const timeout = h('input', { type: 'number', min: 1, max: 3600, value: 120, style: 'width:90px' });
  const results = h('div');
  const runBtn = h('button', { class: 'primary', onclick: async () => {
    const chosen = Object.keys(boxes).filter((n) => boxes[n].checked);
    const command = cmd.value.trim();
    if (!chosen.length || !command) { toast('Pick hosts and type a command', true); return; }
    if (chosen.length > 1 && !await confirmBox('Run on several hosts', `Run this on ${chosen.length} hosts: ${chosen.join(', ')}?`, 'Run')) return;
    runBtn.disabled = true;
    results.replaceChildren(h('p', { class: 'muted' }, `Running on ${chosen.length} hosts…`));
    try {
      const r = await withConfirm((confirm) => api('POST', '/broadcast', { hosts: chosen, command, timeoutSeconds: Number(timeout.value) || 120, confirm }),
        chosen.length === 1 ? chosen[0] : `${chosen.length} hosts`);
      if (!r) { results.replaceChildren(); return; }
      const okN = Object.values(r).filter((x) => x.exit_code === 0).length;
      results.replaceChildren(h('p', {}, h('strong', {}, `${okN} of ${chosen.length} succeeded`)),
        Object.entries(r).map(([n, x]) => h('details', { class: 'res', open: x.exit_code !== 0 || chosen.length <= 3 },
          h('summary', {}, h('span', { class: 'dot ' + (x.exit_code === 0 ? 'online' : 'offline') }), h('strong', {}, n),
            h('span', { class: 'muted' }, x.error ? x.error : x.timed_out ? 'timed out' : `exit ${x.exit_code} · ${x.duration_s}s`)),
          h('div', {}, h('pre', { class: 'out' }, x.output || x.error || '(no output)')))));
    } catch (e) { results.replaceChildren(h('p', { class: 'err' }, e.message)); } finally { runBtn.disabled = false; }
  } }, 'Run');
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Broadcast'), h('span', { class: 'meta' }, 'Run one command on many hosts in parallel')),
    h('div', { class: 'grid g2' },
      h('div', { class: 'card' }, h('h3', {}, 'Hosts'),
        h('div', { class: 'row', style: 'margin-bottom:10px' },
          h('button', { class: 'small', onclick: () => selectWhere(() => true) }, 'All online'),
          h('button', { class: 'small', onclick: () => selectWhere(() => false) }, 'None'),
          groups.map((g) => h('button', { class: 'small', onclick: () => selectWhere((r) => r.host.group === g) }, 'Group ' + g))),
        hosts.length ? pick : h('p', { class: 'muted' }, 'No hosts yet.'),
        h('p', { class: 'hint' }, 'Offline and read-only hosts cannot be selected.')),
      h('div', { class: 'card' }, h('h3', {}, 'Command'), cmd,
        h('div', { class: 'row', style: 'margin-top:10px' }, h('span', { class: 'muted' }, 'timeout per host (s)'), timeout, h('span', { style: 'flex:1' }), runBtn),
        h('div', { class: 'snips' }, (S.session.snippets || []).map((s) => h('button', { class: 'small', title: s.command, onclick: () => { cmd.value = s.command; } }, s.name))))),
    results);
}

// ------------------------------------------------------------------ audit
async function renderAudit() {
  const main = $('main');
  const filter = h('input', { type: 'search', placeholder: 'Filter, e.g. host=web-01 or RUN', value: load('auditFilter', ''), 'aria-label': 'Filter audit log' });
  const pre = h('pre', { class: 'term', style: 'max-height:72vh' });
  let lines = [];
  const paint = () => {
    const q = filter.value.toLowerCase();
    save('auditFilter', filter.value);
    pre.textContent = lines.filter((l) => !q || l.toLowerCase().includes(q)).join('\n') || '(nothing yet)';
    pre.scrollTop = pre.scrollHeight;
  };
  filter.addEventListener('input', paint);
  const refresh = async () => { try { lines = await api('GET', '/audit?lines=1000'); paint(); } catch (e) { fail(e); } };
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Audit log'), h('span', { class: 'meta' }, 'Every action the control centre took, from the UI or over MCP'),
      h('span', { class: 'spacer' }), h('button', { onclick: refresh }, 'Refresh')),
    h('div', { class: 'row', style: 'margin-bottom:10px' }, h('div', { class: 'grow' }, filter)),
    pre);
  refresh();
}

// ------------------------------------------------------------------ boot
async function boot() {
  document.querySelectorAll('#views button').forEach((b) => b.addEventListener('click', () => setView(b.dataset.view)));
  $('add-host').addEventListener('click', () => hostForm());
  $('filter').addEventListener('input', renderSide);
  $('menu').addEventListener('click', () => $('side').classList.toggle('open'));
  try {
    S.session = await api('GET', '/session');
    await loadFleet();
  } catch (e) {
    $('main').replaceChildren(h('div', { class: 'warnbox' }, 'Cannot reach the control centre API: ' + e.message));
    return;
  }
  setView('fleet');
  refreshAlertBadge();
  setInterval(async () => {
    refreshAlertBadge();
    try {
      await loadFleet();
      if (S.view === 'fleet' && !dlg().open) renderFleet();
    } catch { /* keep the last view */ }
  }, 15000);
}
boot();
