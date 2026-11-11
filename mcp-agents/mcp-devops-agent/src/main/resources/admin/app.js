'use strict';
// mcp-devops-agent admin UI. Edits systems.yml through /admin/api. Only secret *references* are
// entered here, never secret values.

const KINDS = {
  jenkins: {
    label: 'Jenkins', singular: 'Jenkins server',
    lead: 'Jenkins servers the agent can create pipelines on and run builds against.',
    sub: s => s.url,
    blank: () => ({ name: '', url: '', username: '', tokenRef: '', caCertRef: '', readOnly: false,
      insecureSkipTlsVerify: false, credentialSyncAllow: [] }),
    fields: [
      { k: 'name', label: 'Name', req: true, ph: 'main', hint: 'Tools refer to the server by this name.' },
      { k: 'url', label: 'URL', req: true, ph: 'http://jenkins.lab.local:8080' },
      { k: 'username', label: 'Username', req: true, ph: 'devops-agent' },
      { k: 'tokenRef', label: 'API token', type: 'ref', req: true, ph: 'access:jenkins/main/api-token' },
      { k: 'caCertRef', label: 'CA certificate', type: 'ref', ph: 'path:/etc/ssl/jenkins-ca.pem', hint: 'Only for HTTPS with a private CA.' },
      { k: 'credentialSyncAllow', label: 'Secrets that may be synced into Jenkins', type: 'list', wide: true,
        ph: 'access:dockerhub/ci/*\naccess:kubernetes/dev/kubeconfig',
        hint: 'One reference per line; a trailing * matches a prefix. Empty disables jenkins_sync_credential.' },
      { k: 'readOnly', label: 'Read-only', type: 'bool', hint: 'Blocks builds, job changes and credential sync.' },
      { k: 'insecureSkipTlsVerify', label: 'Skip TLS verification', type: 'bool', warn: true, hint: 'Lab use only.' },
    ],
  },
  dockerhub: {
    label: 'Docker Hub', singular: 'registry account',
    lead: 'Docker Hub accounts used to verify pushed images. Public repositories work without one.',
    sub: s => s.namespace ? 'hub.docker.com/' + s.namespace : '',
    blank: () => ({ name: '', namespace: '', username: '', passwordRef: '' }),
    fields: [
      { k: 'name', label: 'Name', req: true, ph: 'default' },
      { k: 'namespace', label: 'Namespace', ph: 'myuser', hint: 'Used when a repository is given without one.' },
      { k: 'username', label: 'Username', ph: 'myuser' },
      { k: 'passwordRef', label: 'Access token', type: 'ref', ph: 'access:dockerhub/ci/token', hint: 'Docker Hub personal access token.' },
    ],
  },
  kubernetes: {
    label: 'Kubernetes', singular: 'cluster',
    lead: 'Clusters the agent can inspect and deploy to, through a service account token.',
    sub: s => s.apiServer,
    blank: () => ({ name: '', apiServer: '', tokenRef: '', caCertRef: '', insecureSkipTlsVerify: false,
      allowedNamespaces: [], readOnly: false }),
    fields: [
      { k: 'name', label: 'Name', req: true, ph: 'dev' },
      { k: 'apiServer', label: 'API server', req: true, ph: 'https://192.168.1.50:6443' },
      { k: 'tokenRef', label: 'Service account token', type: 'ref', req: true, ph: 'access:kubernetes/dev/devops-agent-token' },
      { k: 'caCertRef', label: 'Cluster CA', type: 'ref', ph: 'access:kubernetes/dev/ca', hint: 'PEM, or base64 PEM as in kubeconfig.' },
      { k: 'allowedNamespaces', label: 'Allowed namespaces', type: 'list', wide: true, ph: 'dev\nstaging',
        hint: 'One per line. Empty allows every namespace the token can reach.' },
      { k: 'readOnly', label: 'Read-only', type: 'bool', hint: 'Inspect only: no set-image, restart, scale or rollback.' },
      { k: 'insecureSkipTlsVerify', label: 'Skip TLS verification', type: 'bool', warn: true, hint: 'Prefer setting the cluster CA.' },
    ],
  },
};

const S = {
  version: null, file: '', systems: null, saved: '', yaml: '', access: {}, minPassword: 12, auth: 'password',
  tab: 'jenkins', open: {}, tests: {}, errors: [], conflict: null,
};

