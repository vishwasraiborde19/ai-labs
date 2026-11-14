'use strict';
/* ai-labs UI starter. Plain DOM: never innerHTML with data — everything goes through h(). */

const APP = 'app';                       // localStorage prefix and API header name: X-<App>
const S = { route: ['home'], items: null, error: null, timers: [] };

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
const enc = encodeURIComponent;
function save(key, v) { try { localStorage.setItem(APP + '.' + key, JSON.stringify(v)); } catch { /* private mode */ } }
function load(key, d) { try { const v = localStorage.getItem(APP + '.' + key); return v ? JSON.parse(v) : d; } catch { return d; } }

class ApiError extends Error {
  constructor(status, data) { super((data && (data.error || data.detail || data.message)) || ('HTTP ' + status)); this.status = status; this.data = data || {}; }
}
/** JSON API on the same origin. The custom header is the CSRF guard the server checks on changes. */
async function api(method, path, body, { timeoutMs = 15000 } = {}) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs);
  const opt = { method, headers: { ['X-' + APP]: '1' }, signal: ctl.signal };
  if (body !== undefined) { opt.body = JSON.stringify(body); opt.headers['Content-Type'] = 'application/json'; }
  try {
    const r = await fetch('/ui/api' + path, opt);
    const text = await r.text();
    let data = null; try { data = text ? JSON.parse(text) : null; } catch { data = { error: text.slice(0, 300) }; }
    if (!r.ok) throw new ApiError(r.status, data);
    return data;
  } catch (e) {
    if (e.name === 'AbortError') throw new ApiError(0, { error: 'Request timed out' });
    if (e instanceof ApiError) throw e;
    throw new ApiError(0, { error: 'Server not reachable' });
  } finally { clearTimeout(t); }
}

function toast(msg, bad) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast show' + (bad ? ' bad' : '');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => { t.className = 'toast'; }, bad ? 6000 : 2200);
}
const fail = (e) => toast(e.message || String(e), true);

function ago(iso) {
  if (!iso) return 'never';
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 5) return 'just now';
  if (s < 90) return s + ' s ago';
  if (s < 5400) return Math.round(s / 60) + ' min ago';
  return Math.round(s / 3600) + ' h ago';
}
const when = (iso) => h('span', { title: iso ? new Date(iso).toLocaleString() : '' }, ago(iso));

/** Runs fn every ms while this view is shown and the tab is visible. */
function poll(fn, ms) {
  const tick = () => { if (!document.hidden) fn(); };
  S.timers.push(setInterval(tick, ms));
}
function clearTimers() { S.timers.forEach(clearInterval); S.timers = []; }

/** Async click handler: disables the button while running, shows errors. */
function busy(fn) {
  return async (ev) => {
    const b = ev.currentTarget; b.disabled = true;
    try { await fn(ev); } catch (e) { fail(e); } finally { b.disabled = false; }
  };
}

// ------------------------------------------------------------------ dialogs
function openDialog(title, body, actions) {
  const d = $('dlg');
  d.replaceChildren(h('h2', {}, title), h('div', { style: 'margin:12px 0' }, body), h('div', { class: 'row', style: 'justify-content:flex-end' }, actions));
  d.showModal();
  return d;
}
const closeDialog = () => $('dlg').close();

/** Confirm a destructive action; type-to-confirm when `typed` is given (e.g. the resource name). */
function confirmDanger(what, consequence, typed) {
  return new Promise((resolve) => {
    const input = typed ? h('input', { type: 'text', placeholder: typed, 'aria-label': 'Type ' + typed + ' to confirm' }) : null;
    const go = h('button', { class: 'primary danger', disabled: !!typed, onclick: () => { closeDialog(); resolve(true); } }, what);
    if (input) input.addEventListener('input', () => { go.disabled = input.value !== typed; });
    openDialog(what + '?', [h('p', {}, consequence), input && h('label', { class: 'field' }, 'Type ' + typed + ' to confirm', input)],
      [h('button', { onclick: () => { closeDialog(); resolve(false); } }, 'Cancel'), go]);
    $('dlg').addEventListener('close', () => resolve(false), { once: true });
  });
}

