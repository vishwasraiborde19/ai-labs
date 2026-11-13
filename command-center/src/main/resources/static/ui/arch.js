'use strict';
/* Architecture view: every system as a node in its area's lane (left to right), dependencies as
   arrows, and flows (Ship a change, SSO, ...) as numbered paths through the diagram. */

const SVG = 'http://www.w3.org/2000/svg';
function sv(tag, attrs, ...kids) {
  const el = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v); else el.setAttribute(k, v);
  }
  for (const kid of kids.flat(Infinity)) if (kid != null) el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  return el;
}

const A = { flow: null, step: -1, labels: load('arch.labels', false), group: load('arch.group', true), timer: null };
const NW = 176, NH = 48, GX = 84, GY = 16, TOP = 54, PAD = 18;

/* Nodes and edges, with VM worker agents folded into one node when grouping is on. */
function graph() {
  const systems = S.catalog.systems;
  const groupId = '__vm-workers';
  const workers = A.group ? systems.filter((s) => (s.tags || []).includes('vm-worker')) : [];
  const alias = new Map(workers.map((w) => [w.id, groupId]));
  const nodes = systems.filter((s) => !alias.has(s.id)).map((s) => ({ id: s.id, s, cat: categoryOf(s) }));
  if (workers.length) {
    const up = workers.filter((w) => stateOf(w) === 'up').length;
    nodes.push({ id: groupId, cat: 'agents', group: workers, s: { id: groupId, name: `VM worker agents (${workers.length})`, kind: 'mcp-agent',
      description: `${up} of ${workers.length} up` } });
  }
  const id = (x) => alias.get(x) || x;
  const seen = new Set();
  const edges = [];
  systems.forEach((s) => (s.dependsOn || []).forEach((d) => {
    const a = id(s.id), b = id(d.to);
    const key = a + '>' + b;
    if (a === b || seen.has(key) || !nodes.some((n) => n.id === b)) return;
    seen.add(key);
    edges.push({ from: a, to: b, label: d.label || '' });
  }));
  return { nodes, edges, id };
}

function layout(g) {
  const cats = S.session.categories.filter((c) => g.nodes.some((n) => n.cat === c.id));
  const col = new Map(cats.map((c, i) => [c.id, i]));
  const cols = cats.map((c) => g.nodes.filter((n) => n.cat === c.id).sort((a, b) => a.s.name.localeCompare(b.s.name)));
  const nb = new Map(g.nodes.map((n) => [n.id, []]));
  g.edges.forEach((e) => { nb.get(e.from).push(e.to); nb.get(e.to).push(e.from); });
  // barycenter ordering, a few sweeps, to reduce crossings
  for (let pass = 0; pass < 6; pass++) {
    const pos = new Map();
    cols.forEach((list) => list.forEach((n, i) => pos.set(n.id, i / Math.max(1, list.length - 1))));
    cols.forEach((list) => {
      const score = new Map(list.map((n, i) => {
        const ps = nb.get(n.id).filter((m) => pos.has(m)).map((m) => pos.get(m));
        return [n.id, ps.length ? ps.reduce((a, b) => a + b, 0) / ps.length : i / Math.max(1, list.length - 1)];
      }));
      list.sort((a, b) => score.get(a.id) - score.get(b.id));
    });
  }
  const maxN = Math.max(...cols.map((l) => l.length));
  const height = TOP + maxN * (NH + GY) + PAD;
  const xy = new Map();
  cols.forEach((list, ci) => {
    const off = ((maxN - list.length) * (NH + GY)) / 2;
    list.forEach((n, i) => xy.set(n.id, { x: PAD + ci * (NW + GX), y: TOP + off + i * (NH + GY), col: ci }));
  });
  return { cats, col, xy, width: PAD * 2 + cats.length * NW + (cats.length - 1) * GX, height };
}

function edgePath(a, b) {
  const ay = a.y + NH / 2, by = b.y + NH / 2;
  if (a.col < b.col) {
    const x1 = a.x + NW, x2 = b.x, dx = (x2 - x1) / 2;
    return { d: `M${x1} ${ay} C${x1 + dx} ${ay} ${x2 - dx} ${by} ${x2} ${by}`, mx: (x1 + x2) / 2, my: (ay + by) / 2 };
  }
  if (a.col > b.col) {
    const x1 = a.x, x2 = b.x + NW, dx = (x1 - x2) / 2;
    return { d: `M${x1} ${ay} C${x1 - dx} ${ay} ${x2 + dx} ${by} ${x2} ${by}`, mx: (x1 + x2) / 2, my: (ay + by) / 2 };
  }
  const x = a.x + NW, bulge = 28 + Math.abs(by - ay) * 0.25;
  return { d: `M${x} ${ay} C${x + bulge} ${ay} ${x + bulge} ${by} ${x} ${by}`, mx: x + bulge * 0.75, my: (ay + by) / 2 };
}

