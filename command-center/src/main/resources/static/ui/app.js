'use strict';
/* Command Center UI. Plain DOM, no innerHTML with data: everything goes through h(). */

const S = {
  session: null, catalog: null, health: {}, route: ['home'], timers: [],
  fav: new Set(load('fav', [])),
  filters: { q: '', category: '', env: '', state: '' },
};

// ------------------------------------------------------------------ helpers
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style') el.style.cssText = v;
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
function save(key, v) { try { localStorage.setItem('cc.' + key, JSON.stringify(v)); } catch { /* private mode */ } }
function load(key, d) { try { const v = localStorage.getItem('cc.' + key); return v ? JSON.parse(v) : d; } catch { return d; } }

class ApiError extends Error {
  constructor(status, data) { super((data && data.error) || ('HTTP ' + status)); this.status = status; this.data = data || {}; }
}
async function api(method, path, body, raw) {
  const opt = { method, headers: { 'X-Command-Center': '1' } };
  if (raw !== undefined) { opt.body = raw; opt.headers['Content-Type'] = 'application/yaml'; }
  else if (body !== undefined) { opt.body = JSON.stringify(body); opt.headers['Content-Type'] = 'application/json'; }
  const r = await fetch('/ui/api' + path, opt);
  const text = await r.text();
  if (!r.ok) {
    let data = null;
    try { data = JSON.parse(text); } catch { data = { error: text.slice(0, 300) }; }
    throw new ApiError(r.status, data);
  }
  const ct = r.headers.get('content-type') || '';
  return ct.includes('json') ? (text ? JSON.parse(text) : null) : text;
}
const enc = encodeURIComponent;

function toast(msg, bad) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast show' + (bad ? ' bad' : '');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => { t.className = 'toast'; }, bad ? 6000 : 2200);
}
const fail = (e) => toast(e.message || String(e), true);

async function copyText(text, btn) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const ta = h('textarea', { style: 'position:fixed;opacity:0' }, text);
    document.body.append(ta); ta.select();
    try { document.execCommand('copy'); } catch { /* ignore */ }
    ta.remove();
  }
  if (btn) { const o = btn.textContent; btn.textContent = 'Copied'; setTimeout(() => { btn.textContent = o; }, 1200); } else toast('Copied');
}
function ago(iso) {
  if (!iso) return 'never';
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 5) return 'just now';
  if (s < 90) return s + ' s ago';
  if (s < 5400) return Math.round(s / 60) + ' min ago';
  return Math.round(s / 3600) + ' h ago';
}
function clearTimers() { S.timers.forEach(clearInterval); S.timers = []; }

// ------------------------------------------------------------------ catalog accessors
const kindOf = (s) => (S.session.kinds.find((k) => k.id === s.kind) || S.session.kinds.find((k) => k.id === 'generic'));
const categoryOf = (s) => (s.category && S.session.categories.some((c) => c.id === s.category) ? s.category : kindOf(s).category);
const catLabel = (id) => (S.session.categories.find((c) => c.id === id) || { label: id }).label;
const sys = (id) => S.catalog.systems.find((s) => s.id === id);
const healthOf = (id) => S.catalog.health[id] || null;
function stateOf(s) {
  const hh = healthOf(s.id);
  if (!hh) return (s.links || []).length || s.healthUrl ? 'pending' : 'unknown';
  return hh.latest.state;
}
function primaryLink(s) {
  const links = s.links || [];
  return links.find((l) => l.type === 'ui') || links.find((l) => l.type === 'api') || links.find((l) => l.type === 'mcp') || links[0] || null;
}
function placeholderVars(s) {
  const links = s.links || [];
  const first = (t) => { const l = links.find((x) => x.type === t); return l ? l.url : null; };
  const url = (links[0] || {}).url || null;
  let host = null, origin = null;
  try { if (url) { const u = new URL(url); host = u.host; origin = u.origin; } } catch { /* ignore */ }
  return { name: s.name, id: s.id, url: origin || url, ui: first('ui'), api: first('api'), mcp: first('mcp'),
    docs: first('docs'), metrics: first('metrics'), host };
}
function usedBy(id) {
  return S.catalog.systems.filter((s) => (s.dependsOn || []).some((d) => d.to === id));
}