// ---------------------------------------------------------------- helpers

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

async function api(method, path, body) {
  const res = await fetch('/admin/api/' + path, {
    method, credentials: 'same-origin',
    headers: { 'X-Devops-Admin': '1', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let data = {};
  try { data = await res.json(); } catch (e) { /* empty body */ }
  if (res.status === 401 && path !== 'login') { renderLogin('Your session expired. Sign in again.'); throw new Error('login'); }
  return { status: res.status, ok: res.ok, data };
}

function toast(msg) {
  let t = document.querySelector('.toast');
  if (!t) { t = h('div', { class: 'toast' }); document.body.append(t); }
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(t._timer);
  t._timer = setTimeout(() => t.classList.remove('show'), 2400);
}

const isDirty = () => S.systems && JSON.stringify(S.systems) !== S.saved;
const app = () => document.getElementById('app');

// ---------------------------------------------------------------- login

function renderLogin(message) {
  app().className = '';
  const pw = h('input', { type: 'password', id: 'pw', autocomplete: 'current-password', required: true, autofocus: true });
  const err = h('div', { class: 'result bad' }, message || '');
  const form = h('form', {
    onsubmit: async e => {
      e.preventDefault();
      err.textContent = '';
      const r = await api('POST', 'login', { password: pw.value });
      if (r.ok) { await load(); } else { err.textContent = r.data.error || 'Login failed'; pw.select(); }
    },
  },
    h('div', { class: 'field' }, h('label', { for: 'pw' }, 'Admin password'), pw),
    h('button', { class: 'btn primary', type: 'submit' }, 'Sign in'),
    err);
  app().replaceChildren(h('div', { class: 'login' },
    h('h1', {}, 'DevOps Agent'),
    h('p', { class: 'lead' }, 'Configure the Jenkins servers, registries and clusters the agent can reach.'),
    form));
  pw.focus();
}

// ---------------------------------------------------------------- data

async function load() {
  const r = await api('GET', 'config');
  if (!r.ok) { renderLogin(); return; }
  S.version = r.data.version;
  S.file = r.data.file;
  S.systems = r.data.systems;
  S.saved = JSON.stringify(S.systems);
  S.yaml = r.data.yaml;
  S.access = r.data.access || {};
  S.minPassword = (r.data.admin && r.data.admin.minPasswordLength) || 12;
  S.auth = (r.data.admin && r.data.admin.auth) || 'password';
  if (S.auth !== 'password' && S.tab === 'account') S.tab = 'jenkins';
  S.errors = []; S.conflict = null;
  renderShell();
}

async function save() {
  S.errors = []; S.conflict = null;
  const r = await api('PUT', 'config', { version: S.version, systems: S.systems });
  if (r.ok) {
    S.version = r.data.version; S.yaml = r.data.yaml; S.saved = JSON.stringify(S.systems);
    toast('Saved. Running agents pick up the change within a few seconds.');
  } else if (r.status === 409) {
    S.conflict = r.data.error;
  } else {
    S.errors = r.data.errors || [r.data.error || 'Save failed'];
  }
  renderShell();
}

// ---------------------------------------------------------------- shell

function renderShell() {
  app().className = '';
  const saveBtn = h('button', { class: 'btn primary', id: 'save', disabled: !isDirty(), onclick: save }, 'Save changes');
  const top = h('header', { class: 'top' },
    h('div', { class: 'brand' }, 'DevOps Agent', h('small', {}, 'admin')),
    h('div', { class: 'meta', title: S.file }, S.file + ' · ' + (S.version === 'none' ? 'not saved yet' : S.version.slice(0, 8))),
    h('div', { class: 'spacer' }),
    h('span', { class: 'dirty', id: 'dirty' }, isDirty() ? 'Unsaved changes' : ''),
    isDirty() ? h('button', { class: 'btn', onclick: () => { if (confirm('Discard unsaved changes?')) load(); } }, 'Discard') : null,
    saveBtn,
    S.auth === 'password'
      ? h('button', { class: 'btn', onclick: async () => { await api('POST', 'logout'); renderLogin(); } }, 'Sign out')
      : h('span', { class: 'mode', title: 'devops.admin.auth=' + S.auth + '. Only this machine can open the UI.' }, 'Local access, no sign-in'));

  const tab = (id, label, count) => h('button', {
    class: S.tab === id ? 'active' : '', onclick: () => { S.tab = id; renderShell(); },
  }, h('span', {}, label), count == null ? null : h('span', { class: 'count' }, count));

  const nav = h('nav', {},
    tab('jenkins', 'Jenkins', S.systems.jenkins.length),
    tab('dockerhub', 'Docker Hub', S.systems.dockerhub.length),
    tab('kubernetes', 'Kubernetes', S.systems.kubernetes.length),
    tab('health', 'Health checks'),
    h('hr'),
    tab('access', 'Secrets & access'),
    tab('yaml', 'systems.yml'),
    S.auth === 'password' ? tab('account', 'Account') : null);

  const main = h('main', {}, banners(), view());
  app().replaceChildren(top, h('div', { class: 'layout' }, nav, main));
}

function markDirty() {
  const d = document.getElementById('dirty');
  const b = document.getElementById('save');
  if (d) d.textContent = isDirty() ? 'Unsaved changes' : '';
  if (b) b.disabled = !isDirty();
}

function banners() {
  const out = [];
  if (S.conflict) {
    out.push(h('div', { class: 'banner warn' }, S.conflict + ' ',
      h('button', { class: 'btn small', onclick: load }, 'Reload (drops your edits)')));
  }
  if (S.errors.length) {
    out.push(h('div', { class: 'banner bad' }, 'Not saved. Fix these first:', h('ul', {}, S.errors.map(e => h('li', {}, e)))));
  }
  return out;
}

function view() {
  if (KINDS[S.tab]) return systemsView(S.tab);
  if (S.tab === 'health') return healthView();
  if (S.tab === 'access') return accessView();
  if (S.tab === 'yaml') return yamlView();
  return accountView();
}

// ---------------------------------------------------------------- systems

function systemsView(kind) {
  const K = KINDS[kind];
  const list = S.systems[kind];
  const add = h('button', {
    class: 'btn', onclick: () => {
      list.push(K.blank());
      S.open[kind + ':' + (list.length - 1)] = true;
      renderShell(); markDirty();
    },
  }, '+ Add ' + K.singular);
  return h('section', {},
    h('div', { class: 'row' }, h('div', { class: 'spacer' }, h('h1', {}, K.label), h('p', { class: 'lead' }, K.lead)), add),
    list.length === 0 ? h('div', { class: 'empty' }, 'No ' + K.singular + ' configured yet.') : null,
    list.map((s, i) => systemCard(kind, s, i)));
}

function systemCard(kind, s, i) {
  const K = KINDS[kind];
  const key = kind + ':' + i;
  const test = S.tests[key];
  const title = h('span', { class: 'title' }, s.name || 'Unnamed ' + K.singular);
  const sub = h('span', { class: 'sub' }, K.sub(s) || '');
  const head = h('div', {
    class: 'card-head', onclick: () => { S.open[key] = !S.open[key]; card.classList.toggle('open'); },
  }, h('span', { class: 'chev' }, '▸'), title, sub, h('span', { class: 'spacer' }),
    s.readOnly ? h('span', { class: 'pill ro' }, 'read-only') : null,
    test ? h('span', { class: 'pill ' + (test.ok ? 'ok' : 'bad') }, test.ok ? 'connected' : 'failed') : null);

  const refresh = () => { title.textContent = s.name || 'Unnamed ' + K.singular; sub.textContent = K.sub(s) || ''; markDirty(); };
  const grid = h('div', { class: 'grid' }, K.fields.map(f => field(s, f, refresh)));

  const result = h('span', { class: 'result' + (test ? (test.ok ? ' ok' : ' bad') : '') },
    test ? (test.ok ? '✓ ' : '✗ ') + test.detail + (test.ms != null ? ' (' + test.ms + ' ms)' : '') : '');
  const testBtn = h('button', {
    class: 'btn small', onclick: async () => {
      testBtn.disabled = true; result.className = 'result'; result.textContent = 'Testing…';
      const r = await api('POST', 'test', { kind, system: s });
      testBtn.disabled = false;
      S.tests[key] = r.ok ? r.data : { ok: false, detail: r.data.error || 'request failed' };
      renderShell();
    },
  }, 'Test connection');
  const foot = h('div', { class: 'card-foot' }, testBtn,
    h('button', {
      class: 'btn small', onclick: () => {
        const copy = JSON.parse(JSON.stringify(s)); copy.name = (s.name || 'copy') + '-copy';
        S.systems[kind].push(copy); S.open[kind + ':' + (S.systems[kind].length - 1)] = true; renderShell(); markDirty();
      },
    }, 'Duplicate'),
    h('button', {
      class: 'btn small danger', onclick: () => {
        if (!confirm('Remove ' + (s.name || 'this ' + K.singular) + '? It is deleted when you save.')) return;
        S.systems[kind].splice(i, 1); S.tests = {}; S.open = {}; renderShell(); markDirty();
      },
    }, 'Remove'),
    result);

  const card = h('div', { class: 'card' + (S.open[key] ? ' open' : '') }, head, h('div', { class: 'card-body' }, grid, foot));
  return card;
}

function field(obj, f, onChange) {
  const id = 'f' + Math.random().toString(36).slice(2, 9);
  const label = h('label', { for: id }, f.label, f.req ? h('span', { class: 'req' }, '*') : null);
  const hint = f.hint ? h('div', { class: 'hint' }, f.hint) : null;
  const cls = 'field' + (f.wide ? ' wide' : '') + (f.warn && obj[f.k] ? ' warn' : '');

  if (f.type === 'bool') {
    const box = h('input', {
      type: 'checkbox', id, checked: !!obj[f.k],
      onchange: e => { obj[f.k] = e.target.checked; onChange(); },
    });
    return h('div', { class: cls }, h('label', { class: 'toggle', for: id }, box, f.label), hint);
  }
  if (f.type === 'list') {
    const ta = h('textarea', {
      id, placeholder: f.ph || '', spellcheck: 'false',
      oninput: e => { obj[f.k] = e.target.value.split(/[\n,]/).map(x => x.trim()).filter(Boolean); onChange(); },
    });
    ta.value = (obj[f.k] || []).join('\n');
    return h('div', { class: cls }, label, ta, hint);
  }
  const input = h('input', {
    type: 'text', id, placeholder: f.ph || '', spellcheck: 'false', autocomplete: 'off',
    oninput: e => { obj[f.k] = e.target.value.trim(); onChange(); },
  });
  input.value = obj[f.k] || '';
  if (f.type !== 'ref') return h('div', { class: cls }, label, input, hint);

  const out = h('div', { class: 'hint' }, f.hint || 'A reference such as access:…, vault:…, file:…, env:… or path:…, never the secret itself.');
  const check = h('button', {
    class: 'btn small', type: 'button', onclick: async () => {
      out.className = 'result'; out.textContent = 'Checking…';
      const r = await api('POST', 'check-ref', { ref: input.value.trim() });
      const ok = r.ok && r.data.ok;
      out.className = 'result ' + (ok ? 'ok' : 'bad');
      out.textContent = (ok ? '✓ ' : '✗ ') + (r.data.detail || r.data.error || 'failed');
    },
  }, 'Check');
  return h('div', { class: cls }, label, h('div', { class: 'ref' }, input, check), out);
}

// ---------------------------------------------------------------- other tabs

function healthView() {
  const hc = S.systems.health || (S.systems.health = { allowedHosts: [] });
  return h('section', {},
    h('h1', {}, 'Health checks'),
    h('p', { class: 'lead' }, 'Limit which hosts health_check_http and release_verify may call. Leave empty to allow any host.'),
    h('div', { class: 'card open' }, h('div', { class: 'card-body' }, h('div', { class: 'grid' },
      field(hc, { k: 'allowedHosts', label: 'Allowed hosts', type: 'list', wide: true, ph: '*.lab.local\n192.168.1.60',
        hint: 'Exact host names, or *.domain for a whole domain.' }, markDirty)))));
}

function accessView() {
  const a = S.access;
  const ref = h('input', { type: 'text', placeholder: 'access:jenkins/main/api-token', spellcheck: 'false' });
  const out = h('span', { class: 'result' });
  return h('section', {},
    h('h1', {}, 'Secrets & access'),
    h('p', { class: 'lead' }, 'Where the agent fetches credentials. These settings live in config.yml next to the agent and are read at startup.'),
    h('dl', { class: 'kv' },
      h('dt', {}, 'Default provider'), h('dd', {}, a.defaultProvider || '-'),
      h('dt', {}, 'Available providers'), h('dd', {}, (a.providers || []).join(', ')),
      h('dt', {}, 'Access server'), h('dd', {}, a.serverUrl ? a.serverUrl + (a.pathTemplate || '') : 'not configured'),
      h('dt', {}, 'Vault'), h('dd', {}, a.vaultAddress || 'not configured'),
      h('dt', {}, 'Secret cache'), h('dd', {}, a.cacheTtl || '-')),
    h('h1', {}, 'Check a reference'),
    h('p', { class: 'lead' }, 'Confirms the agent can resolve it. The value is never shown.'),
    h('div', { class: 'ref' }, ref, h('button', {
      class: 'btn', onclick: async () => {
        out.className = 'result'; out.textContent = 'Checking…';
        const r = await api('POST', 'check-ref', { ref: ref.value.trim() });
        const ok = r.ok && r.data.ok;
        out.className = 'result ' + (ok ? 'ok' : 'bad');
        out.textContent = (ok ? '✓ ' : '✗ ') + (r.data.detail || r.data.error);
      },
    }, 'Check')),
    h('p', {}, out),
    h('dl', { class: 'kv' },
      h('dt', {}, h('code', {}, 'access:<key>')), h('dd', {}, 'access server'),
      h('dt', {}, h('code', {}, 'vault:<path>#<field>')), h('dd', {}, 'HashiCorp Vault KV v2'),
      h('dt', {}, h('code', {}, 'file:<key>')), h('dd', {}, '~/.devops-agent/secrets.yml'),
      h('dt', {}, h('code', {}, 'env:<NAME>')), h('dd', {}, 'environment variable of the agent'),
      h('dt', {}, h('code', {}, 'path:<file>')), h('dd', {}, 'contents of a file')));
}

function yamlView() {
  return h('section', {},
    h('h1', {}, 'systems.yml'),
    h('p', { class: 'lead' }, 'The saved file at ' + S.file + (isDirty() ? '. Unsaved changes are not shown.' : '.')),
    h('pre', { class: 'yaml' }, S.yaml || '# empty'));
}

function accountView() {
  const cur = h('input', { type: 'password', autocomplete: 'current-password' });
  const next = h('input', { type: 'password', autocomplete: 'new-password' });
  const again = h('input', { type: 'password', autocomplete: 'new-password' });
  const out = h('div', { class: 'result' });
  return h('section', {},
    h('h1', {}, 'Account'),
    h('p', { class: 'lead' }, 'Change the admin password. Other sessions are signed out.'),
    h('div', { class: 'card open' }, h('div', { class: 'card-body' },
      h('div', { class: 'grid' },
        h('div', { class: 'field wide' }, h('label', {}, 'Current password'), cur),
        h('div', { class: 'field' }, h('label', {}, 'New password (' + S.minPassword + '+ characters)'), next),
        h('div', { class: 'field' }, h('label', {}, 'Repeat new password'), again)),
      h('div', { class: 'card-foot' }, h('button', {
        class: 'btn primary', onclick: async () => {
          if (next.value !== again.value) { out.className = 'result bad'; out.textContent = 'New passwords do not match'; return; }
          const r = await api('POST', 'password', { current: cur.value, next: next.value });
          out.className = 'result ' + (r.ok ? 'ok' : 'bad');
          out.textContent = r.ok ? '✓ Password changed' : '✗ ' + (r.data.error || 'failed');
          if (r.ok) { cur.value = next.value = again.value = ''; }
        },
      }, 'Change password'), out))));
}

// ---------------------------------------------------------------- start

window.addEventListener('beforeunload', e => { if (isDirty()) { e.preventDefault(); e.returnValue = ''; } });

(async () => {
  try {
    const r = await api('GET', 'session');
    if (r.ok && r.data.authenticated) await load(); else renderLogin(r.status === 403 ? r.data.error : '');
  } catch (e) {
    if (e.message !== 'login') app().textContent = 'Cannot reach the agent: ' + e.message;
  }
})();
