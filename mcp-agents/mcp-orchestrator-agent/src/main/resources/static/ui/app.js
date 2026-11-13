'use strict';
/* Agent Orchestrator UI. Plain DOM, no innerHTML with data: everything goes through h(). */

const S = { view: 'team', agents: [], info: null, poll: null, runId: null, runsTimer: null };
const FINISHED = ['succeeded', 'failed', 'cancelled', 'interrupted'];

// ------------------------------------------------------------------ helpers
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'value') el.value = v;
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : String(kid));
  }
  return el;
}
const $ = (id) => document.getElementById(id);
const enc = encodeURIComponent;

class ApiError extends Error {
  constructor(status, data) { super((data && data.error) || ('HTTP ' + status)); this.status = status; this.data = data || {}; }
}

async function api(method, path, body) {
  const opt = { method, headers: { 'X-Orchestrator': '1' } };
  if (body !== undefined) { opt.body = JSON.stringify(body); opt.headers['Content-Type'] = 'application/json'; }
  const r = await fetch('/ui/api' + path, opt);
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text.slice(0, 300) }; }
  if (!r.ok) throw new ApiError(r.status, data);
  return data;
}

function toast(msg, bad) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast show' + (bad ? ' bad' : '');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => { t.className = 'toast'; }, bad ? 6000 : 2500);
}
const fail = (e) => toast(e.message || String(e), true);

