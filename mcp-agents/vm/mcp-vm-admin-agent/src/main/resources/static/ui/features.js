'use strict';
/* Shell (web terminal), Services, Packages, Logs, metrics charts, Sync, Schedules and Alerts.
   Uses the helpers and state from app.js (h, api, S, toast, fail, withConfirm, confirmBox, ...). */

S.cleanups = [];
function onLeave(fn) { S.cleanups.push(fn); }

const SVGNS = 'http://www.w3.org/2000/svg';
function sv(tag, attrs, ...kids) {
  const el = document.createElementNS(SVGNS, tag);
  for (const [k, v] of Object.entries(attrs || {})) if (v != null) el.setAttribute(k, v);
  for (const kid of kids.flat()) if (kid != null) el.append(kid instanceof Node ? kid : String(kid));
  return el;
}
function hasFeature(rec, f) { return rec && rec.status && (rec.status.features || []).includes(f); }
function needUpgrade(body, rec, what, version) {
  body.replaceChildren(h('div', { class: 'card' }, h('h3', {}, `${what} needs agent ${version} or newer`),
    h('p', {}, `${rec.host.name} runs agent ${rec.status.version || 'unknown'}.`),
    !rec.host.readOnly && outdated(rec) ? h('button', { class: 'primary', onclick: () => doUpgrade(rec.host.name) }, 'Upgrade agent') : null));
}
function startedJob(host, r) {
  toast(`Started job ${r.job_id}; follow it in the Jobs tab`);
  if (S.view === 'host' && S.host === host) { S.tab = 'jobs'; clearTimers(); renderHost(); }
}

// ------------------------------------------------------------------ overview charts + host alerts
function chart(samples, key, label, opts) {
  const W = 320, H = 80, P = 4;
  const pts = samples.filter((x) => x[key] != null).map((x) => [x.t, opts.map ? opts.map(x) : x[key]]);
  const last = pts.length ? pts[pts.length - 1][1] : null;
  const box = h('div', { class: 'stat chart' },
    h('div', { class: 'row' }, h('span', { class: 'k', style: 'flex:1' }, label),
      h('strong', {}, last == null ? '—' : (Math.round(last * 10) / 10) + (opts.unit || ''))));
  if (pts.length < 2) { box.append(h('div', { class: 's' }, 'Collecting data (one sample every few minutes)…')); return box; }
  const t0 = pts[0][0], t1 = pts[pts.length - 1][0];
  const vmax = Math.max(opts.max || 0, ...pts.map((p) => p[1])) || 1;
  const x = (t) => P + (W - 2 * P) * (t - t0) / Math.max(1, t1 - t0);
  const y = (v) => H - P - (H - 2 * P) * v / vmax;
  const d = pts.map((p, i) => (i ? 'L' : 'M') + x(p[0]).toFixed(1) + ' ' + y(p[1]).toFixed(1)).join(' ');
  const svg = sv('svg', { viewBox: `0 0 ${W} ${H}`, class: 'spark', role: 'img', 'aria-label': `${label} over time, latest ${last}` },
    opts.warn ? sv('line', { x1: P, x2: W - P, y1: y(opts.warn), y2: y(opts.warn), class: 'warnline' }) : null,
    sv('path', { d: d + ` L${x(t1).toFixed(1)} ${H - P} L${x(t0).toFixed(1)} ${H - P} Z`, class: 'area' }),
    sv('path', { d, class: 'line' }));
  box.append(svg, h('div', { class: 's' }, `${new Date(t0).toLocaleString()} → now · max ${Math.round(Math.max(...pts.map((p) => p[1])) * 10) / 10}${opts.unit || ''}`));
  return box;
}

async function overviewExtras(body, rec) {
  const name = rec.host.name;
  const holder = h('div');
  const range = h('select', { 'aria-label': 'Chart range', style: 'width:auto' },
    h('option', { value: '24' }, 'Last 24 hours'), h('option', { value: '168' }, 'Last 7 days'), h('option', { value: '6' }, 'Last 6 hours'));
  range.value = String(load('range', 24));
  const draw = async () => {
    save('range', Number(range.value));
    try {
      const m = await api('GET', `${hp(name)}/metrics?hours=${range.value}`);
      holder.replaceChildren(h('div', { class: 'grid g3' },
        chart(m, 'load1', 'Load per CPU', { map: (x) => x.load1 / (x.cpus || 1), warn: 1 }),
        chart(m, 'memPct', 'Memory used', { unit: '%', max: 100, warn: 90 }),
        chart(m, 'diskPct', 'Root disk used', { unit: '%', max: 100, warn: 90 })));
    } catch (e) { holder.replaceChildren(h('p', { class: 'err' }, e.message)); }
  };
  range.onchange = draw;
  const alertsBox = h('div');
  body.append(h('div', { class: 'card', style: 'margin-top:14px' },
    h('div', { class: 'row', style: 'margin-bottom:10px' }, h('h3', { style: 'margin:0;flex:1' }, 'History'), range), holder), alertsBox);
  draw();
  try {
    const a = await api('GET', '/alerts');
    const mine = a.active.filter((x) => x.host === name);
    if (mine.length) {
      alertsBox.replaceChildren(h('div', { class: 'warnbox' }, h('strong', {}, `${mine.length} active alert${mine.length > 1 ? 's' : ''}`),
        h('ul', { style: 'margin:6px 0 0;padding-left:18px' }, mine.map((x) => h('li', {}, `${x.kind}: ${x.message} (since ${ago(x.since)})`)))));
    }
  } catch { /* alerts are optional here */ }
}