// ------------------------------------------------------------------ shared pieces
function kindIcon(s, big) {
  const k = kindOf(s);
  return h('span', { class: 'kicon' + (big ? ' big' : ''), style: `--k:${k.color}`, title: k.label, 'aria-hidden': 'true' }, k.abbr);
}
const STATE_TEXT = { up: 'Up', down: 'Down', unknown: 'No URL', pending: 'Checking…' };
function statePill(s) {
  const st = stateOf(s);
  const hh = healthOf(s.id);
  const title = hh ? `${hh.latest.message || ''}${hh.latest.httpStatus ? ' · HTTP ' + hh.latest.httpStatus : ''} · checked ${ago(hh.latest.checkedAt)}` : '';
  return h('span', { class: 'pill ' + st, title }, h('span', { class: 'dot ' + st }), STATE_TEXT[st] || st,
    hh && hh.latest.latencyMs != null && st === 'up' ? h('span', { class: 'lat' }, hh.latest.latencyMs + ' ms') : null);
}
function favButton(s) {
  const on = S.fav.has(s.id);
  return h('button', { class: 'ghost icon star' + (on ? ' on' : ''), title: on ? 'Remove from favourites' : 'Add to favourites',
    'aria-pressed': on ? 'true' : 'false', 'aria-label': 'Favourite',
    onclick: (e) => {
      e.stopPropagation(); e.preventDefault();
      if (S.fav.has(s.id)) S.fav.delete(s.id); else S.fav.add(s.id);
      save('fav', [...S.fav]); render();
    } }, on ? '★' : '☆');
}
function linkButtons(s, max) {
  return (s.links || []).slice(0, max || 9).map((l) => h('a', { class: 'btn small' + (l.type === 'ui' ? ' primary-soft' : ''),
    href: l.url, target: '_blank', rel: 'noopener noreferrer', title: l.url, onclick: (e) => e.stopPropagation() },
  l.label || l.type, ' ↗'));
}
function systemCard(s) {
  const k = kindOf(s);
  const p = primaryLink(s);
  return h('article', { class: 'scard', tabindex: '0', role: 'link', 'aria-label': s.name,
    onclick: () => go('system', s.id), onkeydown: (e) => { if (e.key === 'Enter') go('system', s.id); } },
  h('div', { class: 'scard-head' }, kindIcon(s), h('div', { class: 'grow' }, h('div', { class: 'nm' }, s.name),
    h('div', { class: 'sub' }, k.label, s.environment ? ' · ' + s.environment : '')), favButton(s)),
  s.description ? h('p', { class: 'desc' }, s.description) : null,
  h('div', { class: 'scard-foot' }, statePill(s), h('span', { class: 'spacer' }),
    p ? h('a', { class: 'btn small primary-soft', href: p.url, target: '_blank', rel: 'noopener noreferrer',
      onclick: (e) => e.stopPropagation(), title: p.url }, 'Open ↗')
      : h('span', { class: 'muted small' }, 'No URL yet')));
}

// ------------------------------------------------------------------ routing
function go(...route) { location.hash = '#/' + route.map(enc).join('/'); }
function parseRoute() {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
  return parts.length ? parts : ['home'];
}
function render() {
  clearTimers();
  S.route = parseRoute();
  const v = S.route[0];
  document.querySelectorAll('#nav a').forEach((a) => a.classList.toggle('on', a.dataset.view === v || (v === 'system' && a.dataset.view === 'catalog')));
  const views = { home: renderHome, catalog: renderCatalog, agents: renderAgents, architecture: renderArchitecture,
    settings: renderSettings, system: renderSystem };
  (views[v] || renderHome)();
  S.timers.push(setInterval(refreshHealth, 15000));
}

async function loadAll() {
  [S.session, S.catalog] = await Promise.all([api('GET', '/session'), api('GET', '/catalog')]);
  $('ro').hidden = S.session.canEdit;
}
async function refreshHealth() {
  if (document.hidden) return;
  try {
    S.catalog.health = await api('GET', '/health');
    document.querySelectorAll('[data-live]').forEach((el) => el.dispatchEvent(new Event('refresh')));
  } catch { /* offline: keep last */ }
}
async function reload() { S.catalog = await api('GET', '/catalog'); render(); }

// ------------------------------------------------------------------ home
function counts(list) {
  const c = { up: 0, down: 0, unknown: 0, pending: 0 };
  list.forEach((s) => { c[stateOf(s)] = (c[stateOf(s)] || 0) + 1; });
  return c;
}
function renderHome() {
  const main = $('main');
  const all = S.catalog.systems;
  const c = counts(all);
  const search = h('input', { type: 'search', class: 'hero-search', placeholder: 'Search systems, agents, tags, URLs…  (press / )',
    'aria-label': 'Search everything', value: S.filters.q,
    oninput: () => { S.filters.q = search.value; drawResults(); },
    onkeydown: (e) => { if (e.key === 'Enter') { const first = results.querySelector('.scard'); if (first) first.click(); } } });
  const results = h('div', { class: 'cards' });
  const drawResults = () => {
    const q = S.filters.q.trim().toLowerCase();
    results.replaceChildren(...(q ? matches(all, q).slice(0, 12).map(systemCard) : []));
    results.hidden = !q;
    rest.hidden = !!q;
  };
  const favs = all.filter((s) => S.fav.has(s.id));
  const attention = all.filter((s) => stateOf(s) === 'down'
    || (healthOf(s.id) && healthOf(s.id).certWarn));
  const noUrl = all.filter((s) => !(s.links || []).length && !s.healthUrl);
  const tile = (n, label, cls, onclick) => h('button', { class: 'tile ' + cls, onclick }, h('strong', {}, String(n)), h('span', {}, label));
  const rest = h('div', {},
    h('div', { class: 'tiles' },
      tile(all.length, 'systems', '', () => go('catalog')),
      tile(c.up, 'up', 'up', () => { S.filters.state = 'up'; go('catalog'); }),
      tile(c.down, 'down', 'down', () => { S.filters.state = 'down'; go('catalog'); }),
      tile(noUrl.length, 'need a URL', 'unknown', () => { S.filters.state = 'unknown'; go('catalog'); }),
      tile(all.filter((s) => categoryOf(s) === 'agents').length, 'agents', 'agents', () => go('agents'))),
    attention.length ? h('section', { class: 'card warn-card' }, h('h3', {}, 'Needs attention'),
      h('ul', { class: 'plain' }, attention.map((s) => {
        const hh = healthOf(s.id);
        return h('li', {}, h('a', { href: '#/system/' + enc(s.id) }, s.name), ' — ',
          stateOf(s) === 'down' ? h('span', { class: 'err' }, hh.latest.message) : null,
          hh && hh.certWarn ? h('span', { class: 'warn' }, ` certificate expires in ${hh.latest.certDaysLeft} days`) : null);
      }))) : null,
    h('section', {}, h('div', { class: 'sec-head' }, h('h2', {}, 'Favourites'),
      h('span', { class: 'muted small' }, favs.length ? '' : 'Star a system (☆) to pin it here.')),
    favs.length ? h('div', { class: 'cards' }, favs.map(systemCard)) : null),
    onboarding(),
    h('section', {}, h('div', { class: 'sec-head' }, h('h2', {}, 'By area')),
      h('div', { class: 'areas' }, S.session.categories.map((cat) => {
        const list = all.filter((s) => categoryOf(s) === cat.id);
        if (!list.length) return null;
        const cc = counts(list);
        return h('button', { class: 'area', onclick: () => { S.filters.category = cat.id; go('catalog'); } },
          h('div', { class: 'area-head' }, h('strong', {}, cat.label), h('span', { class: 'muted small' }, `${list.length}`)),
          h('div', { class: 'area-icons' }, list.slice(0, 8).map((s) => kindIcon(s))),
          h('div', { class: 'small muted' }, cat.description),
          cc.down ? h('div', { class: 'small err' }, `${cc.down} down`) : null);
      }))));
  main.replaceChildren(
    h('div', { class: 'hero' }, h('h1', {}, 'Lab command center'),
      h('p', { class: 'muted' }, 'Every system, app and agent in one place: open it, learn how to use it, see how it fits.'),
      search),
    results, rest);
  drawResults();
  search.focus();
}