function ago(ts) {
  if (!ts) return 'never';
  const s = Math.round((Date.now() - new Date(ts).getTime()) / 1000);
  if (s < 5) return 'just now';
  if (s < 60) return s + 's ago';
  if (s < 3600) return Math.round(s / 60) + 'm ago';
  if (s < 86400) return Math.round(s / 3600) + 'h ago';
  return Math.round(s / 86400) + 'd ago';
}
function clock(ts) { return ts ? new Date(ts).toLocaleTimeString() : ''; }
function stamp(ts) { return ts ? new Date(ts).toLocaleString() : ''; }
function dur(a, b) {
  if (!a) return '';
  const s = Math.max(0, Math.round(((b ? new Date(b) : new Date()) - new Date(a)) / 1000));
  return s < 60 ? s + 's' : s < 3600 ? Math.floor(s / 60) + 'm ' + (s % 60) + 's' : Math.floor(s / 3600) + 'h ' + Math.floor((s % 3600) / 60) + 'm';
}
function csv(s) { return String(s || '').split(',').map((x) => x.trim()).filter(Boolean); }
function splitArgs(s) {
  const out = []; let cur = ''; let q = null; let any = false;
  for (const c of String(s || '')) {
    if (q) { if (c === q) q = null; else cur += c; continue; }
    if (c === '"' || c === "'") { q = c; any = true; continue; }
    if (/\s/.test(c)) { if (cur || any) { out.push(cur); cur = ''; any = false; } continue; }
    cur += c;
  }
  if (cur || any) out.push(cur);
  return out;
}
function joinArgs(a) { return (a || []).map((x) => (/[\s"']/.test(x) || x === '' ? '"' + x.replace(/"/g, '\\"') + '"' : x)).join(' '); }
function field(label, input, hint) {
  return h('div', { class: 'field' }, h('label', {}, label), input, hint ? h('p', { class: 'hint' }, hint) : null);
}
function agentById(id) { return S.agents.find((v) => v.agent.id === id); }
function stateClass(s) { return 'state s-' + s; }

// ------------------------------------------------------------------ faces
const NS = 'http://www.w3.org/2000/svg';
function sv(tag, attrs) { const el = document.createElementNS(NS, tag); for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, v); return el; }
function hashStr(s) { let x = 2166136261 >>> 0; for (const c of String(s || 'agent')) { x ^= c.charCodeAt(0); x = Math.imul(x, 16777619) >>> 0; } return x; }
function rng(seed) {
  let a = seed || 1;
  return () => { a |= 0; a = (a + 0x6D2B79F5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
const SKIN = ['#f6d7bd', '#eec29c', '#d9a172', '#b97d52', '#8d5a3a', '#5f3b26'];
const HAIR = ['#2b1d14', '#4a3121', '#7b4a24', '#c9a15b', '#1c1c1c', '#a3a3a3', '#b5523b', '#e4d3a0'];
const BG = ['#dbe7ff', '#ffe1d6', '#dff5e5', '#efe1ff', '#fff1c9', '#d6f2f5', '#ffd9e6', '#e5e8ec'];
const SHIRT = ['#2457d6', '#c0392b', '#1d7a3e', '#7b3fd1', '#d58a00', '#0f7c8a', '#c2185b', '#3d4a5c'];

/** A friendly generated face; deterministic for a seed. */
function face(seed, size, color) {
  const r = rng(hashStr(seed));
  const pick = (a) => a[Math.floor(r() * a.length)];
  const skin = pick(SKIN), hair = pick(HAIR), bg = color || pick(BG), shirt = pick(SHIRT);
  const style = Math.floor(r() * 5), glasses = r() < 0.3, beard = r() < 0.22, smile = r();
  const svg = sv('svg', { viewBox: '0 0 64 64', width: size, height: size, 'aria-hidden': 'true' });
  svg.append(sv('circle', { cx: 32, cy: 32, r: 32, fill: bg }));
  if (style === 1) svg.append(sv('path', { d: 'M15 30 C15 12 49 12 49 30 L50 52 C44 56 20 56 14 52 Z', fill: hair }));
  svg.append(sv('path', { d: 'M8 64 C10 49 21 44 32 44 C43 44 54 49 56 64 Z', fill: shirt }));
  svg.append(sv('rect', { x: 27, y: 36, width: 10, height: 10, rx: 3, fill: skin }));
  svg.append(sv('circle', { cx: 19.5, cy: 30, r: 3.2, fill: skin }));
  svg.append(sv('circle', { cx: 44.5, cy: 30, r: 3.2, fill: skin }));
  svg.append(sv('ellipse', { cx: 32, cy: 28.5, rx: 13, ry: 15, fill: skin }));
  if (beard) svg.append(sv('path', { d: 'M19.5 29 C20 45 44 45 44.5 29 C42 39 22 39 19.5 29 Z', fill: hair, opacity: 0.92 }));
  if (style === 0 || style === 1) svg.append(sv('path', { d: 'M18.5 27 C17 11 47 11 45.5 27 C42 19 25 18 18.5 27 Z', fill: hair }));
  if (style === 2) for (const [x, y] of [[20, 20], [25, 15], [32, 13], [39, 15], [44, 20], [22, 25], [42, 25]]) svg.append(sv('circle', { cx: x, cy: y, r: 5.2, fill: hair }));
  if (style === 3) { svg.append(sv('path', { d: 'M18.5 26 C17 12 47 12 45.5 26 C40 20 24 20 18.5 26 Z', fill: hair })); svg.append(sv('circle', { cx: 32, cy: 10.5, r: 6, fill: hair })); }
  if (style === 4) svg.append(sv('path', { d: 'M19 24 C20 18 24 15 27 15 M45 24 C44 18 40 15 37 15', stroke: hair, 'stroke-width': 2.5, fill: 'none', 'stroke-linecap': 'round' }));
  svg.append(sv('circle', { cx: 26.5, cy: 29, r: 1.7, fill: '#1e1e1e' }));
  svg.append(sv('circle', { cx: 37.5, cy: 29, r: 1.7, fill: '#1e1e1e' }));
  svg.append(sv('path', { d: 'M23.5 24.5 Q26.5 23 29 24.3 M35 24.3 Q37.5 23 40.5 24.5', stroke: hair === '#e4d3a0' ? '#9c8450' : hair, 'stroke-width': 1.4, fill: 'none', 'stroke-linecap': 'round' }));
  const m = smile < 0.6 ? 'M27.5 35.5 Q32 39.5 36.5 35.5' : smile < 0.85 ? 'M28 36 Q32 38 36 36' : 'M28.5 36.5 L35.5 36.5';
  svg.append(sv('path', { d: m, stroke: '#7a3b2e', 'stroke-width': 1.6, fill: 'none', 'stroke-linecap': 'round' }));
  svg.append(sv('circle', { cx: 23.5, cy: 33.5, r: 2.2, fill: '#ff8a80', opacity: 0.18 }));
  svg.append(sv('circle', { cx: 40.5, cy: 33.5, r: 2.2, fill: '#ff8a80', opacity: 0.18 }));
  if (glasses) {
    svg.append(sv('circle', { cx: 26.5, cy: 29, r: 4.3, stroke: '#2c2c2c', 'stroke-width': 1.3, fill: 'none' }));
    svg.append(sv('circle', { cx: 37.5, cy: 29, r: 4.3, stroke: '#2c2c2c', 'stroke-width': 1.3, fill: 'none' }));
    svg.append(sv('path', { d: 'M30.8 29 L33.2 29', stroke: '#2c2c2c', 'stroke-width': 1.3 }));
  }
  return svg;
}

function presence(v) {
  if (!v.agent.enabled) return ['disabled', 'Disabled'];
  if (v.state.busy > 0) return ['busy', 'Working'];
  if (v.state.status === 'online') return ['online', 'Available'];
  if (v.state.status === 'offline') return ['offline', 'Offline'];
  return ['unknown', 'Checking…'];
}

function avatar(v, size) {
  const a = v.agent;
  const [p, label] = presence(v);
  return h('div', { class: 'avatar', title: label, style: null }, face(a.avatar || a.id, size, a.color || null),
    h('span', { class: 'pres ' + p }));
}

/** The orchestrator's own face in timelines. */
function systemIcon(type) {
  const map = { start: '▶', plan: '🧭', done: '✓', error: '✕', approval: '✋', 'approval-result': '✋', say: '💬', answer: '📝', step: '⛓' };
  return h('div', { class: 'sys', 'aria-hidden': 'true' }, map[type] || '•');
}

// ------------------------------------------------------------------ systems helpers
const KIND_ICON = { kubernetes: '☸', jenkins: '🛠', dockerhub: '🐳', vm: '🖥' };
const kindIcon = (k) => KIND_ICON[k] || '◆';
function sysStatusClass(st) { return st === 'ok' || st === 'online' ? 'ok' : st === 'error' || st === 'offline' || st === 'unauthorized' ? 'error' : ''; }
function sysChip(s) {
  return h('span', { class: 'sys-chip', title: `${s.kind} ${s.name}${s.endpoint ? ' · ' + s.endpoint : ''} · ${s.status}${s.statusDetail ? ' · ' + s.statusDetail : ''}` },
    kindIcon(s.kind), ' ', s.name, h('i', { class: sysStatusClass(s.status) }));
}
/** A task prefilled with the system it is about, so the agent knows which cluster/server to use. */
function systemTaskPrefix(s) {
  return `On ${s.kind} "${s.name}"${s.endpoint ? ' (' + s.endpoint + ')' : ''}: `;
}

/** Runs a quick action (one tool, no LLM) and shows the output in a dialog. */
async function quickAction(agentId, s, act) {
  const out = h('pre', { class: 'out' }, 'Running ' + act.tool + '…');
  const v = agentById(agentId);
  openDialog(`${act.label} · ${s.name}`, h('div', {}, h('p', { class: 'hint mono' }, `${agentId}.${act.tool} ${JSON.stringify(act.args)}`), out),
    [h('button', { onclick: () => $('dlg').close() }, 'Close')], true, v ? avatar(v, 32) : null);
  try {
    const r = await api('POST', `/agents/${enc(agentId)}/tools/${enc(act.tool)}`, { args: act.args });
    const done = await waitRun(r.runId);
    let txt = done.result || done.error || '(no output)';
    try { txt = JSON.stringify(JSON.parse(txt), null, 2); } catch { /* not JSON */ }
    out.textContent = txt;
  } catch (e) { out.textContent = e.message; }
}

// ------------------------------------------------------------------ navigation
function setNav(view) {
  for (const b of document.querySelectorAll('#views button')) b.classList.toggle('on', b.dataset.view === view);
}
function go(view) {
  clearTimeout(S.poll);
  clearInterval(S.runsTimer);
  S.view = view;
  S.runId = null;
  setNav(view);
  const main = $('main');
  main.replaceChildren();
  ({ team: renderTeam, systems: renderSystems, mission: renderMission, chains: renderChains, runs: renderRuns, registry: renderRegistry, settings: renderSettings }[view] || renderTeam)();
  main.focus({ preventScroll: true });
}

async function loadAgents() {
  S.agents = await api('GET', '/agents');
  return S.agents;
}

async function refreshBadges() {
  try {
    S.info = await api('GET', '/info');
    const pc = $('pending-count');
    pc.hidden = !S.info.pending;
    pc.textContent = S.info.pending || '';
    const runs = await api('GET', '/runs?limit=50');
    const waiting = runs.filter((r) => r.status === 'waiting').length;
    const wc = $('wait-count');
    wc.hidden = !waiting;
    wc.textContent = waiting || '';
    wc.title = waiting ? waiting + ' run(s) waiting for your approval' : '';
  } catch { /* offline; next tick */ }
}

// ------------------------------------------------------------------ team
async function renderTeam() {
  const main = $('main');
  try { await loadAgents(); } catch (e) { fail(e); }
  if (S.view !== 'team') return;
  const online = S.agents.filter((v) => v.state.status === 'online').length;
  const busy = S.agents.filter((v) => v.state.busy > 0).length;
  const head = h('div', { class: 'page-head' },
    h('h2', {}, 'Team'),
    h('span', { class: 'meta' }, `${S.agents.length} agents · ${online} available · ${busy} working`),
    h('span', { class: 'spacer' }),
    h('button', { onclick: async (e) => { e.target.disabled = true; try { await api('POST', '/registry/scan'); await renderTeamGrid(); toast('Refreshed'); } catch (x) { fail(x); } e.target.disabled = false; } }, 'Refresh all'),
    h('button', { class: 'primary', onclick: () => agentDialog(null) }, 'Add agent'));
  const grid = h('div', { class: 'team', id: 'team-grid' });
  main.replaceChildren(head, grid);
  fillTeamGrid(grid);
}

async function renderTeamGrid() {
  await loadAgents();
  const grid = $('team-grid');
  if (grid) fillTeamGrid(grid);
}

function fillTeamGrid(grid) {
  const tiles = S.agents.map(tile);
  tiles.push(h('div', { class: 'tile add', tabindex: 0, role: 'button', onclick: () => agentDialog(null), onkeydown: (e) => { if (e.key === 'Enter') agentDialog(null); } }, '+ Add an agent'));
  grid.replaceChildren(...tiles);
}

function tile(v) {
  const a = v.agent, st = v.state;
  const [p, label] = presence(v);
  let doing;
  if (p === 'busy') doing = h('div', { class: 'doing busy', title: st.currentTask || '' }, '⚙ ', st.currentTask || 'Working');
  else if (p === 'offline') doing = h('div', { class: 'doing bad', title: st.error || '' }, 'Offline: ', st.error || 'not reachable');
  else if (p === 'disabled') doing = h('div', { class: 'doing' }, 'Disabled; gets no tasks');
  else doing = h('div', { class: 'doing' }, label + (st.lastActive ? ' · last active ' + ago(st.lastActive) : ''));
  const open = () => agentDialog(v);
  return h('div', { class: 'tile ' + p, tabindex: 0, role: 'button', 'aria-label': (a.name || a.id) + ', ' + label, onclick: open, onkeydown: (e) => { if (e.key === 'Enter') open(); } },
    h('div', { class: 'who' }, avatar(v, 60),
      h('div', {},
        h('div', { class: 'name' }, a.name || a.id),
        h('div', { class: 'role' }, a.role || (st.server ? st.server.name : 'MCP agent')))),
    h('div', { class: 'bio' }, a.bio || (st.server && st.server.instructions) || 'No description yet.'),
    doing,
    h('div', {}, (a.tags || []).map((t) => h('span', { class: 'chip' }, t)),
      st.toolsChanged ? h('span', { class: 'chip warn', title: st.toolsChanged }, 'tools changed') : null,
      v.dropin ? h('span', { class: 'chip', title: 'Defined in a file in agents.d/' }, 'drop-in') : null),
    (v.systems || []).length ? h('div', {}, v.systems.slice(0, 6).map(sysChip),
      v.systems.length > 6 ? h('span', { class: 'hint' }, '+' + (v.systems.length - 6)) : null) : null,
    h('div', { class: 'stats' },
      h('span', {}, h('b', {}, String((st.tools || []).length)), ' tools'),
      h('span', {}, h('b', {}, String(st.tasks)), ' tasks'),
      h('span', {}, h('b', {}, String(st.calls)), ' calls'),
      h('span', { title: 'LLM model' }, v.model)),
    h('div', { class: 'acts' },
      h('button', { class: 'primary small', disabled: p === 'offline' || p === 'disabled', onclick: (e) => { e.stopPropagation(); taskDialog(v); } }, 'Give a task'),
      h('button', { class: 'small', onclick: (e) => { e.stopPropagation(); agentDialog(v, 'tools'); } }, 'Tools'),
      h('button', { class: 'small', onclick: (e) => { e.stopPropagation(); agentDialog(v, 'instructions'); } }, 'Instructions')));
}

// ------------------------------------------------------------------ dialogs
function openDialog(title, body, foot, wide, head) {
  const d = $('dlg');
  d.className = wide ? 'wide' : '';
  d.replaceChildren(
    h('div', { class: 'dlg-head' }, head || null, h('h3', {}, title), h('button', { class: 'ghost', 'aria-label': 'Close', onclick: () => d.close() }, '✕')),
    h('div', { class: 'dlg-body' }, body),
    foot ? h('div', { class: 'dlg-foot' }, foot) : null);
  if (!d.open) d.showModal();
  return d;
}

function taskDialog(v, prefill) {
  const a = v.agent;
  const ta = h('textarea', { rows: 6, placeholder: 'e.g. Check why the payments deployment in namespace shop keeps restarting and tell me the root cause.' }, prefill || '');
  const go1 = h('button', { class: 'primary', onclick: async () => {
    if (!ta.value.trim()) { toast('Write a task first', true); return; }
    go1.disabled = true;
    try { const r = await api('POST', `/agents/${enc(a.id)}/delegate`, { task: ta.value.trim() }); $('dlg').close(); showRun(r.runId); }
    catch (e) { fail(e); go1.disabled = false; }
  } }, 'Delegate');
  openDialog('Give ' + (a.name || a.id) + ' a task', h('div', {},
    field('Task', ta, `${a.name || a.id} works on it with its LLM (${v.model}) and its ${(v.state.tools || []).length} tools, following its standing instructions.`)),
    [h('button', { onclick: () => $('dlg').close() }, 'Cancel'), go1], false, avatar(v, 36));
  ta.focus();
  ta.setSelectionRange(ta.value.length, ta.value.length);
}

/** Add (v == null) or view/edit an agent. Tabs share one form object; Save writes everything. */
function agentDialog(v, tab) {
  const isNew = !v;
  const a = v ? JSON.parse(JSON.stringify(v.agent)) : { id: '', name: '', role: '', bio: '', avatar: '', color: '', tags: [], transport: 'http', url: '', tokenRef: '', command: [], env: {}, workdir: '', instructions: '', llmProvider: '', model: '', effort: '', confirmTools: [], disabledTools: [], enabled: true };
  const tok = { value: '' };
  let current = tab || (isNew ? 'profile' : 'profile');
  const tabs = isNew ? [['profile', 'Profile'], ['connection', 'Connection'], ['instructions', 'Instructions']]
    : [['profile', 'Profile'], ['instructions', 'Instructions'], ['systems', 'Systems (' + (v.systems || []).length + ')'], ['tools', 'Tools (' + (v.state.tools || []).length + ')'], ['connection', 'Connection'], ['activity', 'Activity']];
  const body = h('div');
  const headFace = h('div');
  const redrawFace = () => headFace.replaceChildren(avatar(v || { agent: a, state: { status: 'unknown', busy: 0 } }, 40));
  redrawFace();

  const bind = (key, attrs) => h('input', Object.assign({ type: 'text', value: a[key] || '', oninput: (e) => { a[key] = e.target.value; if (key === 'avatar' || key === 'color' || key === 'id') redrawFace(); } }, attrs || {}));
  const bindList = (key, attrs) => h('input', Object.assign({ type: 'text', value: (a[key] || []).join(', '), oninput: (e) => { a[key] = csv(e.target.value); } }, attrs || {}));
  const area = (key, rows, attrs) => h('textarea', Object.assign({ rows, oninput: (e) => { a[key] = e.target.value; } }, attrs || {}), a[key] || '');

  function profileTab() {
    return h('div', {},
      h('div', { class: 'grid g2' },
        field('Id', bind('id', { disabled: !isNew, placeholder: 'kube', pattern: '[a-z0-9-]+' }), isNew ? 'Lowercase letters, digits, dashes. Used in tool names and URLs.' : null),
        field('Name', bind('name', { placeholder: 'Kira' }), 'What the team calls this agent.'),
        field('Role', bind('role', { placeholder: 'Kubernetes engineer' })),
        field('Tags', bindList('tags', { placeholder: 'k8s, prod' }), 'Comma separated.')),
      field('Bio', area('bio', 2, { placeholder: 'What this agent is good at. The orchestrator reads this when deciding who gets a task.' })),
      h('div', { class: 'grid g2' },
        field('Face', h('div', { class: 'row' }, h('div', { class: 'grow' }, bind('avatar', { placeholder: 'any text' })),
          h('button', { onclick: () => { a.avatar = Math.random().toString(36).slice(2, 8); render(); redrawFace(); } }, 'New face')), 'The face is generated from this text.'),
        field('Background colour', bind('color', { placeholder: '#dbe7ff (optional)' }))),
      h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: a.enabled, onchange: (e) => { a.enabled = e.target.checked; } }), 'Enabled (disabled agents get no tasks and are not connected)'));
  }

  function instructionsTab() {
    return h('div', {},
      field('Standing instructions', area('instructions', 10, { placeholder: 'Rules this agent always follows, e.g.\n- Only touch namespaces shop and staging.\n- Always dry-run an apply first and show the diff.\n- Report pod names and restart counts.' }),
        'Added to the agent\'s system prompt on every task, whether it comes from you, a chain or the orchestrator.'),
      h('div', { class: 'grid g2' },
        field('LLM provider', providerSelect(a), 'Blank: the default provider in Settings.'),
        field('Model override', bind('model', { placeholder: 'e.g. claude-opus-5-5' })),
        field('Effort', h('select', { onchange: (e) => { a.effort = e.target.value; } },
          ['', 'low', 'medium', 'high', 'xhigh', 'max'].map((x) => h('option', { value: x, selected: (a.effort || '') === x }, x || '(provider default)')))),
        h('div')),
      h('div', { class: 'grid g2' },
        field('Ask me before these tools run', bindList('confirmTools', { placeholder: 'k8s_delete, k8s_drain, *_rollback' }),
          'Glob patterns. When the LLM calls a matching tool, the run pauses until you approve it.'),
        field('Hide these tools from the LLM', bindList('disabledTools', { placeholder: 'jenkins_sync_credential' }),
          'Glob patterns. You can still run them yourself from the Tools tab.')));
  }

  function connectionTab() {
    const out = h('div', { class: 'hint' });
    const isHttp = a.transport !== 'stdio';
    const sec = isHttp
      ? h('div', {},
        field('MCP URL', bind('url', { placeholder: 'http://127.0.0.1:8766/mcp' }), 'Streamable HTTP endpoint of the agent.'),
        h('div', { class: 'grid g2' },
          field('Bearer token', h('input', { type: 'password', autocomplete: 'off', placeholder: v ? 'leave empty to keep the current one' : 'paste the agent\'s token', oninput: (e) => { tok.value = e.target.value; } }),
            'Stored in ~/.orchestrator/tokens.yml (mode 600) as store:agent.<id>.'),
          field('…or token reference', bind('tokenRef', { placeholder: 'env:KUBE_AGENT_TOKEN or path:~/.kube-agent/http.token' }))))
      : h('div', {},
        field('Command', h('input', { type: 'text', value: joinArgs(a.command), class: 'mono', placeholder: 'java -jar /opt/agents/mcp-kube-agent.jar', oninput: (e) => { a.command = splitArgs(e.target.value); } }),
          'Started as a child process of the orchestrator; MCP over stdin/stdout.'),
        field('Environment', h('textarea', { rows: 3, class: 'code', placeholder: 'KUBE_AGENT_CONFIG=/etc/kube-agent/config.yml\nDEVOPS_TOKEN=env:DEVOPS_TOKEN', oninput: (e) => {
          const env = {};
          for (const line of e.target.value.split('\n')) { const i = line.indexOf('='); if (i > 0) env[line.slice(0, i).trim()] = line.slice(i + 1); }
          a.env = env;
        } }, Object.entries(a.env || {}).map(([k, x]) => k + '=' + x).join('\n')), 'KEY=value per line; values starting with env:, path: or store: are resolved.'),
        field('Working directory', bind('workdir', { placeholder: '(optional)' })));
    return h('div', {},
      field('Transport', h('div', { class: 'checks' },
        h('label', {}, h('input', { type: 'radio', name: 'tr', checked: isHttp, onchange: () => { a.transport = 'http'; render(); } }), 'HTTP (agent runs on its own)'),
        h('label', {}, h('input', { type: 'radio', name: 'tr', checked: !isHttp, onchange: () => { a.transport = 'stdio'; render(); } }), 'stdio (orchestrator starts it)'))),
      sec,
      h('div', { class: 'row' }, h('button', { onclick: async (e) => {
        e.target.disabled = true; out.replaceChildren('Connecting…');
        try {
          const probe = Object.assign({}, a, { id: a.id || 'probe' });
          const r = await api('POST', '/agents/probe', { agent: probe, token: tok.value || null });
          out.replaceChildren(h('span', { class: 'okc' }, `✓ ${r.server.name || 'server'} ${r.server.version || ''}, ${r.tools.length} tools: `), r.tools.map((t) => t.name).join(', '));
          if (!a.bio && r.server.instructions) a.bio = r.server.instructions.split('. ')[0].slice(0, 200);
        } catch (x) { out.replaceChildren(h('span', { class: 'err' }, x.message)); }
        e.target.disabled = false;
      } }, 'Test connection')), out,
      v ? h('p', { class: 'hint' }, 'Status: ', h('span', { class: stateClass(v.state.status) }, v.state.status),
        v.state.error ? ' · ' + v.state.error : '', ' · checked ', ago(v.state.lastCheck),
        v.state.server ? ` · ${v.state.server.name} ${v.state.server.version} (MCP ${v.state.server.protocolVersion})` : '',
        v.dropin ? ' · defined in agents.d/ (saving here moves it to agents.yml)' : '') : null);
  }

  function toolsTab() {
    const tools = (v.state.tools || []);
    if (!tools.length) return h('p', { class: 'empty' }, v.state.status === 'online' ? 'This agent has no tools.' : 'The agent is not connected; tools appear once it is online.');
    const filter = h('input', { type: 'search', placeholder: 'Filter ' + tools.length + ' tools' });
    const list = h('div');
    const draw = () => {
      const q = filter.value.toLowerCase();
      list.replaceChildren(...tools.filter((t) => !q || t.name.toLowerCase().includes(q) || (t.description || '').toLowerCase().includes(q)).map((t) => toolPanel(v, t)));
    };
    filter.addEventListener('input', draw);
    draw();
    return h('div', {}, h('div', { class: 'field' }, filter), list,
      v.state.toolsChanged ? h('p', { class: 'hint' }, 'Last catalogue change: ' + v.state.toolsChanged) : null);
  }

  function systemsTab() {
    const list = v.systems || [];
    const table = list.length ? h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'System'), h('th', {}, 'Endpoint'), h('th', {}, 'Status'), h('th', {}, ''))),
      h('tbody', {}, list.map((s) => h('tr', {},
        h('td', {}, kindIcon(s.kind), ' ', h('strong', {}, s.name), ' ', h('span', { class: 'hint' }, s.kind),
          s.readOnly ? h('span', { class: 'chip warn' }, 'read-only') : null),
        h('td', { class: 'mono' }, s.endpoint || '—'),
        h('td', {}, h('span', { class: 'sys-chip' }, h('i', { class: sysStatusClass(s.status) }), s.status),
          s.statusDetail ? h('div', { class: 'hint' }, s.statusDetail) : null,
          s.checkedAt ? h('div', { class: 'hint' }, 'checked ' + ago(s.checkedAt)) : null),
        h('td', {}, h('div', { class: 'row' },
          (s.actions || []).map((act) => h('button', { class: 'small', onclick: () => quickAction(a.id, s, act) }, act.label)),
          h('button', { class: 'small primary', onclick: () => taskDialog(v, systemTaskPrefix(s)) }, 'Ask'))))))))
      : h('p', { class: 'empty' }, v.state.status === 'online' ? 'No systems found. Configure discovery below if this agent has a tool that lists them.' : 'The agent is offline.');

    const auto = v.probesAuto && a.systems == null;
    const cfg = h('textarea', { class: 'code', rows: 12, disabled: auto,
      oninput: (e) => { try { a.systems = JSON.parse(e.target.value || '[]'); err.textContent = ''; } catch (x) { err.textContent = 'Not valid JSON yet: ' + x.message; } } },
    JSON.stringify(a.systems != null ? a.systems : (v.probes || []), null, 2));
    const err = h('p', { class: 'err' });
    return h('div', {},
      h('div', { class: 'row' }, h('span', { class: 'grow hint' }, v.systemsError ? h('span', { class: 'err' }, v.systemsError) : `Read from ${a.name || a.id}'s own tools every minute; health checked every few minutes.`),
        h('button', { onclick: async (e) => {
          e.target.disabled = true;
          try { await api('POST', '/systems/check?agent=' + enc(a.id)); v = await api('GET', '/agents/' + enc(a.id)); render(); toast('Systems checked'); } catch (x) { fail(x); }
          e.target.disabled = false;
        } }, 'Check now')),
      table,
      h('h3', {}, 'How systems are discovered'),
      h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: auto, onchange: (e) => {
        if (e.target.checked) { a.systems = null; cfg.disabled = true; cfg.value = JSON.stringify(v.probes || [], null, 2); }
        else { a.systems = JSON.parse(cfg.value || '[]'); cfg.disabled = false; }
      } }), 'Automatic: built-in presets for the tools this agent has (k8s_clusters, devops_list_resources, fleet_hosts)'),
      field('Probes (JSON)', cfg, 'kind, listTool, listPath, nameField, stateField, healthTool + healthArg, actions [{label, tool, args with {{system}}}]. Save to apply.'),
      err);
  }

  function activityTab() {
    const box = h('div', {}, 'Loading…');
    api('GET', '/runs?limit=300').then((runs) => {
      const mine = runs.filter((r) => r.agent === a.id).slice(0, 30);
      box.replaceChildren(mine.length ? runsTable(mine, true) : h('p', { class: 'empty' }, 'No direct tasks or tool calls yet. (Work given through the orchestrator shows on the mission\'s run.)'));
    }).catch(fail);
    const st = v.state;
    return h('div', {},
      h('p', {}, `${st.tasks} tasks, ${st.calls} tool calls, ${st.failures} failed calls since the orchestrator started. Last active ${ago(st.lastActive)}.`),
      box);
  }

  function render() {
    const nav = h('div', { class: 'tabs' }, tabs.map(([k, label]) => h('button', { class: k === current ? 'on' : '', onclick: () => { current = k; render(); } }, label)));
    const view = { profile: profileTab, instructions: instructionsTab, systems: systemsTab, connection: connectionTab, tools: toolsTab, activity: activityTab }[current]();
    body.replaceChildren(nav, view);
  }
  render();

  const save = h('button', { class: 'primary', onclick: async () => {
    save.disabled = true;
    try {
      const r = isNew ? await api('POST', '/agents', { agent: a, token: tok.value || null })
        : await api('PUT', '/agents/' + enc(a.id), { agent: a, token: tok.value || null });
      toast((r.agent.name || r.agent.id) + ' saved');
      $('dlg').close();
      if (S.view === 'team') renderTeamGrid(); else loadAgents();
    } catch (e) { fail(e); }
    save.disabled = false;
  } }, isNew ? 'Add to team' : 'Save');
  const foot = [];
  if (!isNew && !v.dropin) {
    foot.push(h('button', { class: 'danger', onclick: async () => {
      if (!confirm('Remove ' + (a.name || a.id) + ' from the team?')) return;
      try { await api('DELETE', '/agents/' + enc(a.id)); $('dlg').close(); toast('Removed'); renderTeamGrid(); } catch (e) { fail(e); }
    } }, 'Remove'));
  }
  if (!isNew) {
    foot.push(h('button', { onclick: async () => {
      try { const r = await api('POST', '/agents/' + enc(a.id) + '/refresh'); v = r; toast(r.state.status === 'online' ? 'Online, ' + r.state.tools.length + ' tools' : 'Offline: ' + (r.state.error || '')); render(); renderTeamGrid(); } catch (e) { fail(e); }
    } }, 'Reconnect'));
    foot.push(h('span', { class: 'spacer' }));
    foot.push(h('button', { disabled: presence(v)[0] === 'offline', onclick: () => taskDialog(v) }, 'Give a task'));
  }
  foot.push(save);
  openDialog(isNew ? 'Add an agent' : (a.name || a.id) + (a.role ? ' · ' + a.role : ''), body, foot, true, headFace);
}

