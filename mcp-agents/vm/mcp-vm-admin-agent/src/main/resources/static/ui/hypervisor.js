'use strict';
/* Hypervisor view: the Proxmox cluster, its nodes, and every VM and container (read-only).
   Uses the helpers and state from app.js (h, api, S, load, save, fmtBytes, fmtDur, openHost, fail). */

S.pve = {
  data: null,
  q: load('pve.q', ''), status: load('pve.status', ''), node: load('pve.node', ''), type: load('pve.type', ''),
  templates: load('pve.templates', false), sort: load('pve.sort', ['vmid', 1]),
};

const pct = (a, b) => (b ? Math.round((100 * a) / b) : 0);
const guestState = (s) => ({ running: 'online', stopped: 'unknown', paused: 'error', suspended: 'error' })[s] || 'error';

function meterBox(label, used, total, text) {
  const p = pct(used, total);
  return h('div', { style: 'margin-top:8px' },
    h('div', { class: 'row' }, h('span', { class: 'k', style: 'flex:1' }, label), h('span', { class: 's' }, text)),
    h('div', { class: 'bar', role: 'meter', 'aria-label': label, 'aria-valuenow': String(p), 'aria-valuemin': '0', 'aria-valuemax': '100' },
      h('i', { class: p >= 90 ? 'hot' : null, style: `width:${p}%` })));
}

function nodeCard(n, guests) {
  const mine = guests.filter((g) => g.node === n.name && !g.template);
  const st = n.status === 'online' ? 'online' : 'offline';
  return h('div', { class: 'stat' },
    h('div', { class: 'row' }, h('span', { class: 'dot ' + st }), h('strong', { style: 'font-size:15px' }, n.name),
      n.local ? h('span', { class: 'chip', title: 'The control centre talks to this node' }, 'api') : null,
      h('span', { class: 'spacer', style: 'flex:1' }), h('span', { class: 'state ' + st }, n.status)),
    h('div', { class: 's mono' }, n.ip || ''),
    st === 'online' ? [
      meterBox('CPU', n.cpu, 1, `${Math.round(n.cpu * 100)}% of ${n.maxcpu} cores`),
      meterBox('Memory', n.mem, n.maxmem, `${fmtBytes(n.mem)} / ${fmtBytes(n.maxmem)}`),
      meterBox('Root disk', n.disk, n.maxdisk, `${fmtBytes(n.disk)} / ${fmtBytes(n.maxdisk)}`),
      h('div', { class: 's', style: 'margin-top:8px' },
        `Up ${fmtDur(n.uptime)} · ${mine.filter((g) => g.status === 'running').length} of ${mine.length} guests running`),
    ] : h('p', { class: 'err', style: 'margin:8px 0 0' }, 'Node is offline'));
}

function guestRows(guests) {
  const p = S.pve;
  const q = p.q.trim().toLowerCase();
  let rows = guests.filter((g) => (p.templates || !g.template)
    && (!p.status || g.status === p.status) && (!p.node || g.node === p.node) && (!p.type || g.type === p.type)
    && (!q || [String(g.vmid), g.name || '', g.node, ...(g.ips || []), ...(g.tags || []), g.fleetHost || '']
      .some((x) => x.toLowerCase().includes(q))));
  const [key, dir] = p.sort;
  const val = (g) => ({ cpu: g.status === 'running' ? g.cpu * g.maxcpu : -1, mem: g.status === 'running' ? g.mem : -1,
    ip: (g.ips || [])[0] || '', name: (g.name || '').toLowerCase() })[key] ?? g[key];
  rows = rows.slice().sort((a, b) => {
    const x = val(a), y = val(b);
    return (x < y ? -1 : x > y ? 1 : a.vmid - b.vmid) * dir;
  });
  return rows;
}