// ------------------------------------------------------------------ shell (web terminal)
function b64FromBytes(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
function bytesFromB64(b64) {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

function tabShell(body, rec) {
  const name = rec.host.name;
  if (stateOf(rec) !== 'online') { body.replaceChildren(h('p', { class: 'muted' }, 'The host is not online.')); return; }
  if (rec.host.readOnly) { body.replaceChildren(h('p', { class: 'muted' }, 'This host is read-only: the interactive shell is disabled.')); return; }
  if (!hasFeature(rec, 'pty')) { needUpgrade(body, rec, 'The interactive shell', '2.2.0'); return; }
  if (!window.Terminal) { body.replaceChildren(h('p', { class: 'err' }, 'The terminal library did not load.')); return; }

  const box = h('div', { class: 'xterm-box' });
  const status = h('span', { class: 'muted' }, 'Connecting…');
  const again = h('button', { class: 'small', hidden: true, onclick: () => { clearTimers(); renderHost(); } }, 'New session');
  body.replaceChildren(
    h('div', { class: 'row', style: 'margin-bottom:8px' }, status, h('span', { style: 'flex:1' }), again,
      h('span', { class: 'hint', style: 'margin:0' }, 'Runs as the agent user. Risky-command confirmation does not apply here; keystrokes are not logged, opening and closing are.')),
    box);

  const css = getComputedStyle(document.documentElement);
  const term = new window.Terminal({
    cursorBlink: true, fontSize: 13, scrollback: 5000, convertEol: false,
    fontFamily: 'ui-monospace,SFMono-Regular,Menlo,Consolas,monospace',
    theme: { background: css.getPropertyValue('--code').trim() || '#0b0d11', foreground: '#d9dee5' },
  });
  const fit = window.FitAddon ? new window.FitAddon.FitAddon() : null;
  if (fit) term.loadAddon(fit);
  term.open(box);
  if (fit) fit.fit();
  term.focus();

  let id = null, offset = 0, alive = true, sending = false, pending = '';
  const enc8 = new TextEncoder();
  const flush = async () => {
    if (sending || !pending || !id) return;
    sending = true;
    const chunk = pending; pending = '';
    try { await api('POST', `${hp(name)}/pty/${id}/input`, { b64: b64FromBytes(enc8.encode(chunk)) }); } catch (e) { status.textContent = e.message; }
    sending = false;
    if (pending) flush();
  };
  term.onData((d) => { pending += d; flush(); });
  let rt = null;
  const resize = () => {
    if (!fit || !alive) return;
    fit.fit();
    clearTimeout(rt);
    rt = setTimeout(() => { if (id) api('POST', `${hp(name)}/pty/${id}/resize`, { cols: term.cols, rows: term.rows }).catch(() => {}); }, 250);
  };
  window.addEventListener('resize', resize);
  const end = (msg) => {
    if (!alive) return;
    alive = false;
    status.textContent = msg; again.hidden = false;
    window.removeEventListener('resize', resize);
  };
  onLeave(() => {
    const wasAlive = alive;
    end('closed');
    if (wasAlive && id) api('DELETE', `${hp(name)}/pty/${id}`).catch(() => {});
    term.dispose();
  });

  (async () => {
    try {
      const r = await api('POST', `${hp(name)}/pty`, { cols: term.cols, rows: term.rows, cwd: rec.status.defaultCwd || null });
      id = r.id;
      status.textContent = `Connected to ${name} (session ${id})`;
      flush();
    } catch (e) { end('Could not open a shell: ' + e.message); return; }
    while (alive) {
      try {
        const o = await api('GET', `${hp(name)}/pty/${id}/output?offset=${offset}`);
        if (!alive) break;
        if (o.data) term.write(bytesFromB64(o.data));
        offset = o.offset;
        if (o.closed) { term.write(`\r\n\x1b[2m[process exited${o.exit_code != null ? ' with code ' + o.exit_code : ''}]\x1b[0m\r\n`); end('Session ended'); }
      } catch (e) {
        if (e.status === 404) { end('Session ended'); break; }
        status.textContent = 'Connection problem, retrying: ' + e.message;
        await new Promise((res) => setTimeout(res, 2000));
      }
    }
  })();
}

// ------------------------------------------------------------------ services
function tabServices(body, rec) {
  const name = rec.host.name, ro = rec.host.readOnly;
  if (stateOf(rec) !== 'online') { body.replaceChildren(h('p', { class: 'muted' }, 'The host is not online.')); return; }
  const filter = h('input', { type: 'search', placeholder: 'Filter services', value: load('svcFilter', ''), 'aria-label': 'Filter services' });
  const showAll = h('input', { type: 'checkbox', checked: load('svcAll', false) });
  const list = h('div', {}, h('p', { class: 'muted' }, 'Loading…'));
  let rows = [];
  const act = async (unit, action) => {
    try {
      const r = await withConfirm((confirm) => api('POST', `${hp(name)}/services/${enc(unit)}/${action}`, { confirm }), name);
      if (!r) return;
      if (r.exit_code === 0) toast(`${action} ${unit}: ${r.state}`);
      else openDialog(`${action} ${unit} failed`, h('pre', { class: 'out' }, r.output || '(no output)'), [h('button', { onclick: closeDialog }, 'Close')]);
      refresh();
    } catch (e) { fail(e); }
  };
  const showStatus = async (unit) => {
    openDialog(unit, h('p', { class: 'muted' }, 'Loading…'), null, true);
    try {
      const r = await api('GET', `${hp(name)}/services/${enc(unit)}/status`);
      openDialog(unit, h('pre', { class: 'out', style: 'max-height:65vh' }, r.output), [
        h('button', { onclick: () => { closeDialog(); S.tab = 'logs'; S.logUnit = unit; clearTimers(); renderHost(); } }, 'Open logs'),
        h('button', { onclick: closeDialog }, 'Close')], true);
    } catch (e) { openDialog(unit, h('p', { class: 'err' }, e.message), [h('button', { onclick: closeDialog }, 'Close')]); }
  };
  const paint = () => {
    const q = filter.value.toLowerCase();
    save('svcFilter', filter.value); save('svcAll', showAll.checked);
    const shown = rows.filter((r) => (showAll.checked || r.active !== 'inactive' || r.enabled === 'enabled')
      && (!q || (r.unit + ' ' + r.description).toLowerCase().includes(q)));
    const failed = rows.filter((r) => r.active === 'failed').length;
    list.replaceChildren(
      h('p', { class: 'hint' }, `${shown.length} of ${rows.length} services` + (failed ? ` · ${failed} failed` : '') +
        (ro ? ' · read-only host: status only' : ' · start/stop/enable use sudo -n on the VM')),
      h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, ['Service', 'State', 'Boot', 'Description', ''].map((x) => h('th', {}, x)))),
        h('tbody', {}, shown.map((r) => {
          const running = r.active === 'active';
          return h('tr', {},
            h('td', { class: 'mono' }, h('button', { class: 'ghost', style: 'padding:0', onclick: () => showStatus(r.unit) }, r.unit.replace(/\.service$/, ''))),
            h('td', {}, h('span', { class: 'state ' + (r.active === 'active' ? 'online' : r.active === 'failed' ? 'offline' : 'unknown') }, r.active), h('span', { class: 'muted' }, ' ' + r.sub)),
            h('td', { class: 'muted' }, r.enabled),
            h('td', { class: 'muted' }, r.description),
            h('td', { class: 'act' },
              h('button', { class: 'small ghost', onclick: () => showStatus(r.unit) }, 'Status'),
              !ro ? (running
                ? [h('button', { class: 'small ghost', onclick: () => act(r.unit, 'restart') }, 'Restart'), h('button', { class: 'small ghost danger', onclick: () => act(r.unit, 'stop') }, 'Stop')]
                : h('button', { class: 'small ghost', onclick: () => act(r.unit, 'start') }, 'Start')) : null,
              !ro && r.enabled === 'disabled' ? h('button', { class: 'small ghost', onclick: () => act(r.unit, 'enable') }, 'Enable') : null,
              !ro && r.enabled === 'enabled' ? h('button', { class: 'small ghost danger', onclick: () => act(r.unit, 'disable') }, 'Disable') : null));
        })))));
  };
  async function refresh() {
    try { rows = await api('GET', `${hp(name)}/services`); paint(); } catch (e) { list.replaceChildren(h('p', { class: 'err' }, e.message)); }
  }
  filter.addEventListener('input', paint);
  showAll.addEventListener('change', paint);
  body.replaceChildren(h('div', { class: 'card' },
    h('div', { class: 'row', style: 'margin-bottom:10px' }, h('div', { class: 'grow' }, filter),
      h('label', { class: 'checks', style: 'margin:0' }, h('span', { class: 'row', style: 'gap:6px;color:var(--fg)' }, showAll, 'Show inactive')),
      h('button', { onclick: refresh }, 'Refresh')),
    list));
  refresh();
}