function drawDiagram(holder, flow) {
  const g = graph();
  const L = layout(g);
  const steps = flow ? flow.steps.map((st, i) => ({ ...st, n: i + 1, from: st.from ? g.id(st.from) : null, to: st.to ? g.id(st.to) : null })) : [];
  const active = A.step >= 0 && steps[A.step] ? [steps[A.step]] : steps;
  const flowNodes = new Set(active.flatMap((st) => [st.from, st.to]).filter(Boolean));
  const flowEdge = (e) => active.find((st) => (st.from === e.from && st.to === e.to) || (st.from === e.to && st.to === e.from));

  const svg = sv('svg', { width: L.width, height: L.height, viewBox: `0 0 ${L.width} ${L.height}`, class: 'arch' + (flow ? ' has-flow' : ''),
    role: 'img', 'aria-label': 'Architecture diagram: systems by area and how they depend on each other' },
  sv('defs', {},
    sv('marker', { id: 'arr', viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' },
      sv('path', { d: 'M0 0 L10 5 L0 10 z', class: 'arrowhead' })),
    sv('marker', { id: 'arr-hi', viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' },
      sv('path', { d: 'M0 0 L10 5 L0 10 z', class: 'arrowhead hi' }))));

  // lanes
  L.cats.forEach((c, i) => {
    const x = PAD + i * (NW + GX) - 12;
    svg.append(sv('rect', { x, y: 8, width: NW + 24, height: L.height - 16, rx: 12, class: 'lane' }),
      sv('text', { x: x + (NW + 24) / 2, y: 32, class: 'lane-title', 'text-anchor': 'middle' }, c.label));
  });

  // edges (dependencies), plus flow steps that have no matching dependency
  const edgeLayer = sv('g', {});
  const labelLayer = sv('g', {});
  const all = g.edges.map((e) => ({ ...e }));
  steps.forEach((st) => {
    if (st.from && st.to && st.from !== st.to && !all.some((e) => (e.from === st.from && e.to === st.to) || (e.from === st.to && e.to === st.from))) {
      all.push({ from: st.from, to: st.to, label: '', flowOnly: true });
    }
  });
  all.forEach((e) => {
    const a = L.xy.get(e.from), b = L.xy.get(e.to);
    if (!a || !b) return;
    const p = edgePath(a, b);
    const st = flow ? flowEdge(e) : null;
    if (e.flowOnly && !st) return;
    const cls = 'edge' + (st ? ' hi' : '') + (e.flowOnly ? ' flow-only' : '');
    edgeLayer.append(sv('path', { d: p.d, class: cls, 'data-from': e.from, 'data-to': e.to, 'marker-end': st ? 'url(#arr-hi)' : 'url(#arr)' },
      sv('title', {}, `${nodeName(g, e.from)} → ${nodeName(g, e.to)}${e.label ? ': ' + e.label : ''}`)));
    if (st) {
      labelLayer.append(sv('g', { class: 'badge', transform: `translate(${p.mx},${p.my})` }, sv('circle', { r: 11 }), sv('text', { 'text-anchor': 'middle', dy: '4' }, String(st.n))));
    } else if (A.labels && e.label) {
      labelLayer.append(sv('text', { x: p.mx, y: p.my - 4, class: 'elabel', 'text-anchor': 'middle', 'data-from': e.from, 'data-to': e.to }, e.label));
    }
  });
  svg.append(edgeLayer);

  // nodes
  g.nodes.forEach((n) => {
    const p = L.xy.get(n.id);
    if (!p) return;
    const k = kindOf(n.s);
    const st = n.group ? (n.group.some((w) => stateOf(w) === 'down') ? 'down' : n.group.every((w) => stateOf(w) === 'up') ? 'up' : 'unknown') : stateOf(n.s);
    const dim = flow && flowNodes.size && !flowNodes.has(n.id);
    const solo = steps.filter((x) => x.from === n.id && !x.to && (A.step < 0 || steps[A.step] === x));
    const name = n.s.name.length > 22 ? n.s.name.slice(0, 21) + '…' : n.s.name;
    const node = sv('g', { class: 'node' + (dim ? ' dim' : '') + (flowNodes.has(n.id) ? ' on' : ''), transform: `translate(${p.x},${p.y})`, tabindex: '0', role: 'link', 'data-id': n.id,
      'aria-label': `${n.s.name}, ${k.label}, ${st}`,
      onclick: () => { if (n.group) { S.filters = { q: 'vm-worker', category: '', env: '', state: '' }; go('catalog'); } else go('system', n.id); },
      onkeydown: (ev) => { if (ev.key === 'Enter') ev.currentTarget.dispatchEvent(new Event('click')); },
      onmouseenter: () => focusNode(svg, n.id, true), onmouseleave: () => focusNode(svg, n.id, false),
      onfocus: () => focusNode(svg, n.id, true), onblur: () => focusNode(svg, n.id, false) },
    sv('title', {}, `${n.s.name} — ${k.label}${n.s.description ? '\n' + n.s.description : ''}`),
    sv('rect', { width: NW, height: NH, rx: 10, class: 'nbox' }),
    sv('rect', { x: 0, y: 0, width: 6, height: NH, rx: 3, style: `fill:${k.color}` }),
    sv('rect', { x: 14, y: 12, width: 34, height: 24, rx: 6, style: `fill:${k.color}`, class: 'nabbr-bg' }),
    sv('text', { x: 31, y: 28, 'text-anchor': 'middle', class: 'nabbr' }, k.abbr),
    sv('text', { x: 56, y: 22, class: 'nname' }, name),
    sv('text', { x: 56, y: 37, class: 'nsub' }, n.group ? n.s.description : k.label),
    sv('circle', { cx: NW - 12, cy: 12, r: 4.5, class: 'ndot ' + st }));
    solo.forEach((x, i) => node.append(sv('g', { class: 'badge', transform: `translate(${NW - 4 - i * 24},${NH})` }, sv('circle', { r: 11 }), sv('text', { 'text-anchor': 'middle', dy: '4' }, String(x.n)))));
    svg.append(node);
  });
  svg.append(labelLayer);
  holder.replaceChildren(svg);
}

function nodeName(g, id) { const n = g.nodes.find((x) => x.id === id); return n ? n.s.name : id; }

function focusNode(svg, id, on) {
  if (svg.classList.contains('has-flow')) return;
  svg.classList.toggle('focus', on);
  svg.querySelectorAll('.edge, .elabel').forEach((e) => e.classList.toggle('near', on && (e.dataset.from === id || e.dataset.to === id)));
  const near = new Set([id]);
  if (on) svg.querySelectorAll('.edge.near').forEach((e) => { near.add(e.dataset.from); near.add(e.dataset.to); });
  svg.querySelectorAll('.node').forEach((n) => n.classList.toggle('near', on && near.has(n.dataset.id)));
}

function renderArchitecture() {
  const main = $('main');
  const flows = S.catalog.flows;
  const flowId = S.route[1] || null;
  const flow = flows.find((f) => f.id === flowId) || null;
  if (A.flow !== flowId) { A.step = -1; A.flow = flowId; }
  stopPlay();
  const holder = h('div', { class: 'arch-wrap', 'data-live': '1' });
  holder.addEventListener('refresh', () => drawDiagram(holder, flow));

  const labels = h('input', { type: 'checkbox', checked: A.labels, onchange: () => { A.labels = labels.checked; save('arch.labels', A.labels); drawDiagram(holder, flow); } });
  const group = h('input', { type: 'checkbox', checked: A.group, onchange: () => { A.group = group.checked; save('arch.group', A.group); drawDiagram(holder, flow); } });

  const stepList = flow ? h('ol', { class: 'steps' }, flow.steps.map((st, i) => {
    const a = sys(st.from), b = sys(st.to);
    return h('li', { class: A.step === i ? 'on' : null, tabindex: '0',
      onclick: () => { A.step = A.step === i ? -1 : i; stopPlay(); renderArchitecture(); },
      onkeydown: (e) => { if (e.key === 'Enter') e.currentTarget.click(); } },
    h('div', { class: 'who small' }, a ? a.name : '', b ? [' → ', b.name] : null),
    h('div', {}, mdInline(st.text || '')));
  })) : null;

  const side = h('aside', { class: 'arch-side' },
    h('h3', {}, 'Flows'),
    h('p', { class: 'small muted' }, 'Pick a flow to see how the systems work together, step by step.'),
    h('ul', { class: 'flowlist' },
      h('li', {}, h('a', { href: '#/architecture', class: !flow ? 'on' : null }, 'Everything (dependencies)')),
      flows.map((f) => h('li', {}, h('a', { href: '#/architecture/' + enc(f.id), class: flow && flow.id === f.id ? 'on' : null }, f.name)))),
    S.session.canEdit ? h('div', { class: 'row' }, h('button', { class: 'small', onclick: () => flowForm() }, '+ New flow'),
      flow ? h('button', { class: 'small', onclick: () => flowForm(flow) }, 'Edit flow') : null,
      flow ? h('button', { class: 'small danger', onclick: () => deleteFlow(flow) }, 'Delete') : null) : null,
    flow ? h('div', { class: 'flow-detail' }, h('h3', {}, flow.name), flow.description ? h('p', { class: 'muted' }, flow.description) : null,
      h('div', { class: 'row' }, h('button', { class: 'small primary', id: 'play', onclick: () => togglePlay(flow) }, A.timer ? 'Pause' : '▶ Play'),
        A.step >= 0 ? h('button', { class: 'small', onclick: () => { A.step = -1; renderArchitecture(); } }, 'Show all steps') : null),
      stepList) : h('div', { class: 'legend small' },
      h('h3', {}, 'Reading the diagram'),
      h('ul', { class: 'plain' },
        h('li', {}, 'Columns are areas, from who-you-are (identity) on the left to the agents that operate everything on the right.'),
        h('li', {}, 'An arrow A → B means A uses or depends on B. Hover a system to see its connections; click to open it.'),
        h('li', {}, 'The dot is live status: ', h('span', { class: 'dot up' }), ' up ', h('span', { class: 'dot down' }), ' down ', h('span', { class: 'dot unknown' }), ' no URL.')),
      S.session.categories.filter((c) => S.catalog.systems.some((s) => categoryOf(s) === c.id)).map((c) => h('p', {}, h('strong', {}, c.label), ' — ', c.description))));

  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h1', {}, 'How it fits together'), h('span', { class: 'spacer' }),
      h('label', { class: 'check' }, labels, ' Arrow labels'), h('label', { class: 'check' }, group, ' Group VM worker agents')),
    h('div', { class: 'arch-layout' }, holder, side));
  drawDiagram(holder, flow);
}