function guestTable(guests) {
  const rows = guestRows(guests);
  const cols = [['status', 'Status'], ['vmid', 'ID'], ['name', 'Name'], ['type', 'Type'], ['node', 'Node'],
    ['cpu', 'CPU'], ['mem', 'Memory'], ['maxdisk', 'Disk'], ['uptime', 'Uptime'], ['ip', 'IP address'], ['fleetHost', 'Fleet host']];
  const [sk, sd] = S.pve.sort;
  const head = h('tr', {}, cols.map(([k, label]) => h('th', { class: 'sortable', 'aria-sort': sk === k ? (sd > 0 ? 'ascending' : 'descending') : 'none' },
    h('button', { class: 'th-btn', onclick: () => { S.pve.sort = [k, sk === k ? -sd : 1]; save('pve.sort', S.pve.sort); drawGuests(); } },
      label, sk === k ? (sd > 0 ? ' ▲' : ' ▼') : ''))));
  const body = rows.map((g) => {
    const running = g.status === 'running';
    const fleet = g.fleetHost
      ? h('button', { class: 'linkish', title: `Open ${g.fleetHost} in the control centre`, onclick: (e) => { e.stopPropagation(); openHost(g.fleetHost); } },
        h('span', { class: 'dot ' + (g.fleetState || 'unknown') }), ' ', g.fleetHost)
      : h('span', { class: 'muted', title: 'No worker agent in the fleet has this IP or name' }, '—');
    return h('tr', { class: g.fleetHost ? 'click' : null, onclick: g.fleetHost ? () => openHost(g.fleetHost) : null },
      h('td', {}, h('span', { class: 'row', style: 'flex-wrap:nowrap' }, h('span', { class: 'dot ' + guestState(g.status) }),
        h('span', { class: 'state ' + guestState(g.status) }, g.status)),
        g.lock ? h('div', {}, h('span', { class: 'chip ro', title: 'Locked by a running task' }, 'lock: ' + g.lock)) : null),
      h('td', { class: 'num' }, String(g.vmid)),
      h('td', {}, h('strong', {}, g.name || '(no name)'), g.template ? h('span', { class: 'chip' }, 'template') : null,
        (g.tags || []).length ? h('div', {}, g.tags.map((t) => h('span', { class: 'chip' }, '#' + t))) : null),
      h('td', {}, g.type === 'qemu' ? 'VM' : 'CT'),
      h('td', {}, g.node),
      h('td', { class: 'num' }, running ? `${Math.round(g.cpu * 100)}% · ${g.maxcpu} vCPU` : `${g.maxcpu} vCPU`),
      h('td', { class: 'num' }, running ? `${fmtBytes(g.mem)} / ${fmtBytes(g.maxmem)}` : fmtBytes(g.maxmem)),
      h('td', { class: 'num' }, g.type === 'lxc' && g.disk ? `${fmtBytes(g.disk)} / ${fmtBytes(g.maxdisk)}` : fmtBytes(g.maxdisk)),
      h('td', { class: 'num' }, running ? fmtDur(g.uptime) : ''),
      h('td', { class: 'mono', title: g.ipSource ? 'from ' + g.ipSource : (running ? 'No IP: install qemu-guest-agent in the VM or set a static IP' : '') },
        (g.ips || []).join(', ') || h('span', { class: 'muted' }, running ? 'unknown' : '')),
      h('td', {}, fleet));
  });
  return h('div', { class: 'table-wrap' }, h('table', { class: 'guests' }, h('thead', {}, head),
    h('tbody', {}, body.length ? body : h('tr', {}, h('td', { colspan: String(cols.length), class: 'empty' }, 'No guests match the filters.')))));
}

function drawGuests() {
  const holder = $('pve-guests');
  const d = S.pve.data;
  if (!holder || !d || !d.configured) return;
  holder.replaceChildren(guestTable(d.guests));
  const shown = guestRows(d.guests).length;
  $('pve-count').textContent = `${shown} of ${d.guests.length} shown`;
}

function pveSetup(d) {
  return h('div', { class: 'card' }, h('h3', {}, 'Connect your Proxmox cluster'),
    h('p', {}, 'Create a read-only API token on any cluster node (Datacenter → Permissions → API Tokens, or in a node shell):'),
    h('pre', { class: 'out' }, [
      'pveum user add vmadmin@pve --comment "VM control centre"',
      'pveum acl modify / --users vmadmin@pve --roles PVEAuditor',
      'pveum user token add vmadmin@pve control-centre --privsep 0',
      '# optional, so VM IPs can be read from the QEMU guest agent (PVE 9; on PVE 8 use VM.Monitor):',
      'pveum role add VMAdminGuestIP --privs VM.GuestAgent.Audit',
      'pveum acl modify /vms --users vmadmin@pve --roles VMAdminGuestIP'].join('\n')),
    h('p', {}, 'Save the token on this machine as ', h('code', {}, 'vmadmin@pve!control-centre=<secret>'), ' and add to config.yml:'),
    h('pre', { class: 'out' }, [
      'install -m 600 /dev/null ~/.vm-admin/proxmox.token   # then paste the token into it',
      '',
      'vmadmin:',
      '  proxmox:',
      '    endpoints:',
      ...((d.endpoints || []).length ? d.endpoints : ['https://192.168.1.30:8006', 'https://192.168.1.40:8006']).map((e) => `      - ${e}`),
      '    token-ref: path:~/.vm-admin/proxmox.token',
      '    insecure-skip-tls-verify: true   # or ca-cert-ref: path:<copy of /etc/pve/pve-root-ca.pem>'].join('\n')),
    h('p', { class: 'hint' }, 'Restart the control centre after editing config.yml.'));
}

async function loadHypervisor(fresh) {
  const btn = $('pve-refresh');
  if (btn) btn.disabled = true;
  try {
    const d = await api('GET', '/pve' + (fresh ? '?fresh=true' : ''));
    if (S.view !== 'hypervisor') return;
    S.pve.data = d;
    S.pve.error = null;
  } catch (e) {
    if (S.view !== 'hypervisor') return;
    S.pve.error = e.message || String(e);
  } finally {
    if (btn) btn.disabled = false;
  }
  drawHypervisor();
}