// ------------------------------------------------------------------ packages
function tabPackages(body, rec) {
  const name = rec.host.name, ro = rec.host.readOnly;
  if (stateOf(rec) !== 'online') { body.replaceChildren(h('p', { class: 'muted' }, 'The host is not online.')); return; }
  const up = h('div', {}, h('p', { class: 'muted' }, 'Checking for updates…'));
  const found = h('div');
  const q = h('input', { type: 'search', placeholder: 'Search installed packages, e.g. nginx', 'aria-label': 'Search installed packages' });
  let manager = null;
  const action = async (act, pkg) => {
    try {
      if (act === 'upgrade-all' && !await confirmBox('Upgrade all packages', `Upgrade every package on ${name}? Services may restart.`, 'Upgrade all', name)) return;
      const r = await api('POST', `${hp(name)}/packages/action`, { manager, action: act, pkg: pkg || null, confirm: act === 'upgrade-all' });
      startedJob(name, r);
    } catch (e) { fail(e); }
  };
  async function refresh() {
    up.replaceChildren(h('p', { class: 'muted' }, 'Checking for updates…'));
    try {
      const r = await api('GET', `${hp(name)}/packages/upgradable`);
      manager = r.manager;
      if (manager === 'none') { up.replaceChildren(h('p', { class: 'muted' }, 'No apt, dnf or yum on this host.')); return; }
      up.replaceChildren(
        h('div', { class: 'row', style: 'margin-bottom:8px' }, h('strong', { style: 'flex:1' }, `${r.packages.length} update${r.packages.length === 1 ? '' : 's'} available (${manager})`),
          !ro ? h('button', { onclick: () => action('refresh') }, 'Refresh package index') : null,
          !ro && r.packages.length ? h('button', { class: 'primary', onclick: () => action('upgrade-all') }, 'Upgrade all') : null),
        r.packages.length ? h('div', { class: 'table-wrap' }, h('table', {},
          h('thead', {}, h('tr', {}, ['Package', 'Installed', 'Available', 'Repository', ''].map((x) => h('th', {}, x)))),
          h('tbody', {}, r.packages.map((p) => h('tr', {}, h('td', { class: 'mono' }, p.name), h('td', { class: 'mono muted' }, p.current), h('td', { class: 'mono' }, p.version),
            h('td', { class: 'muted' }, p.repo), h('td', { class: 'act' }, !ro ? h('button', { class: 'small ghost', onclick: () => action('upgrade', p.name) }, 'Upgrade') : null))))))
          : h('p', { class: 'muted' }, 'Everything is up to date (as of the last index refresh).'));
    } catch (e) { up.replaceChildren(h('p', { class: 'err' }, e.message)); }
  }
  const search = async () => {
    const v = q.value.trim();
    if (!v) { found.replaceChildren(); return; }
    found.replaceChildren(h('p', { class: 'muted' }, 'Searching…'));
    try {
      const r = await api('GET', `${hp(name)}/packages/installed?q=${enc(v)}`);
      found.replaceChildren(r.length ? h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, ['Package', 'Version', 'Status'].map((x) => h('th', {}, x)))),
        h('tbody', {}, r.map((p) => h('tr', {}, h('td', { class: 'mono' }, p.name), h('td', { class: 'mono' }, p.version), h('td', { class: 'muted' }, p.status))))))
        : h('p', { class: 'muted' }, 'No installed package matches.'));
    } catch (e) { found.replaceChildren(h('p', { class: 'err' }, e.message)); }
  };
  q.addEventListener('keydown', (e) => { if (e.key === 'Enter') search(); });
  body.replaceChildren(
    h('div', { class: 'card' }, h('h3', {}, 'Updates'), up,
      h('p', { class: 'hint' }, 'Package changes run as background jobs with sudo -n; the agent user needs passwordless sudo for the package manager.')),
    h('div', { class: 'card' }, h('h3', {}, 'Installed packages'),
      h('div', { class: 'row', style: 'margin-bottom:8px' }, h('div', { class: 'grow' }, q), h('button', { onclick: search }, 'Search')), found));
  refresh();
}