function providerSelect(obj, key) {
  key = key || 'llmProvider';
  const sel = h('select', { onchange: (e) => { obj[key] = e.target.value; } }, h('option', { value: '' }, '(default)'));
  api('GET', '/settings').then((r) => {
    for (const name of Object.keys(r.settings.providers || {})) sel.append(h('option', { value: name, selected: obj[key] === name }, name + ' · ' + r.settings.providers[name].model));
  }).catch(() => {});
  return sel;
}

// ------------------------------------------------------------------ tools
function schemaForm(schema) {
  const props = (schema && schema.properties) || {};
  const req = new Set((schema && schema.required) || []);
  const inputs = {};
  const wrap = h('div', { class: 'grid g2' });
  for (const [k, p] of Object.entries(props)) {
    const t = Array.isArray(p.type) ? p.type.find((x) => x !== 'null') : p.type;
    let inp;
    if (p.enum) inp = h('select', {}, h('option', { value: '' }, '—'), p.enum.map((o) => h('option', { value: String(o) }, String(o))));
    else if (t === 'boolean') inp = h('select', {}, h('option', { value: '' }, '—'), h('option', { value: 'true' }, 'true'), h('option', { value: 'false' }, 'false'));
    else if (t === 'object' || t === 'array') inp = h('textarea', { class: 'code', rows: 3, placeholder: t === 'array' ? '[ ]' : '{ }' });
    else inp = h('input', { type: t === 'integer' || t === 'number' ? 'number' : 'text', placeholder: p.default != null ? String(p.default) : '' });
    inputs[k] = { inp, t };
    wrap.append(field(k + (req.has(k) ? ' *' : ''), inp, p.description || null));
  }
  return {
    el: wrap,
    empty: Object.keys(props).length === 0,
    value() {
      const out = {};
      for (const [k, { inp, t }] of Object.entries(inputs)) {
        const x = inp.value.trim();
        if (x === '') continue;
        if (t === 'boolean') out[k] = x === 'true';
        else if (t === 'integer' || t === 'number') out[k] = Number(x);
        else if (t === 'object' || t === 'array') { try { out[k] = JSON.parse(x); } catch { throw new Error(k + ': not valid JSON'); } }
        else out[k] = x;
      }
      for (const r of req) if (!(r in out)) throw new Error(r + ' is required');
      return out;
    },
  };
}