function matches(list, q) {
  return list.filter((s) => [s.name, s.id, s.description, s.kind, kindOf(s).label, catLabel(categoryOf(s)), s.environment, s.owner,
    ...(s.tags || []), ...(s.links || []).map((l) => l.url + ' ' + (l.label || ''))]
    .some((x) => x && String(x).toLowerCase().includes(q)));
}

/* A first-day checklist built from what the catalog contains; ticks are kept in this browser. */
function onboarding() {
  const done = new Set(load('onboard', []));
  const have = (kind) => S.catalog.systems.find((s) => s.kind === kind);
  const items = [
    have('keycloak') && { id: 'sso', text: 'Get a Keycloak account and log in once', sys: have('keycloak') },
    have('password-manager') && { id: 'pw', text: 'Join the password manager’s lab collection', sys: have('password-manager') },
    have('git') && { id: 'git', text: 'Get access to the repositories and add your SSH key', sys: have('git') },
    have('kubernetes') && { id: 'kube', text: 'Get a namespace and a kubeconfig; run kubectl get ns', sys: have('kubernetes') },
    have('harbor') && { id: 'harbor', text: 'docker login to Harbor and find your team’s project', sys: have('harbor') },
    have('grafana') && { id: 'grafana', text: 'Open Grafana and find your service’s dashboard', sys: have('grafana') },
    have('mcp-agent') && { id: 'agents', text: 'Add the MCP agents you need to Claude Code', go: 'agents' },
    { id: 'arch', text: 'Read “How it fits together” (Architecture)', go: 'architecture' },
  ].filter(Boolean);
  const left = items.filter((i) => !done.has(i.id)).length;
  if (!left && load('onboardHidden', false)) return null;
  return h('section', { class: 'card' },
    h('div', { class: 'sec-head' }, h('h2', {}, 'New here? Start with these'), h('span', { class: 'muted small' }, `${items.length - left} of ${items.length} done`),
      h('span', { class: 'spacer' }), !left ? h('button', { class: 'ghost small', onclick: () => { save('onboardHidden', true); render(); } }, 'Hide') : null),
    h('ul', { class: 'checklist' }, items.map((i) => {
      const cb = h('input', { type: 'checkbox', checked: done.has(i.id), id: 'ob-' + i.id,
        onchange: () => { if (cb.checked) done.add(i.id); else done.delete(i.id); save('onboard', [...done]); render(); } });
      return h('li', { class: done.has(i.id) ? 'done' : null }, cb, h('label', { for: 'ob-' + i.id }, i.text),
        i.sys ? h('a', { href: '#/system/' + enc(i.sys.id), class: 'small' }, 'how →') : h('a', { href: '#/' + i.go, class: 'small' }, 'open →'));
    })));
}