// ------------------------------------------------------------------ logs
function tabLogs(body, rec) {
  const name = rec.host.name;
  if (stateOf(rec) !== 'online') { body.replaceChildren(h('p', { class: 'muted' }, 'The host is not online.')); return; }
  const mode = h('select', { style: 'width:auto', 'aria-label': 'Log source' }, h('option', { value: 'journal' }, 'systemd journal'), h('option', { value: 'file' }, 'File'));
  const unit = h('input', { type: 'text', class: 'mono', placeholder: 'unit (empty = everything), e.g. nginx', value: S.logUnit || '', 'aria-label': 'Unit' });
  const prio = h('select', { style: 'width:auto', 'aria-label': 'Priority' }, h('option', { value: '' }, 'all priorities'),
    ['err', 'warning', 'notice', 'info'].map((p) => h('option', { value: p }, p + ' and worse')));
  const path = h('input', { type: 'text', class: 'mono', placeholder: '/var/log/syslog', value: load('logPath.' + name, '/var/log/syslog'), 'aria-label': 'File path' });
  const follow = h('input', { type: 'checkbox', checked: true });
  const pre = h('pre', { class: 'term', style: 'max-height:66vh;min-height:50vh' });
  const state = h('span', { class: 'muted' });
  S.logUnit = null;
  let cursor = '', offset = -1, buf = [], timer = null, busy = false;
  const append = (text) => {
    const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;
    buf.push(...text);
    if (buf.length > 5000) buf = buf.slice(-5000);
    pre.textContent = buf.join('\n');
    if (atBottom) pre.scrollTop = pre.scrollHeight;
  };
  const poll = async () => {
    if (busy) return;
    busy = true;
    try {
      if (mode.value === 'journal') {
        const r = await api('GET', `${hp(name)}/logs/journal?unit=${enc(unit.value.trim())}&priority=${enc(prio.value)}&cursor=${enc(cursor)}&lines=300`);
        cursor = r.cursor || cursor;
        if (r.lines.length) append(r.lines);
      } else {
        const r = await api('GET', `${hp(name)}/logs/file?path=${enc(path.value.trim())}&offset=${offset}`);
        if (r.reset && offset >= 0) append(['--- file was truncated or rotated ---']);
        offset = r.offset;
        if (r.data) append(r.data.replace(/\n$/, '').split('\n'));
      }
      state.textContent = (follow.checked ? 'Following, refreshes every 3s' : 'Paused') + ` · ${buf.length} lines`;
    } catch (e) { state.textContent = e.message; state.className = 'err'; follow.checked = false; } finally { busy = false; }
  };
  const restart = () => {
    cursor = ''; offset = -1; buf = []; pre.textContent = ''; state.className = 'muted';
    unitRow.hidden = mode.value !== 'journal'; fileRow.hidden = mode.value !== 'file';
    if (mode.value === 'file') save('logPath.' + name, path.value.trim());
    poll();
  };
  const unitRow = h('div', { class: 'row grow' }, h('div', { class: 'grow' }, unit), prio);
  const fileRow = h('div', { class: 'row grow', hidden: true }, h('div', { class: 'grow' }, path));
  [unit, path].forEach((i) => i.addEventListener('keydown', (e) => { if (e.key === 'Enter') restart(); }));
  mode.onchange = restart; prio.onchange = restart;
  timer = setInterval(() => { if (follow.checked) poll(); }, 3000);
  S.timers.push(timer);
  body.replaceChildren(h('div', { class: 'card' },
    h('div', { class: 'row', style: 'margin-bottom:10px' }, mode, unitRow, fileRow, h('button', { class: 'primary', onclick: restart }, 'Show')),
    h('div', { class: 'row', style: 'margin-bottom:8px' }, h('label', { class: 'checks', style: 'margin:0' }, h('span', { class: 'row', style: 'gap:6px;color:var(--fg)' }, follow, 'Follow')),
      state, h('span', { style: 'flex:1' }), h('button', { class: 'ghost', onclick: () => { buf = []; pre.textContent = ''; } }, 'Clear')),
    pre));
  restart();
}