function skeleton(schema) {
  const out = {};
  for (const [k, p] of Object.entries((schema && schema.properties) || {})) {
    const t = Array.isArray(p.type) ? p.type.find((x) => x !== 'null') : p.type;
    out[k] = p.default != null ? p.default : p.enum ? p.enum[0] : t === 'boolean' ? false : t === 'integer' || t === 'number' ? 0 : t === 'array' ? [] : t === 'object' ? {} : '';
  }
  return JSON.stringify(out, null, 2);
}

function toolPanel(v, t) {
  const form = schemaForm(t.inputSchema);
  const out = h('div');
  const ann = t.annotations || {};
  const run = h('button', { class: 'primary small', onclick: async () => {
    let args;
    try { args = form.value(); } catch (e) { fail(e); return; }
    run.disabled = true;
    out.replaceChildren(h('p', { class: 'hint' }, 'Running…'));
    try {
      const r = await api('POST', `/agents/${enc(v.agent.id)}/tools/${enc(t.name)}`, { args });
      const done = await waitRun(r.runId);
      out.replaceChildren(
        h('p', { class: 'hint' }, h('span', { class: stateClass(done.status) }, done.status), ' · ', dur(done.createdAt, done.finishedAt), ' · ',
          h('a', { href: '#', onclick: (e) => { e.preventDefault(); $('dlg').close(); showRun(r.runId); } }, 'open run')),
        h('pre', { class: 'out' }, done.result || done.error || '(no output)'));
    } catch (e) { out.replaceChildren(h('p', { class: 'err' }, e.message)); }
    run.disabled = false;
  } }, 'Run tool');
  return h('details', { class: 'tool' },
    h('summary', {}, h('span', { class: 'tn' }, t.name),
      ann.readOnlyHint ? h('span', { class: 'chip' }, 'read-only') : null,
      ann.destructiveHint ? h('span', { class: 'chip warn' }, 'destructive') : null,
      AgentGlob(v.agent.confirmTools, t.name) ? h('span', { class: 'chip warn' }, 'asks first') : null,
      AgentGlob(v.agent.disabledTools, t.name) ? h('span', { class: 'chip' }, 'hidden from LLM') : null,
      h('span', { class: 'td' }, (t.description || '').split('\n')[0])),
    h('div', {},
      t.description && t.description.includes('\n') ? h('p', { class: 'hint' }, t.description) : null,
      form.empty ? h('p', { class: 'hint' }, 'No parameters.') : form.el,
      h('div', { class: 'row' }, run, h('span', { class: 'hint' }, 'Runs right away, without the LLM and without approval.')),
      out));
}