// ------------------------------------------------------------------ catalog
function renderCatalog() {
  const main = $('main');
  const f = S.filters;
  const envs = [...new Set(S.catalog.systems.map((s) => s.environment).filter(Boolean))].sort();
  const sel = (key, label, opts) => {
    const s = h('select', { 'aria-label': label, onchange: () => { f[key] = s.value; draw(); } }, opts.map(([v, l]) => h('option', { value: v }, l)));
    s.value = f[key];
    return s;
  };
  const q = h('input', { type: 'search', id: 'cat-q', placeholder: 'Filter (name, tag, URL, kind)…', 'aria-label': 'Filter systems', value: f.q,
    oninput: () => { f.q = q.value; draw(); } });
  const body = h('div', { 'data-live': '1' });
  body.addEventListener('refresh', () => draw());
  const draw = () => {
    const ql = f.q.trim().toLowerCase();
    let list = ql ? matches(S.catalog.systems, ql) : S.catalog.systems.slice();
    if (f.category) list = list.filter((s) => categoryOf(s) === f.category);
    if (f.env) list = list.filter((s) => s.environment === f.env);
    if (f.state) list = list.filter((s) => stateOf(s) === f.state);
    const groups = S.session.categories.map((c) => [c, list.filter((s) => categoryOf(s) === c.id)]).filter(([, l]) => l.length);
    body.replaceChildren(...(groups.length ? groups.map(([c, l]) => h('section', {},
      h('div', { class: 'sec-head' }, h('h2', {}, c.label), h('span', { class: 'muted small' }, c.description)),
      h('div', { class: 'cards' }, l.sort((a, b) => a.name.localeCompare(b.name)).map(systemCard))))
      : [h('p', { class: 'empty' }, 'Nothing matches these filters.')]));
  };
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h1', {}, 'Catalog'), h('span', { class: 'muted' }, `${S.catalog.systems.length} systems`),
      h('span', { class: 'spacer' }), S.session.canEdit ? h('button', { class: 'primary', onclick: () => systemForm() }, '+ Add system') : null),
    h('div', { class: 'filters' }, h('div', { class: 'grow' }, q),
      sel('category', 'Area', [['', 'All areas'], ...S.session.categories.map((c) => [c.id, c.label])]),
      sel('env', 'Environment', [['', 'All environments'], ...envs.map((e) => [e, e])]),
      sel('state', 'Status', [['', 'Any status'], ['up', 'Up'], ['down', 'Down'], ['unknown', 'No URL']]),
      (f.q || f.category || f.env || f.state) ? h('button', { class: 'ghost', onclick: () => { S.filters = { q: '', category: '', env: '', state: '' }; render(); } }, 'Clear') : null),
    body);
  draw();
}

// ------------------------------------------------------------------ agents
function mcpAddCommand(s) {
  const mcp = (s.links || []).find((l) => l.type === 'mcp');
  if (!mcp) return null;
  return `claude mcp add --transport http --scope user ${s.id} ${mcp.url} \\\n  --header "Authorization: Bearer <token from the password manager>"`;
}
function renderAgents() {
  const main = $('main');
  const agents = S.catalog.systems.filter((s) => categoryOf(s) === 'agents');
  const orch = agents.filter((s) => s.kind === 'orchestrator');
  const others = agents.filter((s) => s.kind !== 'orchestrator');
  const groups = new Map();
  others.forEach((s) => {
    const g = s.source === 'orchestrator' ? 'Registered with the orchestrator'
      : (s.tags || []).includes('vm-worker') ? 'VM worker agents' : 'Other agents';
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g).push(s);
  });
  const os = S.session.orchestrator;
  const syncBtn = h('button', { onclick: async () => {
    syncBtn.disabled = true;
    try { const r = await api('POST', '/integrations/orchestrator/sync'); toast('Orchestrator: ' + r.message); await loadAll(); render(); }
    catch (e) { fail(e); } finally { syncBtn.disabled = false; }
  }, disabled: !S.session.canEdit }, 'Sync from orchestrator');
  const table = (list) => h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, ['', 'Agent', 'Status', 'MCP endpoint', 'Claude Code', ''].map((x) => h('th', {}, x)))),
    h('tbody', {}, list.sort((a, b) => a.name.localeCompare(b.name)).map((s) => {
      const mcp = (s.links || []).find((l) => l.type === 'mcp');
      const cmd = mcpAddCommand(s);
      const cbtn = cmd ? h('button', { class: 'small', onclick: (e) => copyText(cmd, e.currentTarget) }, 'Copy “claude mcp add”') : null;
      return h('tr', {},
        h('td', {}, kindIcon(s)),
        h('td', {}, h('a', { href: '#/system/' + enc(s.id) }, h('strong', {}, s.name)), s.description ? h('div', { class: 'small muted clamp' }, s.description) : null),
        h('td', {}, statePill(s)),
        h('td', { class: 'mono small' }, mcp ? mcp.url : h('span', { class: 'muted' }, 'stdio / none')),
        h('td', {}, cbtn),
        h('td', {}, favButton(s)));
    }))));
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h1', {}, 'Agents'), h('span', { class: 'muted' }, `${others.length} agents`),
      h('span', { class: 'spacer' }), syncBtn),
    orch.length ? h('div', { class: 'cards' }, orch.map(systemCard)) : null,
    h('p', { class: 'small muted' }, 'Orchestrator registry: ', h('span', { class: 'mono' }, os.url), ' — ',
      os.status.state === 'ok' ? `${os.status.message} (${ago(os.status.at)})`
        : os.status.state === 'never' ? 'not synced yet' : h('span', { class: 'err' }, os.status.message),
      '. Agents registered there appear here automatically; the orchestrator only answers this machine.'),
    [...groups.entries()].map(([g, list]) => h('section', { class: 'card' }, h('h3', {}, g), table(list))),
    h('section', { class: 'card' }, h('h3', {}, 'Using an agent'),
      renderMarkdown(kindOf({ kind: 'mcp-agent' }).guide, { id: '<agent-id>', mcp: '<mcp-endpoint>' })));
}

// ------------------------------------------------------------------ system detail
function sparkline(points) {
  const W = 240, H = 28;
  if (!points || points.length < 2) return null;
  const n = points.length;
  const bw = W / n;
  const svgNS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNS, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('class', 'spark');
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', 'Recent checks');
  points.forEach((p, i) => {
    const r = document.createElementNS(svgNS, 'rect');
    r.setAttribute('x', (i * bw + 0.5).toFixed(1));
    r.setAttribute('y', '2');
    r.setAttribute('width', Math.max(1, bw - 1.5).toFixed(1));
    r.setAttribute('height', String(H - 4));
    r.setAttribute('rx', '1.5');
    r.setAttribute('class', 'b ' + p.state);
    const t = document.createElementNS(svgNS, 'title');
    t.textContent = `${new Date(p.t).toLocaleTimeString()} ${p.state}${p.latencyMs != null ? ' ' + p.latencyMs + ' ms' : ''}`;
    r.append(t);
    svg.append(r);
  });
  return svg;
}