// ------------------------------------------------------------------ sync (push a file with diff)
function hostChecklist(filterFn) {
  const boxes = {};
  const el = h('div', { class: 'checks', style: 'flex-direction:column;gap:6px;max-height:240px;overflow:auto' }, S.fleet.hosts.map((r) => {
    const ok = filterFn(r);
    boxes[r.host.name] = h('input', { type: 'checkbox', disabled: !ok });
    return h('label', {}, boxes[r.host.name], h('span', { class: 'dot ' + stateOf(r) }), r.host.name,
      r.host.group ? h('span', { class: 'chip' }, r.host.group) : null, r.host.readOnly ? h('span', { class: 'chip ro' }, 'read-only') : null);
  }));
  const groups = [...new Set(S.fleet.hosts.map((r) => r.host.group).filter(Boolean))];
  const pick = (pred) => S.fleet.hosts.forEach((r) => { const b = boxes[r.host.name]; if (!b.disabled) b.checked = pred(r); });
  const quick = h('div', { class: 'row', style: 'margin-bottom:8px' },
    h('button', { class: 'small', onclick: () => pick(() => true) }, 'All'), h('button', { class: 'small', onclick: () => pick(() => false) }, 'None'),
    groups.map((g) => h('button', { class: 'small', onclick: () => pick((r) => r.host.group === g) }, 'Group ' + g)));
  return { el: h('div', {}, quick, el), chosen: () => Object.keys(boxes).filter((n) => boxes[n].checked), boxes };
}
function diffView(text) {
  return h('pre', { class: 'out diff' }, (text || '').split('\n').map((l) => h('span', {
    class: l.startsWith('+++') || l.startsWith('---') ? 'dh' : l.startsWith('@@') ? 'dc' : l.startsWith('+') ? 'da' : l.startsWith('-') ? 'dd' : '',
  }, l + '\n')));
}