function AgentGlob(patterns, name) {
  return (patterns || []).some((p) => new RegExp('^' + p.trim().replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$').test(name));
}

async function waitRun(id) {
  for (;;) {
    const r = await api('GET', '/runs/' + enc(id) + '?after=1000000');
    if (FINISHED.includes(r.status)) return r;
    await new Promise((res) => setTimeout(res, 900));
  }
}

// ------------------------------------------------------------------ systems view
async function renderSystems() {
  const main = $('main');
  let groups = [];
  try { [groups] = await Promise.all([api('GET', '/systems'), loadAgents()]); } catch (e) { fail(e); }
  if (S.view !== 'systems') return;
  const kinds = [...new Set(groups.map((g) => g.kind))];
  const bad = groups.filter((g) => g.access.some((x) => sysStatusClass(x.status) === 'error')).length;
  const check = h('button', { onclick: async () => {
    check.disabled = true; check.textContent = 'Checking…';
    try { await api('POST', '/systems/check'); renderSystems(); toast('All systems checked'); } catch (e) { fail(e); check.disabled = false; check.textContent = 'Check all now'; }
  } }, 'Check all now');
  const body = [];
  for (const k of kinds) {
    body.push(h('h3', {}, kindIcon(k), ' ', k));
    for (const g of groups.filter((x) => x.kind === k)) body.push(systemCard(g));
  }
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Systems'),
      h('span', { class: 'meta' }, `${groups.length} systems across ${kinds.length} kinds${bad ? ` · ${bad} with problems` : ''}. Found through each agent's own tools; the same endpoint reached by two agents is one system.`),
      h('span', { class: 'spacer' }), check),
    groups.length ? h('div', {}, body) : h('div', { class: 'card empty' },
      'No systems yet. They appear once agents are online: the kube agent lists its clusters (k8s_clusters), the devops agent its Jenkins servers, registries and clusters, the VM control centre its hosts. For other agents, set up discovery in the agent\'s Systems tab.'));
}

function systemCard(g) {
  return h('div', { class: 'sys-card' },
    h('div', { class: 'hd' }, h('span', { class: 'ico', 'aria-hidden': 'true' }, kindIcon(g.kind)),
      h('div', { class: 'grow' }, h('div', { class: 'nm' }, g.name), h('div', { class: 'hint mono' }, g.endpoint || '')),
      h('span', { class: 'hint' }, g.access.length === 1 ? 'reached by 1 agent' : `reached by ${g.access.length} agents`)),
    g.access.map((s) => {
      const v = agentById(s.agent);
      const who = v ? (v.agent.name || v.agent.id) : s.agent;
      const det = Object.entries(s.details || {}).slice(0, 6).map(([k, x]) => h('span', { class: 'chip' }, `${k}: ${String(x).slice(0, 60)}`));
      return h('div', { class: 'access' },
        v ? avatar(v, 36) : null,
        h('div', { class: 'grow' },
          h('div', {}, h('strong', {}, who), v && v.agent.role ? h('span', { class: 'hint' }, ' · ' + v.agent.role) : null,
            s.name !== g.name ? h('span', { class: 'hint' }, ` (calls it “${s.name}”)`) : null),
          h('div', {}, h('span', { class: 'sys-chip' }, h('i', { class: sysStatusClass(s.status) }), s.status),
            s.readOnly ? h('span', { class: 'chip warn' }, 'read-only') : null, det),
          s.statusDetail ? h('div', { class: 'hint' }, s.statusDetail) : null,
          s.checkedAt ? h('div', { class: 'hint' }, 'checked ' + ago(s.checkedAt)) : null),
        h('div', { class: 'row' },
          (s.actions || []).map((act) => h('button', { class: 'small', onclick: () => quickAction(s.agent, s, act) }, act.label)),
          v ? h('button', { class: 'small primary', disabled: presence(v)[0] === 'offline', onclick: () => taskDialog(v, systemTaskPrefix(s)) }, 'Ask ' + who) : null));
    }));
}

// ------------------------------------------------------------------ mission
async function renderMission() {
  const main = $('main');
  try { await loadAgents(); } catch (e) { fail(e); }
  if (S.view !== 'mission') return;
  const usable = S.agents.filter((v) => v.agent.enabled);
  const chosen = new Set(usable.filter((v) => v.state.status === 'online').map((v) => v.agent.id));
  const goal = h('textarea', { rows: 6, placeholder: 'e.g. Build and deploy the orders service from main to the staging cluster, check it is healthy, and roll back if the health check fails.' });
  const chips = h('div', {}, usable.map((v) => {
    const c = h('span', { class: 'chip toggle' + (chosen.has(v.agent.id) ? '' : ' off'), role: 'checkbox', tabindex: 0, 'data-agent': v.agent.id, 'aria-checked': String(chosen.has(v.agent.id)) },
      (v.agent.name || v.agent.id) + (v.state.status !== 'online' ? ' (offline)' : ''));
    const flip = () => {
      if (chosen.has(v.agent.id)) chosen.delete(v.agent.id); else chosen.add(v.agent.id);
      c.classList.toggle('off', !chosen.has(v.agent.id));
      c.setAttribute('aria-checked', String(chosen.has(v.agent.id)));
    };
    c.addEventListener('click', flip);
    c.addEventListener('keydown', (e) => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); flip(); } });
    return c;
  }));
  let systems = [];
  try { systems = await api('GET', '/systems'); } catch { /* optional */ }
  const focus = h('select', { onchange: () => {
    const g = systems.find((s) => s.key === focus.value);
    if (!g) return;
    const reach = new Set(g.access.map((x) => x.agent));
    for (const c of chips.children) {
      const id = c.dataset.agent;
      const on = reach.has(id);
      if (on) chosen.add(id); else chosen.delete(id);
      c.classList.toggle('off', !on);
      c.setAttribute('aria-checked', String(on));
    }
  } }, h('option', { value: '' }, '(any)'), systems.map((g) => h('option', { value: g.key },
    `${kindIcon(g.kind)} ${g.name} · ${g.kind} · ${g.access.map((x) => (agentById(x.agent) || { agent: { name: x.agent } }).agent.name || x.agent).join(', ')}`)));
  const start = async (mode, btn) => {
    if (!goal.value.trim()) { toast('Describe the goal first', true); return; }
    if (!chosen.size) { toast('Pick at least one agent', true); return; }
    const g = systems.find((s) => s.key === focus.value);
    const text = (g ? `Target system: ${g.kind} "${g.name}"${g.endpoint ? ' (' + g.endpoint + ')' : ''}.\n` : '') + goal.value.trim();
    btn.disabled = true;
    try { const r = await api('POST', '/missions', { goal: text, mode, agents: [...chosen] }); showRun(r.runId); }
    catch (e) { fail(e); btn.disabled = false; }
  };
  const planBtn = h('button', { onclick: () => start('plan', planBtn) }, 'Plan first, I\'ll review');
  const runBtn = h('button', { class: 'primary', onclick: () => start('orchestrate', runBtn) }, 'Run with the orchestrator');
  const recent = h('div', {}, 'Loading…');
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Mission'), h('span', { class: 'meta' }, 'Give the team a goal; the orchestrator\'s LLM splits it into directives for the agents.')),
    h('div', { class: 'card' },
      field('Goal', goal),
      field('Focus on a system', focus, 'Optional. Picks the agents that can reach it and names it in the goal.'),
      field('Agents the orchestrator may use', chips, 'Click to include or leave out. Offline agents are skipped.'),
      h('div', { class: 'row end' }, planBtn, runBtn),
      h('p', { class: 'hint' }, '“Run” lets the orchestrator delegate as it goes and summarise at the end. “Plan first” drafts a chain of steps you can edit, run or save; nothing runs until you say so.')),
    h('div', { class: 'card' }, h('h3', {}, 'Recent missions'), recent));
  goal.focus();
  try {
    const runs = (await api('GET', '/runs?limit=200')).filter((r) => r.mode === 'orchestrate' || r.mode === 'plan').slice(0, 15);
    recent.replaceChildren(runs.length ? runsTable(runs) : h('p', { class: 'empty' }, 'No missions yet.'));
  } catch (e) { recent.replaceChildren(h('p', { class: 'err' }, e.message)); }
}

// ------------------------------------------------------------------ run view
const KIND = { start: 'started', plan: 'orchestrator', delegate: 'assigned a task', say: 'says', tool: 'calls a tool', 'tool-result': 'tool result',
  'tool-error': 'tool error', answer: 'reports', step: 'chain step', approval: 'needs your approval', 'approval-result': 'approval', done: 'finished', error: 'stopped' };