function renderSystem() {
  const id = S.route[1];
  const s = sys(id);
  const main = $('main');
  if (!s) { main.replaceChildren(h('p', { class: 'empty' }, 'No system “' + id + '”. ', h('a', { href: '#/catalog' }, 'Back to the catalog'))); return; }
  const k = kindOf(s);
  const deps = (s.dependsOn || []).map((d) => [sys(d.to), d.label]).filter(([x]) => x);
  const users = usedBy(s.id).map((u) => [u, (u.dependsOn.find((d) => d.to === s.id) || {}).label]);
  const flows = S.catalog.flows.filter((f) => f.steps.some((st) => st.from === s.id || st.to === s.id));
  const vars = placeholderVars(s);

  const healthBox = h('div', { class: 'card', 'data-live': '1' });
  const drawHealth = () => {
    const hh = healthOf(s.id);
    healthBox.replaceChildren(h('div', { class: 'sec-head' }, h('h3', {}, 'Status'), h('span', { class: 'spacer' }),
      h('button', { class: 'small', onclick: async (e) => {
        e.currentTarget.disabled = true;
        try { await api('POST', '/health/check?id=' + enc(s.id)); await refreshHealth(); } catch (err) { fail(err); }
      } }, 'Check now')),
    hh ? [
      h('div', { class: 'row' }, statePill(s), h('span', { class: 'muted small' }, hh.latest.message),
        hh.latest.httpStatus ? h('span', { class: 'chip' }, 'HTTP ' + hh.latest.httpStatus) : null),
      h('div', { class: 'kv' },
        h('span', {}, 'Probed'), h('span', { class: 'mono small' }, hh.latest.url || '—'),
        h('span', {}, 'Checked'), h('span', {}, ago(hh.latest.checkedAt)),
        hh.uptimePct != null ? [h('span', {}, 'Uptime'), h('span', {}, `${hh.uptimePct}% of the last ${hh.samples} checks`)] : null,
        hh.latest.certDaysLeft != null ? [h('span', {}, 'TLS certificate'),
          h('span', { class: hh.certWarn ? 'warn' : '' }, `expires in ${hh.latest.certDaysLeft} days`)] : null),
      sparkline(hh.history)]
      : h('p', { class: 'muted' }, (s.links || []).length || s.healthUrl ? 'Not checked yet.' : 'Add a link or a health URL to monitor it.'));
  };
  healthBox.addEventListener('refresh', drawHealth);
  drawHealth();

  const a = s.access || {};
  const accessCard = h('div', { class: 'card' }, h('h3', {}, 'Access'),
    a.method || a.username || a.credentialRef || a.howTo ? h('div', { class: 'kv' },
      a.method ? [h('span', {}, 'Login'), h('span', {}, { sso: 'Single sign-on (Keycloak)', local: 'Local account', token: 'API / bearer token', certificate: 'Client certificate', none: 'No login' }[a.method] || a.method)] : null,
      a.username ? [h('span', {}, 'User'), h('span', {}, h('span', { class: 'mono' }, a.username), ' ',
        h('button', { class: 'ghost small', onclick: (e) => copyText(a.username, e.currentTarget) }, 'Copy'))] : null,
      a.credentialRef ? [h('span', {}, 'Credentials'), h('span', {}, '🔑 ', a.credentialRef)] : null,
      a.howTo ? [h('span', {}, 'How'), h('span', {}, mdInline(a.howTo))] : null)
      : h('p', { class: 'muted small' }, 'Not documented yet.'),
    h('p', { class: 'hint' }, 'Passwords are never stored here: the credentials line says where to find them.'));

  const relList = (list, empty) => list.length ? h('ul', { class: 'rel' }, list.map(([x, label]) => h('li', {},
    h('a', { href: '#/system/' + enc(x.id) }, kindIcon(x), ' ', x.name), label ? h('span', { class: 'muted small' }, ' — ' + label) : null)))
    : h('p', { class: 'muted small' }, empty);

  main.replaceChildren(
    h('div', { class: 'crumbs small' }, h('a', { href: '#/catalog' }, 'Catalog'), ' / ', catLabel(categoryOf(s))),
    h('div', { class: 'sys-head' }, kindIcon(s, true),
      h('div', { class: 'grow' }, h('h1', {}, s.name, ' ', favButton(s)),
        h('div', { class: 'muted' }, k.label, ' · ', catLabel(categoryOf(s)), s.environment ? ' · ' + s.environment : '',
          s.owner ? ' · owner: ' + s.owner : '', s.source === 'orchestrator' ? ' · synced from the orchestrator' : ''),
        s.description ? h('p', {}, s.description) : null,
        (s.tags || []).length ? h('div', {}, s.tags.map((t) => h('span', { class: 'chip' }, '#' + t))) : null),
      S.session.canEdit ? h('div', { class: 'row' }, h('button', { onclick: () => systemForm(s) }, 'Edit'),
        h('button', { class: 'danger', onclick: () => deleteSystem(s) }, 'Delete')) : null),
    (s.links || []).length ? h('div', { class: 'links' }, (s.links || []).map((l) => h('div', { class: 'linkrow' },
      h('span', { class: 'chip type' }, l.type), h('a', { href: l.url, target: '_blank', rel: 'noopener noreferrer' }, l.label || l.url, ' ↗'),
      h('span', { class: 'mono small muted grow trunc' }, l.url),
      h('button', { class: 'ghost small', onclick: (e) => copyText(l.url, e.currentTarget) }, 'Copy URL'))))
      : h('div', { class: 'card warn-card' }, 'No URL yet. ', S.session.canEdit ? h('button', { class: 'small', onclick: () => systemForm(s) }, 'Add one') : 'Ask an admin to add it.'),
    h('div', { class: 'grid2' },
      h('div', {},
        s.usage ? h('div', { class: 'card' }, h('h3', {}, 'In this lab'), renderMarkdown(s.usage, vars)) : null,
        h('div', { class: 'card guide' }, h('h3', {}, 'How to use ', k.label), renderMarkdown(k.guide, vars)),
        s.notes ? h('div', { class: 'card' }, h('h3', {}, 'Notes'), renderMarkdown(s.notes, vars)) : null),
      h('div', {},
        healthBox, accessCard,
        h('div', { class: 'card' }, h('h3', {}, 'Depends on'), relList(deps, 'Nothing recorded.'),
          h('h3', { style: 'margin-top:14px' }, 'Used by'), relList(users, 'Nothing recorded.')),
        flows.length ? h('div', { class: 'card' }, h('h3', {}, 'Part of'), h('ul', { class: 'rel' }, flows.map((f) => h('li', {},
          h('a', { href: '#/architecture/' + enc(f.id) }, f.name), h('span', { class: 'muted small' }, ` — ${f.steps.filter((st) => st.from === s.id || st.to === s.id).length} step(s)`)))))
          : null,
        h('div', { class: 'card small muted' }, 'Id ', h('span', { class: 'mono' }, s.id), ' · source ', s.source || 'manual'))));
}