function renderSync() {
  const main = $('main');
  const hosts = hostChecklist((r) => stateOf(r) === 'online' && hasFeature(r, 'fs'));
  const path = h('input', { type: 'text', class: 'mono', placeholder: '/etc/nginx/conf.d/app.conf', value: load('syncPath', '') });
  const content = h('textarea', { rows: 14, class: 'mono', placeholder: 'File content to push', spellcheck: 'false' });
  const fromHost = h('select', { style: 'width:auto', 'aria-label': 'Load from host' }, h('option', { value: '' }, 'Load current file from…'),
    S.fleet.hosts.filter((r) => hasFeature(r, 'fs')).map((r) => h('option', { value: r.host.name }, r.host.name)));
  const backup = h('input', { type: 'checkbox', checked: true });
  const results = h('div');
  fromHost.onchange = async () => {
    if (!fromHost.value || !path.value.trim()) { if (!path.value.trim()) toast('Type the path first', true); fromHost.value = ''; return; }
    try {
      const r = await api('GET', `${hp(fromHost.value)}/fs/read?path=${enc(path.value.trim())}`);
      if (r.binary || r.truncated) throw new Error('that file is binary or too large to sync');
      content.value = r.content; toast('Loaded from ' + fromHost.value);
    } catch (e) { fail(e); }
    fromHost.value = '';
  };
  let last = null;
  const preview = async () => {
    const chosen = hosts.chosen();
    if (!chosen.length || !path.value.trim()) { toast('Pick hosts and a path', true); return; }
    save('syncPath', path.value.trim());
    results.replaceChildren(h('p', { class: 'muted' }, 'Comparing…'));
    try {
      last = { path: path.value.trim(), content: content.value, hosts: chosen };
      const r = await api('POST', '/sync/preview', last);
      const writable = Object.entries(r).filter(([, x]) => (x.status === 'new' || x.status === 'changed') && !x.readOnly).map(([n]) => n);
      const apply = h('button', { class: 'primary', disabled: !writable.length, onclick: async () => {
        if (content.value !== last.content || path.value.trim() !== last.path) { toast('The content changed since the preview; preview again', true); return; }
        if (!await confirmBox('Push file', `Write ${last.path} on ${writable.length} host(s): ${writable.join(', ')}?` + (backup.checked ? ' Existing files are kept as .bak copies.' : ''), 'Write')) return;
        apply.disabled = true;
        try {
          const w = await api('POST', '/sync/apply', { ...last, hosts: writable, backup: backup.checked });
          const bad = Object.entries(w).filter(([, x]) => x.error);
          toast(bad.length ? `${bad.length} host(s) failed: ${bad.map(([n, x]) => n + ': ' + x.error).join('; ')}` : `Written on ${writable.length} host(s)`, !!bad.length);
          preview();
        } catch (e) { fail(e); apply.disabled = false; }
      } }, `Write to ${writable.length} host(s)`);
      results.replaceChildren(
        h('div', { class: 'row', style: 'margin:4px 0 10px' }, h('strong', { style: 'flex:1' },
          Object.values(r).filter((x) => x.status === 'same').length + ' already match · ' + writable.length + ' to write'), apply),
        Object.entries(r).map(([n, x]) => h('details', { class: 'res', open: x.status === 'changed' || x.status === 'new' || !!x.error },
          h('summary', {}, h('span', { class: 'dot ' + (x.error ? 'offline' : x.status === 'same' ? 'online' : 'unauthorized') }), h('strong', {}, n),
            h('span', { class: 'muted' }, x.error ? x.error : x.status === 'same' ? 'identical' : x.status === 'new' ? 'file does not exist yet' : 'differs'),
            x.readOnly ? h('span', { class: 'chip ro' }, 'read-only, will be skipped') : null),
          x.diff ? h('div', {}, diffView(x.diff)) : null)));
    } catch (e) { results.replaceChildren(h('p', { class: 'err' }, e.message)); }
  };
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Sync a file'), h('span', { class: 'meta' }, 'Push one file to many hosts, with a diff per host first')),
    h('div', { class: 'grid g2' },
      h('div', { class: 'card' }, h('h3', {}, 'Hosts'), hosts.el, h('p', { class: 'hint' }, 'Needs agent 2.1.0+; offline hosts cannot be picked.')),
      h('div', { class: 'card' }, h('h3', {}, 'File'),
        h('div', { class: 'field' }, h('label', {}, 'Path on the hosts'), path),
        h('div', { class: 'row', style: 'margin-bottom:8px' }, fromHost),
        content,
        h('div', { class: 'row', style: 'margin-top:10px' },
          h('label', { class: 'checks', style: 'margin:0' }, h('span', { class: 'row', style: 'gap:6px;color:var(--fg)' }, backup, 'Keep .bak copies')),
          h('span', { style: 'flex:1' }), h('button', { class: 'primary', onclick: preview }, 'Preview changes')))),
    results);
}

// ------------------------------------------------------------------ schedules
function cronHelp() {
  return h('p', { class: 'hint' }, 'Five fields: minute hour day month weekday. ', h('code', {}, '0 3 * * *'), ' daily 03:00 · ',
    h('code', {}, '*/15 * * * *'), ' every 15 min · ', h('code', {}, '0 6 * * MON'), ' Mondays 06:00 · ', h('code', {}, '@hourly'), '. Server time zone.');
}