function eventRow(ev) {
  const v = ev.agent ? agentById(ev.agent) : null;
  const who = v ? (v.agent.name || v.agent.id) : ev.agent || (['say', 'plan', 'answer'].includes(ev.type) ? 'Orchestrator' : 'Run');
  const icon = v ? avatar(v, 30) : systemIcon(ev.type);
  const long = ev.detail && (ev.type === 'tool' || ev.type === 'tool-result' || ev.detail.length > 600);
  let detail = null;
  if (ev.detail) {
    detail = long ? h('details', {}, h('summary', { class: 'hint' }, ev.type === 'tool' ? 'arguments' : 'output (' + ev.detail.length + ' chars)'), h('pre', { class: 'out' }, ev.detail))
      : h('div', { class: 'txt' }, ev.detail);
  }
  return h('li', { class: 'ev ' + ev.type },
    h('div', { class: 'ic' }, icon),
    h('div', { class: 'body' },
      h('div', { class: 'hd' }, h('span', { class: 'who' }, who), h('span', { class: 'kind' }, KIND[ev.type] || ev.type),
        ev.title && ev.type !== 'say' ? h('span', { class: ev.type === 'tool' || ev.type === 'tool-result' || ev.type === 'tool-error' ? 'mono' : '' }, ev.title) : null,
        h('span', { class: 'ts' }, clock(ev.ts))),
      detail));
}

async function showRun(id) {
  clearTimeout(S.poll);
  clearInterval(S.runsTimer);
  S.view = 'run';
  S.runId = id;
  setNav('runs');
  if (!S.agents.length) { try { await loadAgents(); } catch { /* names fall back to ids */ } }
  const head = h('div', { class: 'page-head' });
  const approvals = h('div');
  const result = h('div');
  const extra = h('div');
  const tl = h('ol', { class: 'timeline' });
  $('main').replaceChildren(
    h('p', {}, h('button', { class: 'ghost small', onclick: () => go('runs') }, '← All runs')),
    head, approvals, result, extra, h('div', { class: 'card' }, h('h3', {}, 'Timeline'), tl));
  let after = 0;
  let planShown = false;
  const tick = async () => {
    if (S.runId !== id) return;
    let r;
    try { r = await api('GET', `/runs/${enc(id)}?after=${after}`); } catch (e) { fail(e); return; }
    if (S.runId !== id) return;
    for (const ev of r.events) { tl.append(eventRow(ev)); after = ev.seq; }
    runHead(head, r);
    runApprovals(approvals, r);
    runResult(result, r);
    if (r.mode === 'plan' && r.data && !planShown) {
      planShown = true;
      extra.replaceChildren(h('div', { class: 'card' }, h('h3', {}, 'Proposed plan: review, edit, then run or save'),
        chainEditor(r.data, { input: r.input, planned: true })));
    }
    if (!FINISHED.includes(r.status)) S.poll = setTimeout(tick, 1200);
  };
  tick();
}

function runHead(head, r) {
  const v = r.agent ? agentById(r.agent) : null;
  head.replaceChildren(
    v ? avatar(v, 40) : null,
    h('div', { class: 'grow' },
      h('h2', {}, r.title),
      h('div', { class: 'meta' }, h('span', { class: stateClass(r.status) }, r.status), ` · ${r.mode} · started ${stamp(r.createdAt)} · ${dur(r.createdAt, r.finishedAt)}`,
        r.inputTokens || r.outputTokens ? ` · ${r.inputTokens.toLocaleString()} in / ${r.outputTokens.toLocaleString()} out tokens` : '')),
    FINISHED.includes(r.status) ? null : h('button', { class: 'danger', onclick: async () => { try { await api('POST', `/runs/${enc(r.id)}/cancel`); toast('Cancelling…'); } catch (e) { fail(e); } } }, 'Cancel'));
}

function runApprovals(box, r) {
  const ids = (r.pendingApprovals || []).map((p) => p.approval).join(',');
  if (box.dataset.ids === ids) return;
  box.dataset.ids = ids;
  box.replaceChildren(...(r.pendingApprovals || []).map((p) => {
    const v = p.agent ? agentById(p.agent) : null;
    const answer = async (ok) => {
      try { await api('POST', `/runs/${enc(r.id)}/approvals/${enc(p.approval)}`, { approve: ok }); toast(ok ? 'Approved' : 'Denied'); refreshBadges(); }
      catch (e) { fail(e); }
    };
    return h('div', { class: 'approval', role: 'alert' },
      h('div', { class: 'row' }, v ? avatar(v, 32) : systemIcon('approval'), h('strong', { class: 'grow' }, p.title)),
      p.detail ? h('pre', { class: 'out' }, p.detail) : null,
      h('div', { class: 'row end' }, h('button', { class: 'danger', onclick: () => answer(false) }, 'Deny'), h('button', { class: 'primary', onclick: () => answer(true) }, 'Approve')));
  }));
}

function runResult(box, r) {
  const key = r.status + '|' + (r.result || '').length + '|' + (r.error || '');
  if (box.dataset.key === key) return;
  box.dataset.key = key;
  if (r.error) box.replaceChildren(h('div', { class: 'card' }, h('h3', { class: 'err' }, 'Error'), h('div', { class: 'result' }, r.error)));
  else if (r.result) box.replaceChildren(h('div', { class: 'card' }, h('h3', {}, 'Result'), h('div', { class: 'result' }, r.result)));
  else box.replaceChildren();
}

// ------------------------------------------------------------------ runs list
function runsTable(runs, compact) {
  return h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, h('th', {}, 'Status'), compact ? null : h('th', {}, 'Kind'), h('th', {}, 'Title'), compact ? null : h('th', {}, 'Agent'), h('th', {}, 'Started'), h('th', {}, 'Took'))),
    h('tbody', {}, runs.map((r) => {
      const v = r.agent ? agentById(r.agent) : null;
      return h('tr', { class: 'click', onclick: () => { if ($('dlg').open) $('dlg').close(); showRun(r.id); } },
        h('td', {}, h('span', { class: stateClass(r.status) }, r.status), r.pendingApprovals && r.pendingApprovals.length ? h('span', { class: 'badge', title: 'waiting for approval' }, '!') : null),
        compact ? null : h('td', {}, r.mode),
        h('td', {}, r.title),
        compact ? null : h('td', {}, v ? (v.agent.name || v.agent.id) : r.agent || '—'),
        h('td', {}, stamp(r.createdAt)),
        h('td', {}, dur(r.createdAt, r.finishedAt)));
    }))));
}

async function renderRuns() {
  const box = h('div', { class: 'card' }, 'Loading…');
  $('main').replaceChildren(h('div', { class: 'page-head' }, h('h2', {}, 'Runs'), h('span', { class: 'meta' }, 'Missions, delegations, chains and tool calls. Click one to see its timeline.')), box);
  if (!S.agents.length) { try { await loadAgents(); } catch { /* ignore */ } }
  const draw = async () => {
    try {
      const runs = await api('GET', '/runs?limit=200');
      if (S.view !== 'runs') return;
      box.replaceChildren(runs.length ? runsTable(runs) : h('p', { class: 'empty' }, 'Nothing has run yet.'));
    } catch (e) { box.replaceChildren(h('p', { class: 'err' }, e.message)); }
  };
  await draw();
  S.runsTimer = setInterval(draw, 4000);
}

// ------------------------------------------------------------------ chains
async function renderChains() {
  const main = $('main');
  try { await loadAgents(); } catch (e) { fail(e); }
  let chains = [];
  try { chains = await api('GET', '/chains'); } catch (e) { fail(e); }
  if (S.view !== 'chains') return;
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Chains'), h('span', { class: 'meta' }, 'Saved sequences of steps across agents. Each step can use the previous output.'),
      h('span', { class: 'spacer' }), h('button', { class: 'primary', onclick: () => editChain({ name: '', description: '', steps: [{ type: 'delegate', agent: '', task: '' }] }) }, 'New chain')),
    chains.length ? h('div', { class: 'grid g2' }, chains.map((c) => h('div', { class: 'card' },
      h('h3', {}, c.name),
      c.description ? h('p', { class: 'muted' }, c.description) : null,
      h('div', { class: 'row' }, c.steps.map((s, i) => {
        const v = agentById(s.agent);
        return h('span', { class: 'row', title: s.type === 'tool' ? s.tool : s.task }, i ? '→' : null, v ? avatar(v, 26) : h('span', { class: 'chip' }, s.agent));
      })),
      h('p', { class: 'hint' }, c.steps.length + ' steps · updated ' + ago(c.updatedAt)),
      h('div', { class: 'row end' }, h('button', { onclick: () => editChain(c) }, 'Edit'), h('button', { class: 'primary', onclick: () => runChainDialog(c) }, 'Run')))))
      : h('div', { class: 'card empty' }, 'No chains yet. Create one here, or use “Plan first” on the Mission page and save the plan.'));
}

function editChain(c) {
  clearTimeout(S.poll);
  S.view = 'chain-edit';
  setNav('chains');
  $('main').replaceChildren(
    h('p', {}, h('button', { class: 'ghost small', onclick: () => go('chains') }, '← Chains')),
    h('div', { class: 'page-head' }, h('h2', {}, c.id ? 'Edit chain' : 'New chain')),
    h('div', { class: 'card' }, chainEditor(c, {})));
}