async function deleteSystem(s) {
  const used = usedBy(s.id);
  const msg = `Delete ${s.name}?` + (used.length ? ` ${used.length} system(s) depend on it; those links are removed too.` : '')
    + (s.source === 'orchestrator' ? ' It comes back on the next orchestrator sync unless you remove it there.' : '');
  if (!await confirmBox('Delete system', msg, 'Delete')) return;
  try { await api('DELETE', `/systems/${enc(s.id)}?version=${S.catalog.version}`); toast('Deleted'); await reload(); go('catalog'); }
  catch (e) { fail(e); }
}

// ------------------------------------------------------------------ dialogs
function openDialog(title, body, foot, wide) {
  const d = $('dlg');
  d.className = wide ? 'wide' : '';
  d.replaceChildren(
    h('div', { class: 'dlg-head' }, h('h2', {}, title), h('button', { class: 'ghost icon', 'aria-label': 'Close', onclick: () => d.close() }, '✕')),
    h('div', { class: 'dlg-body' }, body),
    foot ? h('div', { class: 'dlg-foot' }, foot) : null);
  if (!d.open) d.showModal();
  return d;
}
function closeDialog() { if ($('dlg').open) $('dlg').close(); }
function confirmBox(title, message, okLabel) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); closeDialog(); } };
    const d = openDialog(title, h('p', {}, message), [h('button', { onclick: () => finish(false) }, 'Cancel'),
      h('button', { class: 'primary danger', onclick: () => finish(true) }, okLabel || 'OK')]);
    d.addEventListener('close', () => finish(false), { once: true });
  });
}
const field = (label, input, hint) => h('div', { class: 'field' }, h('label', {}, label, input), hint ? h('div', { class: 'hint' }, hint) : null);

/* Repeating rows (links, dependencies, flow steps). make(row) returns [elements, read()] */
function rowsEditor(items, make, addLabel) {
  const box = h('div', { class: 'rows' });
  const rows = [];
  const add = (it) => {
    const [els, read] = make(it || {});
    const row = h('div', { class: 'erow' }, els, h('button', { class: 'ghost icon', type: 'button', 'aria-label': 'Remove row',
      onclick: () => { row.remove(); rows.splice(rows.indexOf(entry), 1); } }, '✕'));
    const entry = { read };
    rows.push(entry);
    box.append(row);
  };
  (items || []).forEach(add);
  const wrap = h('div', {}, box, h('button', { class: 'small', type: 'button', onclick: () => add() }, addLabel));
  wrap.read = () => rows.map((r) => r.read()).filter(Boolean);
  return wrap;
}