function scheduleForm(existing) {
  const x = existing || { enabled: true, hosts: [], timeoutSeconds: 300 };
  const f = {
    name: h('input', { type: 'text', value: x.name || '', placeholder: 'Nightly disk check' }),
    cron: h('input', { type: 'text', class: 'mono', value: x.cron || '0 3 * * *' }),
    group: h('select', {}, h('option', { value: '' }, '(none)'), [...new Set(S.fleet.hosts.map((r) => r.host.group).filter(Boolean))].map((g) => h('option', { value: g }, g))),
    tag: h('input', { type: 'text', value: x.tag || '', placeholder: 'tag' }),
    command: h('textarea', { rows: 4, class: 'mono', placeholder: 'df -h / && journalctl --vacuum-time=14d' }, x.command || ''),
    timeout: h('input', { type: 'number', min: 1, max: 3600, value: x.timeoutSeconds || 300 }),
    enabled: h('input', { type: 'checkbox', checked: x.enabled !== false }),
  };
  f.group.value = x.group || '';
  const hosts = hostChecklist(() => true);
  (x.hosts || []).forEach((n) => { if (hosts.boxes[n]) hosts.boxes[n].checked = true; });
  const out = h('p', { class: 'hint err' });
  const saveBtn = h('button', { class: 'primary', onclick: async () => {
    const sched = { id: x.id || null, name: f.name.value.trim(), cron: f.cron.value.trim(), hosts: hosts.chosen(), group: f.group.value || null,
      tag: f.tag.value.trim() || null, command: f.command.value, timeoutSeconds: Number(f.timeout.value) || 300, enabled: f.enabled.checked, confirmed: false };
    try {
      const r = await withConfirm((confirm) => api('POST', '/schedules', { schedule: sched, confirm }), 'the scheduled hosts');
      if (!r) return;
      closeDialog(); toast('Saved schedule ' + sched.name); renderSchedules();
    } catch (e) { out.textContent = e.message; }
  } }, 'Save');
  const field = (label, el) => h('div', { class: 'field' }, h('label', {}, label), el);
  openDialog(existing ? 'Edit schedule' : 'New schedule', [
    h('div', { class: 'grid g2' }, field('Name', f.name), field('When (cron)', f.cron)), cronHelp(),
    h('div', { class: 'field' }, h('label', {}, 'Hosts'), hosts.el),
    h('div', { class: 'grid g2' }, field('or every host in group', f.group), field('or every host with tag', f.tag)),
    field('Command', f.command),
    h('div', { class: 'row' }, h('span', { class: 'muted' }, 'timeout per host (s)'), h('div', { style: 'width:110px' }, f.timeout), h('span', { style: 'flex:1' }),
      h('label', { class: 'checks', style: 'margin:0' }, h('span', { class: 'row', style: 'gap:6px;color:var(--fg)' }, f.enabled, 'Enabled'))),
    h('p', { class: 'hint' }, 'Read-only and offline hosts are skipped at run time.'), out,
  ], [h('button', { onclick: closeDialog }, 'Cancel'), saveBtn], true);
}

function runDetails(run) {
  return [h('p', { class: 'muted' }, `${new Date(run.at).toLocaleString()} · ${run.ok} ok · ${run.failed} failed · ${run.skipped} skipped · ${(run.durationMs / 1000).toFixed(1)}s`),
    run.error ? h('p', { class: 'err' }, run.error) : null,
    Object.entries(run.results || {}).map(([n, x]) => h('details', { class: 'res', open: x.exit_code !== '0' && !x.skipped },
      h('summary', {}, h('span', { class: 'dot ' + (x.skipped ? 'unknown' : x.exit_code === '0' ? 'online' : 'offline') }), h('strong', {}, n),
        h('span', { class: 'muted' }, x.skipped ? 'skipped (' + x.skipped + ')' : x.error ? x.error : 'exit ' + x.exit_code)),
      x.output != null ? h('div', {}, h('pre', { class: 'out' }, x.output || '(no output)')) : null))];
}

async function renderSchedules() {
  const main = $('main');
  main.replaceChildren(h('div', { class: 'page-head' }, h('h2', {}, 'Schedules'), h('span', { class: 'meta' }, 'Commands that run on a timer across hosts'),
    h('span', { class: 'spacer' }), h('button', { class: 'primary', onclick: () => scheduleForm() }, 'New schedule')), h('p', { class: 'muted' }, 'Loading…'));
  let list;
  try { list = await api('GET', '/schedules'); } catch (e) { main.lastChild.replaceWith(h('p', { class: 'err' }, e.message)); return; }
  if (S.view !== 'schedules') return;
  const target = (s) => [...(s.hosts || []), s.group ? 'group ' + s.group : null, s.tag ? '#' + s.tag : null].filter(Boolean).join(', ');
  const body = list.length ? h('div', { class: 'card', style: 'padding:6px 8px' }, h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, ['Schedule', 'When', 'Targets', 'Next run', 'Last run', ''].map((x) => h('th', {}, x)))),
    h('tbody', {}, list.map((v) => {
      const s = v.schedule, l = v.last;
      return h('tr', {},
        h('td', {}, h('strong', {}, s.name), s.enabled ? null : h('span', { class: 'chip ro' }, 'paused'), s.confirmed ? h('span', { class: 'chip ro' }, 'risky') : null,
          h('div', { class: 'mono muted', style: 'max-width:360px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap', title: s.command }, s.command)),
        h('td', { class: 'mono' }, s.cron),
        h('td', {}, target(s)),
        h('td', { class: 'muted' }, v.running ? 'running now' : v.next ? new Date(v.next).toLocaleString() : '—'),
        h('td', {}, l ? h('span', { class: 'state ' + (l.failed || l.error ? 'offline' : 'online') }, l.failed || l.error ? `${l.failed} failed` : `${l.ok} ok`) : h('span', { class: 'muted' }, 'never'),
          l ? h('div', { class: 'muted', style: 'font-size:12px' }, ago(l.at)) : null),
        h('td', { class: 'act' },
          h('button', { class: 'small ghost', onclick: async () => {
            toast('Running ' + s.name + '…');
            try { const r = await api('POST', `/schedules/${enc(s.id)}/run`); openDialog(s.name + ': run', runDetails(r), [h('button', { onclick: closeDialog }, 'Close')], true); renderSchedules(); } catch (e) { fail(e); }
          } }, 'Run now'),
          h('button', { class: 'small ghost', onclick: async () => {
            try {
              const runs = await api('GET', `/schedules/${enc(s.id)}/runs`);
              openDialog(s.name + ': recent runs', runs.length ? runs.map((r) => h('div', { class: 'card' }, runDetails(r))) : h('p', { class: 'muted' }, 'No runs since the control centre started.'),
                [h('button', { onclick: closeDialog }, 'Close')], true);
            } catch (e) { fail(e); }
          } }, 'History'),
          h('button', { class: 'small ghost', onclick: () => scheduleForm(s) }, 'Edit'),
          h('button', { class: 'small ghost danger', onclick: async () => {
            if (!await confirmBox('Delete schedule', `Delete "${s.name}"?`, 'Delete')) return;
            try { await api('DELETE', `/schedules/${enc(s.id)}`); renderSchedules(); } catch (e) { fail(e); }
          } }, 'Delete')));
    })))))
    : h('div', { class: 'card' }, h('h3', {}, 'No schedules yet'), h('p', {}, 'Run a command across hosts on a timer, for example a nightly disk report or a weekly cleanup.'), cronHelp());
  main.lastChild.replaceWith(body);
}