function drawHypervisor() {
  const main = $('main');
  const d = S.pve.data;
  const p = S.pve;
  const refresh = h('button', { id: 'pve-refresh', onclick: () => loadHypervisor(true) }, 'Refresh');
  if (p.error && !d) {
    main.replaceChildren(h('div', { class: 'page-head' }, h('h2', {}, 'Hypervisor'), h('span', { class: 'spacer' }), refresh),
      h('div', { class: 'card' }, h('h3', {}, 'Cannot reach Proxmox'), h('p', { class: 'err' }, p.error),
        h('p', { class: 'hint' }, 'Check vmadmin.proxmox in config.yml, the token, and that port 8006 is reachable from this machine.')));
    return;
  }
  if (!d) { main.replaceChildren(h('p', { class: 'muted' }, 'Loading the Proxmox cluster…')); return; }
  if (!d.configured) {
    main.replaceChildren(h('div', { class: 'page-head' }, h('h2', {}, 'Hypervisor')), pveSetup(d));
    return;
  }
  const c = d.cluster || {};
  const sum = d.summary || {};
  const nodesOnline = d.nodes.filter((n) => n.status === 'online').length;

  // Filters are built once and kept across the 15 s refreshes, so typing is not interrupted.
  let filters = $('pve-filters');
  if (!filters) {
    const opt = (v, l) => h('option', { value: v }, l);
    const search = h('input', { type: 'search', placeholder: 'Filter by name, ID, IP, tag or fleet host', 'aria-label': 'Filter guests', value: p.q,
      oninput: () => { p.q = search.value; save('pve.q', p.q); drawGuests(); } });
    const sel = (key, label, options) => {
      const s = h('select', { 'aria-label': label, style: 'width:auto', onchange: () => { p[key] = s.value; save('pve.' + key, s.value); drawGuests(); } }, options);
      s.value = p[key];
      return s;
    };
    const tmpl = h('input', { type: 'checkbox', checked: p.templates, onchange: () => { p.templates = tmpl.checked; save('pve.templates', p.templates); drawGuests(); } });
    filters = h('div', { class: 'row', id: 'pve-filters', style: 'margin-bottom:10px' },
      h('div', { class: 'grow' }, search),
      sel('status', 'Status', [opt('', 'All states'), opt('running', 'Running'), opt('stopped', 'Stopped'), opt('paused', 'Paused')]),
      sel('type', 'Type', [opt('', 'VMs and containers'), opt('qemu', 'VMs only'), opt('lxc', 'Containers only')]),
      h('select', { id: 'pve-node', 'aria-label': 'Node', style: 'width:auto', onchange: (e) => { p.node = e.target.value; save('pve.node', p.node); drawGuests(); } }),
      h('label', { class: 'row', style: 'margin:0;color:var(--fg);font-size:13px;gap:6px' }, tmpl, 'Templates'),
      h('span', { class: 'meta muted', id: 'pve-count' }));
  }
  const nodeSel = filters.querySelector('#pve-node');
  nodeSel.replaceChildren(h('option', { value: '' }, 'All nodes'), ...d.nodes.map((n) => h('option', { value: n.name }, n.name)));
  nodeSel.value = d.nodes.some((n) => n.name === p.node) ? p.node : '';

  main.replaceChildren(
    h('div', { class: 'page-head' }, h('h2', {}, 'Hypervisor'),
      h('span', { class: 'meta' }, [c.name ? `Cluster ${c.name}` : 'Single node', `${nodesOnline}/${d.nodes.length} nodes online`,
        `${sum.running}/${sum.guests} guests running`, `${sum.inFleet} in fleet`].join(' · ')),
      c.quorate ? h('span', { class: 'chip' }, 'quorate') : h('span', { class: 'chip ro' }, 'NO QUORUM'),
      h('span', { class: 'spacer' }), p.error ? h('span', { class: 'err', title: p.error }, 'Last refresh failed') : null, refresh),
    h('div', { class: 'grid ' + (d.nodes.length >= 3 ? 'g3' : 'g2'), style: 'margin-bottom:14px' }, d.nodes.map((n) => nodeCard(n, d.guests))),
    h('div', { class: 'card', style: 'padding:10px 10px 4px' }, filters, h('div', { id: 'pve-guests' })),
    h('p', { class: 'hint' }, `Read-only view via ${d.endpoint || '?'} (fails over to ${d.endpoints.filter((e) => e !== d.endpoint).join(', ') || 'no other node'}). `,
      'Refreshed every 15 seconds. IPs come from the QEMU guest agent when available, else from static cloud-init or container settings. ',
      'Click a guest with a fleet host to open it.'));
  drawGuests();
}

function renderHypervisor() {
  if (S.pve.data) drawHypervisor(); else $('main').replaceChildren(h('p', { class: 'muted' }, 'Loading the Proxmox cluster…'));
  loadHypervisor(false);
  S.timers.push(setInterval(() => { if (!document.hidden) loadHypervisor(false); }, 15000));
}