// ------------------------------------------------------------------ views
function stateView(loading, error, empty, data) {
  if (error) return h('div', { class: 'card err' }, error, ' ', h('button', { class: 'small', onclick: () => { S.error = null; refresh(); } }, 'Retry'));
  if (loading) return h('p', { class: 'muted pad' }, 'Loading…');
  if (empty) return h('div', { class: 'empty' }, empty);
  return data();
}

function viewHome() {
  return h('div', {}, h('h1', {}, 'Home'), h('p', { class: 'muted' }, 'Replace with an overview: what is healthy, what needs attention.'));
}

function viewItems() {
  return h('div', {},
    h('div', { class: 'section' }, h('h1', {}, 'Items'), h('span', { class: 'spacer' }),
      h('button', { class: 'primary', onclick: openAdd }, 'Add item')),
    stateView(S.items == null, S.error, S.items && !S.items.length && 'No items yet.', () =>
      h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'Name'), h('th', {}, 'State'), h('th', {}, 'Updated'), h('th', {}))),
        h('tbody', {}, S.items.map((it) => h('tr', {},
          h('td', {}, it.name),
          h('td', {}, h('span', { class: 'badge ' + (it.healthy ? 'ok' : 'bad') }, it.healthy ? 'healthy' : 'failing')),
          h('td', {}, when(it.updated)),
          h('td', {}, h('button', { class: 'small danger', onclick: busy(async () => {
            if (!(await confirmDanger('Delete ' + it.name, 'This removes it permanently.', it.name))) return;
            await api('DELETE', '/items/' + enc(it.id)); toast('Deleted'); await refresh();
          }) }, 'Delete')))))))));
}

function openAdd() {
  const name = h('input', { type: 'text', required: true });
  const err = h('p', { class: 'err', role: 'alert' });
  const saveBtn = h('button', { class: 'primary', onclick: busy(async () => {
    if (!name.value.trim()) { err.textContent = 'Name is required.'; return; }
    try { await api('POST', '/items', { name: name.value.trim() }); closeDialog(); toast('Saved'); await refresh(); }
    catch (e) { err.textContent = e.message; }
  }) }, 'Save');
  openDialog('Add item', [h('label', { class: 'field' }, 'Name', name), err], [h('button', { onclick: closeDialog }, 'Cancel'), saveBtn]);
  name.focus();
}

// ------------------------------------------------------------------ router
const VIEWS = { home: viewHome, items: viewItems };

async function refresh() {
  try { S.items = await api('GET', '/items'); S.error = null; } catch (e) { S.error = e.message; }
  render();
}

function render() {
  const parts = (location.hash.replace(/^#\/?/, '') || 'home').split('/').map(decodeURIComponent);
  if (parts[0] !== S.route[0]) clearTimers();
  S.route = parts;
  document.querySelectorAll('#nav a').forEach((a) => (a.dataset.view === parts[0] ? a.setAttribute('aria-current', 'page') : a.removeAttribute('aria-current')));
  const view = VIEWS[parts[0]] || (() => h('div', { class: 'empty' }, 'Not found. ', h('a', { href: '#/home' }, 'Go home')));
  $('main').replaceChildren(view(...parts.slice(1)));
}

window.addEventListener('hashchange', render);
document.addEventListener('DOMContentLoaded', () => {
  const theme = load('theme', null); if (theme) document.documentElement.dataset.theme = theme;
  $('theme').addEventListener('click', () => {
    const dark = matchMedia('(prefers-color-scheme: dark)').matches;
    const cur = document.documentElement.dataset.theme || (dark ? 'dark' : 'light');
    const next = cur === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next; save('theme', next);
  });
  render();
  refresh();
});