// ------------------------------------------------------------------ alerts
async function refreshAlertBadge() {
  try {
    const a = await api('GET', '/alerts');
    const b = $('alert-count');
    b.textContent = a.active.length ? String(a.active.length) : '';
    b.hidden = !a.active.length;
    return a;
  } catch { return null; }
}

async function renderAlerts() {
  const main = $('main');
  const a = await refreshAlertBadge();
  if (S.view !== 'alerts') return;
  if (!a) { main.replaceChildren(h('p', { class: 'err' }, 'Cannot load alerts.')); return; }
  const c = a.config;
  const table = (rows, resolved) => rows.length ? h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, ['Host', 'Kind', 'Message', 'Since', resolved ? 'Resolved' : null].filter(Boolean).map((x) => h('th', {}, x)))),
    h('tbody', {}, rows.map((x) => h('tr', { class: 'click', onclick: () => openHost(x.host) },
      h('td', {}, h('strong', {}, x.host)), h('td', {}, h('span', { class: 'chip' }, x.kind)), h('td', {}, x.message),
      h('td', { class: 'muted' }, new Date(x.since).toLocaleString()),
      resolved ? h('td', { class: 'muted' }, x.resolvedAt ? new Date(x.resolvedAt).toLocaleString() : h('span', { class: 'state offline' }, 'firing')) : null)))))
    : h('p', { class: 'empty' }, resolved ? 'Nothing yet.' : 'All clear.');
  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Alerts'), h('span', { class: 'meta' }, `${a.active.length} active`), h('span', { class: 'spacer' }),
      h('button', { onclick: renderAlerts }, 'Refresh'),
      h('button', { disabled: !a.webhook, title: a.webhook ? '' : 'Set vmadmin.alerts.webhook-ref first', onclick: async () => {
        try { const r = await api('POST', '/alerts/test'); toast(r.ok ? 'Test message sent' : 'Webhook answered HTTP ' + r.status, !r.ok); } catch (e) { fail(e); }
      } }, 'Send test message')),
    h('div', { class: 'card' }, h('h3', {}, 'Active'), table(a.active, false)),
    h('div', { class: 'card' }, h('h3', {}, 'Recent (fired and resolved)'), table(a.history, true)),
    h('div', { class: 'card' }, h('h3', {}, 'Rules'),
      h('ul', { style: 'margin:0;padding-left:18px' },
        h('li', {}, `Host offline or rejecting the token for ${String(c.offlineAfter).replace(/^PT/, '').toLowerCase()}`),
        h('li', {}, `Root disk at ${c.diskPct}% or more`), h('li', {}, `Memory at ${c.memPct}% or more`),
        h('li', {}, `Load of ${c.loadPerCpu} per CPU or more`),
        c.failedUnits ? h('li', {}, 'Any failed systemd unit') : null, c.rebootRequired ? h('li', {}, 'Reboot required') : null),
      h('p', { class: 'hint' }, a.webhook ? `Notifications go to the configured webhook (${c.format} format).`
        : 'No webhook configured: alerts show here and in the audit log only. Set vmadmin.alerts.webhook-ref (env: or path:) to post them to Slack, Teams, Mattermost or any JSON endpoint.'),
      h('p', { class: 'hint' }, 'Thresholds are set under vmadmin.alerts in config.yml. Disk, memory, load and units are checked with each metrics sample (every 5 minutes by default).')));
}

// ------------------------------------------------------------------ token rotation (from host settings)
async function rotateToken(name) {
  if (!await confirmBox('Rotate token', [
    h('p', { style: 'margin:0 0 6px' }, `Create a new token on ${name}, switch the control centre to it, then revoke the old one.`),
    h('p', { style: 'margin:0' }, 'Other users of the old token (scripts, Claude Code) stop working.')], 'Rotate')) return;
  try {
    const r = await api('POST', `${hp(name)}/rotate-token`);
    closeDialog();
    toast(r.oldRevoked ? `Rotated: now using ${r.newTokenName}; old token revoked` : r.message, !r.oldRevoked);
  } catch (e) { fail(e); }
}