/** Editor for a chain or a proposed plan. opts.planned: came from the planner (offer run + save). */
function chainEditor(chain, opts) {
  const c = JSON.parse(JSON.stringify(chain));
  c.steps = c.steps || [];
  const root = h('div');
  const agentOpts = (sel) => [h('option', { value: '' }, 'Choose an agent…'), S.agents.map((v) => h('option', { value: v.agent.id, selected: v.agent.id === sel }, (v.agent.name || v.agent.id) + (v.agent.role ? ' · ' + v.agent.role : '')))];

  function stepBox(s, i) {
    const v = agentById(s.agent);
    const tools = v ? (v.state.tools || []) : [];
    const move = (d) => { const j = i + d; if (j < 0 || j >= c.steps.length) return; [c.steps[i], c.steps[j]] = [c.steps[j], c.steps[i]]; draw(); };
    let work;
    if (s.type === 'tool') {
      const argsArea = h('textarea', { class: 'code', rows: 5, oninput: (e) => { s.args = e.target.value; } }, s.args || '{}');
      work = h('div', { class: 'grid g2' },
        field('Tool', h('select', { onchange: (e) => { s.tool = e.target.value; const t = tools.find((x) => x.name === s.tool); if (t && (!s.args || s.args.trim() === '{}')) { s.args = skeleton(t.inputSchema); } draw(); } },
          h('option', { value: '' }, tools.length ? 'Choose a tool…' : 'Agent offline: no tool list'), tools.map((t) => h('option', { value: t.name, selected: t.name === s.tool }, t.name)),
          s.tool && !tools.find((t) => t.name === s.tool) ? h('option', { value: s.tool, selected: true }, s.tool) : null),
          s.tool && tools.find((t) => t.name === s.tool) ? (tools.find((t) => t.name === s.tool).description || '').split('\n')[0] : null),
        field('Arguments (JSON)', argsArea, 'Use {{input}}, {{prev}} or {{step1}} inside strings.'));
    } else {
      work = field('Task', h('textarea', { rows: 3, oninput: (e) => { s.task = e.target.value; }, placeholder: 'Directive for the agent. Use {{prev}} for the previous step\'s output, {{input}} for the run input.' }, s.task || ''),
        s.note ? 'Why: ' + s.note : null);
    }
    return h('div', { class: 'step' },
      h('div', { class: 'row' }, h('span', { class: 'num' }, String(i + 1)),
        v ? avatar(v, 28) : null,
        h('div', { class: 'grow' }, h('select', { onchange: (e) => { s.agent = e.target.value; draw(); } }, agentOpts(s.agent))),
        h('select', { onchange: (e) => { s.type = e.target.value; draw(); }, style: null },
          h('option', { value: 'delegate', selected: s.type !== 'tool' }, 'Delegate a task (LLM)'),
          h('option', { value: 'tool', selected: s.type === 'tool' }, 'Call one tool (no LLM)')),
        h('button', { class: 'ghost small', 'aria-label': 'Move up', onclick: () => move(-1) }, '↑'),
        h('button', { class: 'ghost small', 'aria-label': 'Move down', onclick: () => move(1) }, '↓'),
        h('button', { class: 'ghost small', 'aria-label': 'Remove step', onclick: () => { c.steps.splice(i, 1); draw(); } }, '✕')),
      h('div', { class: 'field' }), work,
      h('div', { class: 'checks' },
        h('label', {}, h('input', { type: 'checkbox', checked: !!s.approve, onchange: (e) => { s.approve = e.target.checked; } }), 'Ask me before this step'),
        h('label', {}, h('input', { type: 'checkbox', checked: !!s.continueOnError, onchange: (e) => { s.continueOnError = e.target.checked; } }), 'Continue if it fails')));
  }

  function draw() {
    const steps = [];
    c.steps.forEach((s, i) => { if (i) steps.push(h('div', { class: 'arrow' }, '↓ output becomes {{prev}}')); steps.push(stepBox(s, i)); });
    const saveBtn = h('button', { onclick: async () => {
      saveBtn.disabled = true;
      try {
        const saved = c.id ? await api('PUT', '/chains/' + enc(c.id), c) : await api('POST', '/chains', c);
        c.id = saved.id;
        toast('Chain saved');
        if (!opts.planned) go('chains');
      } catch (e) { fail(e); }
      saveBtn.disabled = false;
    } }, opts.planned ? 'Save as chain' : 'Save');
    const runBtn = h('button', { class: 'primary', onclick: () => runChainDialog(c, opts.input) }, opts.planned ? 'Run this plan' : 'Run');
    root.replaceChildren(
      h('div', { class: 'grid g2' },
        field('Name', h('input', { type: 'text', value: c.name || '', oninput: (e) => { c.name = e.target.value; } })),
        field('Description', h('input', { type: 'text', value: c.description || '', oninput: (e) => { c.description = e.target.value; } }))),
      steps,
      h('div', { class: 'row' }, h('button', { onclick: () => { c.steps.push({ type: 'delegate', agent: '', task: '' }); draw(); } }, '+ Add step'),
        h('span', { class: 'spacer grow' }),
        c.id && !opts.planned ? h('button', { class: 'danger', onclick: async () => {
          if (!confirm('Delete chain ' + c.name + '?')) return;
          try { await api('DELETE', '/chains/' + enc(c.id)); toast('Deleted'); go('chains'); } catch (e) { fail(e); }
        } }, 'Delete') : null,
        saveBtn, runBtn));
  }
  draw();
  return root;
}

function runChainDialog(c, preset) {
  const input = h('textarea', { rows: 4, placeholder: 'Optional. Available to steps as {{input}} (and as {{prev}} for step 1).' }, preset || '');
  const btn = h('button', { class: 'primary', onclick: async () => {
    btn.disabled = true;
    try { const r = await api('POST', '/chains/run', { chain: c, input: input.value }); $('dlg').close(); showRun(r.runId); }
    catch (e) { fail(e); btn.disabled = false; }
  } }, 'Run');
  openDialog('Run “' + (c.name || 'chain') + '”', h('div', {}, field('Input', input),
    h('p', { class: 'hint' }, c.steps.length + ' steps run in order. Steps marked “ask me” pause for your approval.')),
  [h('button', { onclick: () => $('dlg').close() }, 'Cancel'), btn]);
}

// ------------------------------------------------------------------ registry
async function renderRegistry() {
  const main = $('main');
  let pending = [];
  try { [pending] = await Promise.all([api('GET', '/registry/pending'), loadAgents(), refreshBadges()]); } catch (e) { fail(e); }
  if (S.view !== 'registry') return;
  const info = S.info || {};
  const origin = location.origin;
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Registry'), h('span', { class: 'meta' }, `${S.agents.length} agents on the team · ${pending.length} waiting`),
      h('span', { class: 'spacer' }),
      h('button', { onclick: async (e) => { e.target.disabled = true; try { const r = await api('POST', '/registry/scan'); toast(`Scanned: ${r.agents} agents, ${r.pending} waiting`); renderRegistry(); } catch (x) { fail(x); } e.target.disabled = false; } }, 'Scan now')),
    h('div', { class: 'card' }, h('h3', {}, 'Inbox'),
      pending.length ? pending.map(pendingRow) : h('p', { class: 'empty' }, 'No agents waiting. Agents that register themselves or are found by discovery appear here for you to approve.')),
    h('div', { class: 'card' }, h('h3', {}, 'Team catalogue'),
      S.agents.length ? h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'Agent'), h('th', {}, 'Server'), h('th', {}, 'Endpoint'), h('th', {}, 'Tools'), h('th', {}, 'Source'), h('th', {}, 'Status'))),
        h('tbody', {}, S.agents.map((v) => h('tr', { class: 'click', onclick: () => agentDialog(v, 'tools') },
          h('td', {}, h('div', { class: 'row' }, avatar(v, 26), v.agent.name || v.agent.id)),
          h('td', {}, v.state.server ? v.state.server.name + ' ' + v.state.server.version : '—'),
          h('td', { class: 'mono' }, v.agent.transport === 'stdio' ? joinArgs(v.agent.command) : v.agent.url),
          h('td', { title: (v.state.tools || []).map((t) => t.name).join(', ') }, String((v.state.tools || []).length)),
          h('td', {}, v.agent.source || 'manual'),
          h('td', {}, h('span', { class: stateClass(v.state.status) }, v.state.status))))))) : h('p', { class: 'empty' }, 'No agents yet.')),
    h('div', { class: 'card' }, h('h3', {}, 'How agents join'),
      h('p', {}, h('strong', {}, '1. In the UI. '), 'Team → Add agent: an HTTP URL (and token), or a command the orchestrator starts over stdio.'),
      h('p', {}, h('strong', {}, '2. Drop-in file. '), 'Put a YAML file in ', h('code', {}, (info.home || '~/.orchestrator') + '/agents.d/'), '; it is picked up within a minute, no restart:'),
      h('pre', { class: 'out' }, 'id: kube\nname: Kira\nrole: Kubernetes engineer\nbio: Inspects, deploys and troubleshoots workloads on our clusters.\ntransport: stdio\ncommand: [java, -jar, /opt/agents/mcp-kube-agent.jar]\ninstructions: |\n  Always dry-run applies first.\nconfirm-tools: [k8s_delete, k8s_drain]'),
      h('p', {}, h('strong', {}, '3. Self-registration. '), info.registration
        ? 'Enabled. An agent (or its install script) calls:'
        : 'Off. Set orchestrator.registration.token-ref (e.g. env:ORCHESTRATOR_REGISTRATION_TOKEN) and listen on the network (ORCHESTRATOR_HOST) to enable it. Then:'),
      h('pre', { class: 'out' }, `curl -X POST ${origin}/registry/register \\\n  -H "Authorization: Bearer $REGISTRATION_TOKEN" -H "Content-Type: application/json" \\\n  -d '{"id":"devops","name":"Dev","role":"CI/CD engineer","url":"http://10.0.0.7:8766/mcp","token":"<agent token>","tags":["jenkins"]}'`),
      h('p', { class: 'hint' }, 'New registrations wait here until you approve them. A registered agent calling again with the same URL counts as a heartbeat.'),
      h('p', {}, h('strong', {}, '4. Discovery. '), `orchestrator.discovery.urls in config.yml lists MCP endpoints to probe on every refresh (${info.discoveryUrls || 0} configured). New servers that answer land in the inbox.`)));
}