function systemForm(existing) {
  const x = existing || { links: [], dependsOn: [], tags: [], access: {} };
  const ac = x.access || {};
  const kindSel = h('select', {}, S.session.categories.map((c) => h('optgroup', { label: c.label },
    S.session.kinds.filter((k) => k.category === c.id).map((k) => h('option', { value: k.id }, k.label)))));
  kindSel.value = x.kind || 'generic';
  const catSel = h('select', {}, h('option', { value: '' }, 'From the kind'), S.session.categories.map((c) => h('option', { value: c.id }, c.label)));
  catSel.value = x.category || '';
  const f = {
    name: h('input', { type: 'text', value: x.name || '', required: true, placeholder: 'Harbor' }),
    id: h('input', { type: 'text', value: x.id || '', placeholder: 'from the name', pattern: '[a-z0-9][a-z0-9-]*' }),
    env: h('input', { type: 'text', value: x.environment || 'lab', list: 'envs' }),
    owner: h('input', { type: 'text', value: x.owner || '', placeholder: 'platform team' }),
    desc: h('input', { type: 'text', value: x.description || '', placeholder: 'One line: what it is for' }),
    tags: h('input', { type: 'text', value: (x.tags || []).join(', '), placeholder: 'registry, prod' }),
    health: h('input', { type: 'url', value: x.healthUrl || '', placeholder: 'defaults to the first link (+ the kind’s health path)' }),
    insecure: h('input', { type: 'checkbox', checked: !!x.insecureTls }),
    method: h('select', {}, h('option', { value: '' }, '—'), S.session.accessMethods.map((m) => h('option', { value: m }, m))),
    user: h('input', { type: 'text', value: ac.username || '', placeholder: 'admin, or “your Keycloak account”' }),
    cred: h('input', { type: 'text', value: ac.credentialRef || '', placeholder: 'Password manager: Lab / Harbor admin' }),
    howTo: h('input', { type: 'text', value: ac.howTo || '', placeholder: 'Ask #platform for a project' }),
    usage: h('textarea', { rows: 6, placeholder: 'Markdown. Instance-specific steps, shown above the built-in guide.' }, x.usage || ''),
    notes: h('textarea', { rows: 3, placeholder: 'Markdown' }, x.notes || ''),
  };
  f.method.value = ac.method || '';
  const links = rowsEditor(x.links, (l) => {
    const label = h('input', { type: 'text', value: l.label || '', placeholder: 'Label', 'aria-label': 'Link label' });
    const url = h('input', { type: 'url', value: l.url || '', placeholder: 'https://…', 'aria-label': 'URL', class: 'grow' });
    const type = h('select', { 'aria-label': 'Link type' }, S.session.linkTypes.map((t) => h('option', { value: t }, t)));
    type.value = l.type || 'ui';
    return [[label, url, type], () => url.value.trim() ? { label: label.value.trim() || null, url: url.value.trim(), type: type.value } : null];
  }, '+ Link');
  const others = S.catalog.systems.filter((s) => s.id !== x.id).sort((a, b) => a.name.localeCompare(b.name));
  const deps = rowsEditor(x.dependsOn, (d) => {
    const to = h('select', { 'aria-label': 'Depends on' }, h('option', { value: '' }, 'Choose a system…'), others.map((s) => h('option', { value: s.id }, s.name)));
    to.value = d.to || '';
    const label = h('input', { type: 'text', value: d.label || '', placeholder: 'how: pulls images, OIDC login…', 'aria-label': 'How', class: 'grow' });
    return [[to, label], () => to.value ? { to: to.value, label: label.value.trim() || null } : null];
  }, '+ Dependency');
  const hint = h('div', { class: 'hint' });
  const showHint = () => {
    const k = S.session.kinds.find((kk) => kk.id === kindSel.value);
    hint.textContent = k ? k.summary + (k.linkHints ? ' Typical links: ' + Object.entries(k.linkHints).map(([t, l]) => `${l} (${t})`).join(', ') + '.' : '') : '';
  };
  kindSel.addEventListener('change', showHint);
  showHint();
  const err = h('p', { class: 'err', role: 'alert' });
  const saveBtn = h('button', { class: 'primary', onclick: async () => {
    const body = {
      id: f.id.value.trim() || null, name: f.name.value.trim(), kind: kindSel.value, category: catSel.value || null,
      environment: f.env.value.trim() || null, description: f.desc.value.trim() || null, owner: f.owner.value.trim() || null,
      tags: f.tags.value.split(',').map((t) => t.trim()).filter(Boolean), links: links.read(), healthUrl: f.health.value.trim() || null,
      insecureTls: f.insecure.checked, dependsOn: deps.read(),
      access: { method: f.method.value || null, username: f.user.value.trim() || null, credentialRef: f.cred.value.trim() || null, howTo: f.howTo.value.trim() || null },
      usage: f.usage.value.trim() || null, notes: f.notes.value.trim() || null, source: x.source || 'manual',
    };
    if (!body.name) { err.textContent = 'Name is required.'; return; }
    saveBtn.disabled = true;
    try {
      const saved = existing
        ? await api('PUT', '/systems/' + enc(existing.id), { system: body, version: S.catalog.version })
        : await api('POST', '/systems', { system: body, version: S.catalog.version });
      closeDialog(); toast('Saved'); await reload(); go('system', saved.id);
    } catch (e) { err.textContent = e.message; } finally { saveBtn.disabled = false; }
  } }, existing ? 'Save' : 'Add system');
  openDialog(existing ? 'Edit ' + x.name : 'Add a system', h('div', { class: 'form' },
    h('datalist', { id: 'envs' }, ['lab', 'dev', 'staging', 'prod'].map((e) => h('option', { value: e }))),
    h('div', { class: 'g2' }, field('Name', f.name), field('Id', f.id, 'Lowercase, used in URLs and by agents.')),
    h('div', { class: 'g2' }, field('Kind', kindSel), field('Area', catSel)),
    hint,
    field('Description', f.desc),
    h('div', { class: 'g3' }, field('Environment', f.env), field('Owner', f.owner), field('Tags', f.tags)),
    h('fieldset', {}, h('legend', {}, 'Links'), links),
    h('div', { class: 'g2' }, field('Health URL', f.health), h('label', { class: 'check' }, f.insecure, ' Self-signed certificate (don’t verify TLS when probing)')),
    h('fieldset', {}, h('legend', {}, 'Access — no passwords, only where to find them'),
      h('div', { class: 'g2' }, field('Login', f.method), field('User', f.user)),
      field('Credentials are in', f.cred), field('How to get access', f.howTo)),
    h('fieldset', {}, h('legend', {}, 'Depends on'), deps),
    field('Usage in this lab', f.usage), field('Notes', f.notes), err),
  [h('button', { onclick: closeDialog }, 'Cancel'), saveBtn], true);
  f.name.focus();
}