function stopPlay() { if (A.timer) { clearInterval(A.timer); A.timer = null; } }
function togglePlay(flow) {
  if (A.timer) { stopPlay(); renderArchitecture(); return; }
  A.step = 0;
  renderArchitecture();
  A.timer = setInterval(() => {
    if (S.route[0] !== 'architecture') { stopPlay(); return; }
    A.step++;
    if (A.step >= flow.steps.length) { stopPlay(); A.step = -1; }
    const t = A.timer;
    A.timer = null;               // renderArchitecture stops a running timer; keep ours
    renderArchitecture();
    A.timer = t;
    const b = $('play'); if (b) b.textContent = A.timer ? 'Pause' : '▶ Play';
  }, 3000);
  const b = $('play'); if (b) b.textContent = 'Pause';
}

async function deleteFlow(f) {
  if (!await confirmBox('Delete flow', `Delete the flow “${f.name}”?`, 'Delete')) return;
  try { await api('DELETE', `/flows/${enc(f.id)}?version=${S.catalog.version}`); toast('Deleted'); await reload(); go('architecture'); }
  catch (e) { fail(e); }
}

function flowForm(existing) {
  const x = existing || { steps: [] };
  const name = h('input', { type: 'text', value: x.name || '', placeholder: 'Ship a change' });
  const desc = h('input', { type: 'text', value: x.description || '', placeholder: 'One line' });
  const systems = S.catalog.systems.slice().sort((a, b) => a.name.localeCompare(b.name));
  const opts = (blank) => [h('option', { value: '' }, blank), systems.map((s) => h('option', { value: s.id }, s.name))];
  const steps = rowsEditor(x.steps, (st) => {
    const from = h('select', { 'aria-label': 'From' }, opts('From…'));
    from.value = st.from || '';
    const to = h('select', { 'aria-label': 'To' }, opts('(no target)'));
    to.value = st.to || '';
    const text = h('input', { type: 'text', value: st.text || '', placeholder: 'What happens', class: 'grow', 'aria-label': 'What happens' });
    return [[from, h('span', { class: 'muted' }, '→'), to, text], () => (from.value || to.value) ? { from: from.value || null, to: to.value || null, text: text.value.trim() || null } : null];
  }, '+ Step');
  const err = h('p', { class: 'err', role: 'alert' });
  const saveBtn = h('button', { class: 'primary', onclick: async () => {
    const body = { id: x.id || null, name: name.value.trim(), description: desc.value.trim() || null, steps: steps.read() };
    if (!body.name) { err.textContent = 'Name is required.'; return; }
    saveBtn.disabled = true;
    try {
      const saved = existing ? await api('PUT', '/flows/' + enc(existing.id), { flow: body, version: S.catalog.version })
        : await api('POST', '/flows', { flow: body, version: S.catalog.version });
      closeDialog(); toast('Saved'); await reload(); go('architecture', saved.id);
    } catch (e) { err.textContent = e.message; } finally { saveBtn.disabled = false; }
  } }, 'Save');
  openDialog(existing ? 'Edit flow' : 'New flow', h('div', { class: 'form' },
    h('div', { class: 'g2' }, field('Name', name), field('Description', desc)),
    h('fieldset', {}, h('legend', {}, 'Steps, in order'), steps,
      h('p', { class: 'hint' }, 'A step from A to B shows as a numbered arrow; leave the target empty for something that happens inside one system.')),
    err), [h('button', { onclick: closeDialog }, 'Cancel'), saveBtn], true);
  name.focus();
}