function pendingRow(p) {
  const s = p.spec;
  const fake = { agent: s, state: { status: 'unknown', busy: 0 } };
  return h('div', { class: 'step' },
    h('div', { class: 'row' }, avatar(fake, 44),
      h('div', { class: 'grow' }, h('strong', {}, s.name || s.id), s.role ? ' · ' + s.role : '', h('div', { class: 'hint mono' }, s.url)),
      h('span', { class: 'chip' }, s.source)),
    s.bio ? h('p', { class: 'muted' }, s.bio) : null,
    h('p', { class: 'hint' }, `From ${p.from} · ${ago(p.receivedAt)}`, p.serverName ? ` · server ${p.serverName} ${p.serverVersion || ''}` : ''),
    p.probeError ? h('p', { class: 'err' }, 'Could not connect: ' + p.probeError) : h('p', { class: 'hint' }, (p.tools || []).length + ' tools: ' + (p.tools || []).join(', ')),
    h('div', { class: 'row end' },
      h('button', { class: 'danger', onclick: async () => { try { await api('DELETE', '/registry/pending/' + enc(s.id)); toast('Rejected'); renderRegistry(); } catch (e) { fail(e); } } }, 'Reject'),
      h('button', { class: 'primary', onclick: async () => {
        try { await api('POST', '/registry/pending/' + enc(s.id) + '/approve', { agent: s }); toast((s.name || s.id) + ' joined the team'); refreshBadges(); renderRegistry(); } catch (e) { fail(e); }
      } }, 'Approve')));
}

// ------------------------------------------------------------------ settings
async function renderSettings() {
  const main = $('main');
  let r;
  try { r = await api('GET', '/settings'); } catch (e) { fail(e); return; }
  if (S.view !== 'settings') return;
  const st = r.settings;
  const keys = {};
  const provBox = h('div');
  const drawProviders = () => {
    provBox.replaceChildren(...Object.entries(st.providers).map(([name, p]) => {
      const testOut = h('span', { class: 'hint' });
      return h('div', { class: 'step' },
        h('div', { class: 'row' }, h('strong', { class: 'grow' }, name, name === st.defaultProvider ? h('span', { class: 'chip' }, 'default') : null),
          h('button', { class: 'small', onclick: async (e) => {
            e.target.disabled = true; testOut.textContent = 'Testing (save first if you changed it)…';
            try { const t = await api('POST', '/settings/test/' + enc(name)); testOut.replaceChildren(h('span', { class: 'okc' }, `✓ ${t.model} replied “${(t.reply || '').slice(0, 40)}” in ${t.ms} ms`)); }
            catch (x) { testOut.replaceChildren(h('span', { class: 'err' }, x.message)); }
            e.target.disabled = false;
          } }, 'Test'),
          h('button', { class: 'small', onclick: () => { st.defaultProvider = name; drawProviders(); } }, 'Make default'),
          Object.keys(st.providers).length > 1 ? h('button', { class: 'ghost small', 'aria-label': 'Remove provider', onclick: () => { delete st.providers[name]; if (st.defaultProvider === name) st.defaultProvider = Object.keys(st.providers)[0]; drawProviders(); } }, '✕') : null),
        testOut,
        h('div', { class: 'grid g3' },
          field('Type', h('select', { onchange: (e) => { p.type = e.target.value; drawProviders(); } },
            h('option', { value: 'anthropic', selected: p.type === 'anthropic' }, 'Claude (Anthropic API)'),
            h('option', { value: 'openai', selected: p.type === 'openai' }, 'OpenAI-compatible (Ollama, vLLM…)'))),
          field('Model', h('input', { type: 'text', value: p.model || '', oninput: (e) => { p.model = e.target.value; } })),
          p.type === 'anthropic'
            ? field('Effort', h('select', { onchange: (e) => { p.effort = e.target.value || null; } }, ['', 'low', 'medium', 'high', 'xhigh', 'max'].map((x) => h('option', { value: x, selected: (p.effort || '') === x }, x || '(model default)'))))
            : field('Base URL', h('input', { type: 'text', value: p.baseUrl || '', placeholder: 'http://127.0.0.1:11434/v1', oninput: (e) => { p.baseUrl = e.target.value; } })),
          field('API key', h('input', { type: 'password', autocomplete: 'off', placeholder: p.apiKeyRef ? 'stored (' + p.apiKeyRef + '); paste to replace' : (p.type === 'anthropic' ? 'blank: ANTHROPIC_API_KEY / ant auth' : 'blank: none'), oninput: (e) => { keys[name] = e.target.value; } })),
          field('…or key reference', h('input', { type: 'text', value: p.apiKeyRef || '', placeholder: 'env:ANTHROPIC_API_KEY', oninput: (e) => { p.apiKeyRef = e.target.value || null; } })),
          field('Max output tokens', h('input', { type: 'number', value: p.maxTokens || '', placeholder: '16000', oninput: (e) => { p.maxTokens = e.target.value ? Number(e.target.value) : null; } }))),
        p.type === 'anthropic' ? h('div', { class: 'grid g2' },
          field('Custom base URL', h('input', { type: 'text', value: p.baseUrl || '', placeholder: '(optional, e.g. a gateway)', oninput: (e) => { p.baseUrl = e.target.value || null; } })),
          h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: p.fallbacks !== false, onchange: (e) => { p.fallbacks = e.target.checked; } }),
            'Refusal fallback: if the model declines, the API retries on a fallback model (supported Claude models only)')) : null);
    }));
  };
  drawProviders();
  const newName = h('input', { type: 'text', placeholder: 'e.g. local', class: 'grow' });
  const orchSel = h('select', { onchange: (e) => { st.orchestratorProvider = e.target.value || null; } }, h('option', { value: '' }, '(default provider)'),
    Object.keys(st.providers).map((n) => h('option', { value: n, selected: st.orchestratorProvider === n }, n)));
  const promptArea = (key, dflt) => h('textarea', { rows: 12, oninput: (e) => { st[key] = e.target.value; }, placeholder: dflt }, st[key] || '');
  const orchPrompt = promptArea('orchestratorInstructions', r.defaults.orchestrator);
  const planPrompt = promptArea('plannerInstructions', r.defaults.planner);
  const save = h('button', { class: 'primary', onclick: async () => {
    save.disabled = true;
    try { await api('PUT', '/settings', { settings: st, apiKeys: keys }); toast('Settings saved'); renderSettings(); } catch (e) { fail(e); }
    save.disabled = false;
  } }, 'Save settings');
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Settings'), h('span', { class: 'spacer' }), save),
    h('div', { class: 'card' }, h('h3', {}, 'LLM providers'),
      h('p', { class: 'hint' }, 'Agents use the default provider unless their Instructions tab picks another. Keys are stored as references; pasted keys go to tokens.yml (mode 600) and are never shown again.'),
      provBox,
      h('div', { class: 'row' }, newName, h('button', { onclick: () => {
        const n = newName.value.trim().toLowerCase();
        if (!/^[a-z0-9][a-z0-9-]{0,30}$/.test(n) || st.providers[n]) { toast('Use a new name: lowercase letters, digits, dashes', true); return; }
        st.providers[n] = { type: 'openai', model: '', baseUrl: 'http://127.0.0.1:11434/v1' };
        newName.value = '';
        drawProviders();
      } }, '+ Add provider'))),
    h('div', { class: 'card' }, h('h3', {}, 'Orchestrator'),
      h('div', { class: 'grid g2' },
        field('Provider for the orchestrator and planner', orchSel),
        field('Model override', h('input', { type: 'text', value: st.orchestratorModel || '', placeholder: '(provider model)', oninput: (e) => { st.orchestratorModel = e.target.value || null; } }))),
      field('Orchestrator instructions', orchPrompt, 'System prompt used when the orchestrator works a mission. Leave empty for the built-in default shown. The team roster is appended automatically.'),
      h('div', { class: 'row' }, h('button', { class: 'small', onclick: () => { orchPrompt.value = r.defaults.orchestrator; st.orchestratorInstructions = orchPrompt.value; } }, 'Start from the default')),
      field('Planner instructions', planPrompt, 'System prompt used by “Plan first”.'),
      h('div', { class: 'row' }, h('button', { class: 'small', onclick: () => { planPrompt.value = r.defaults.planner; st.plannerInstructions = planPrompt.value; } }, 'Start from the default'))));
}

// ------------------------------------------------------------------ boot
for (const b of document.querySelectorAll('#views button')) b.addEventListener('click', () => go(b.dataset.view));
go('team');
refreshBadges();
setInterval(() => {
  refreshBadges();
  if (S.view === 'team' && !$('dlg').open) renderTeamGrid().catch(() => {});
}, 5000);