// ------------------------------------------------------------------ settings
async function renderSettings() {
  const main = $('main');
  const p = S.catalog.problems || [];
  const os = S.session.orchestrator;
  const importTa = h('textarea', { rows: 8, placeholder: 'Paste a catalog.yml here, or pick a file', class: 'mono' });
  const file = h('input', { type: 'file', accept: '.yml,.yaml', onchange: async () => { if (file.files[0]) importTa.value = await file.files[0].text(); } });
  const mode = h('select', {}, h('option', { value: 'merge' }, 'Merge (add or replace by id)'), h('option', { value: 'replace' }, 'Replace the whole catalog'));
  const auditBox = h('pre', { class: 'audit' }, 'Loading…');
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h1', {}, 'Settings')),
    h('div', { class: 'grid2' },
      h('div', {},
        h('section', { class: 'card' }, h('h3', {}, 'Catalog file'),
          h('p', {}, h('span', { class: 'mono small' }, S.session.catalogFile)),
          h('p', { class: 'hint' }, 'Edits here are saved to this YAML file; hand edits to it are picked up automatically. Keep it in Git to review changes.'),
          p.length ? h('div', { class: 'err' }, h('strong', {}, `${p.length} problem(s):`), h('ul', {}, p.map((x) => h('li', {}, x)))) : h('p', { class: 'okc' }, 'No problems.'),
          h('div', { class: 'row' }, h('button', { onclick: exportCatalog }, 'Download catalog.yml'))),
        h('section', { class: 'card' }, h('h3', {}, 'Import'),
          !S.session.canEdit ? h('p', { class: 'muted' }, 'Read-only from this machine.') : [
            file, importTa, h('div', { class: 'row' }, mode, h('span', { class: 'spacer' }),
              h('button', { class: 'primary', onclick: async () => {
                if (!importTa.value.trim()) return;
                if (mode.value === 'replace' && !await confirmBox('Replace catalog', 'Replace every system and flow with the imported file?', 'Replace')) return;
                try { const r = await api('POST', '/import?mode=' + mode.value, undefined, importTa.value); toast(`Imported ${r.systems} systems, ${r.flows} flows`); await reload(); }
                catch (e) { fail(e); }
              } }, 'Import'))]),
        h('section', { class: 'card' }, h('h3', {}, 'Orchestrator'),
          h('div', { class: 'kv' }, h('span', {}, 'URL'), h('span', { class: 'mono' }, os.url),
            h('span', {}, 'Last sync'), h('span', {}, os.status.at ? ago(os.status.at) : 'never'),
            h('span', {}, 'Result'), h('span', { class: os.status.state === 'error' ? 'err' : '' }, os.status.message)),
          h('p', { class: 'hint' }, 'Set commandcenter.orchestrator.url in ~/.command-center/config.yml if it runs elsewhere.'))),
      h('div', {},
        h('section', { class: 'card' }, h('h3', {}, 'Health checks'),
          h('p', {}, `Every ${S.session.health.intervalS} s; certificates expiring within ${S.session.health.certWarnDays} days are flagged.`),
          h('p', { class: 'hint' }, 'A system is up when it answers at all, including “login required” (401/403). Down means no answer, a timeout or a 5xx.'),
          h('button', { onclick: async (e) => { e.currentTarget.disabled = true; try { S.catalog.health = await api('POST', '/health/check'); toast('Checked'); } catch (err) { fail(err); } finally { render(); } } }, 'Check everything now')),
        h('section', { class: 'card' }, h('h3', {}, 'Audit log'), auditBox),
        h('section', { class: 'card small muted' }, `Command Center ${S.session.version}. `,
          S.session.canEdit ? 'You can edit from this machine.' : 'Read-only: changes are only accepted on the command center’s own machine.'))));
  try { const lines = await api('GET', '/audit?lines=200'); auditBox.textContent = lines.length ? lines.slice().reverse().join('\n') : 'No changes yet.'; }
  catch (e) { auditBox.textContent = e.message; }
}
async function exportCatalog() {
  try {
    const yaml = await api('GET', '/export');
    const url = URL.createObjectURL(new Blob([yaml], { type: 'application/yaml' }));
    const a = h('a', { href: url, download: 'catalog.yml' });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  } catch (e) { fail(e); }
}

// ------------------------------------------------------------------ boot
document.addEventListener('keydown', (e) => {
  if (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) && !$('dlg').open) {
    e.preventDefault();
    if (S.route[0] !== 'home' && S.route[0] !== 'catalog') go('home');
    setTimeout(() => { const i = document.querySelector('.hero-search, #cat-q'); if (i) i.focus(); }, 30);
  }
});
window.addEventListener('hashchange', render);
(async () => {
  try {
    await loadAll();
    render();
    if (!Object.keys(S.catalog.health).length) setTimeout(refreshHealth, 3500);
  } catch (e) {
    $('main').replaceChildren(h('div', { class: 'card' }, h('h2', {}, 'Cannot load the command center'), h('p', { class: 'err' }, e.message)));
  }
})();
