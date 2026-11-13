'use strict';
/* Minimal, safe markdown for usage guides: builds DOM nodes, never innerHTML.
   Headings, paragraphs, bullet and numbered lists, fenced code (with copy), inline code, bold,
   italic and http(s) links. {{placeholders}} are filled from `vars` first. */

function fillPlaceholders(text, vars) {
  return String(text || '').replace(/\{\{\s*([a-z]+)\s*\}\}/g, (m, k) => (vars && vars[k]) ? vars[k] : `<${k}>`);
}

function mdInline(text) {
  const out = [];
  // order matters: code first so ** inside code stays literal
  const re = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\[[^\]]+\]\((https?:\/\/[^)\s]+)\))|(\*[^*\s][^*]*\*)|(_[^_\s][^_]*_)/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index));
    const t = m[0];
    if (m[1]) out.push(h('code', {}, t.slice(1, -1)));
    else if (m[2]) out.push(h('strong', {}, mdInline(t.slice(2, -2))));
    else if (m[3]) {
      const label = t.slice(1, t.indexOf(']('));
      out.push(h('a', { href: m[4], target: '_blank', rel: 'noopener noreferrer' }, label));
    } else out.push(h('em', {}, t.slice(1, -1)));
    last = m.index + t.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

function codeBlock(code, lang) {
  const copy = h('button', { class: 'ghost small copy', type: 'button', 'aria-label': 'Copy code', onclick: () => copyText(code, copy) }, 'Copy');
  return h('div', { class: 'code' }, h('div', { class: 'code-head' }, h('span', {}, lang || ''), copy), h('pre', {}, h('code', {}, code)));
}

function renderMarkdown(src, vars) {
  const lines = fillPlaceholders(src, vars).replace(/\r/g, '').split('\n');
  const root = h('div', { class: 'md' });
  let i = 0;
  let para = [];
  const flush = () => { if (para.length) { root.append(h('p', {}, mdInline(para.join(' ')))); para = []; } };
  while (i < lines.length) {
    const line = lines[i];
    const fence = line.match(/^\s*```\s*([\w-]*)\s*$/);
    if (fence) {
      flush();
      const indent = line.match(/^\s*/)[0].length;
      const buf = [];
      i++;
      while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) { buf.push(lines[i].slice(Math.min(indent, lines[i].match(/^\s*/)[0].length))); i++; }
      i++;
      root.append(codeBlock(buf.join('\n'), fence[1]));
      continue;
    }
    const hd = line.match(/^(#{1,4})\s+(.*)$/);
    if (hd) { flush(); root.append(h('h' + Math.min(6, hd[1].length + 2), {}, mdInline(hd[2]))); i++; continue; }
    const li = line.match(/^(\s*)([-*]|\d+\.)\s+(.*)$/);
    if (li) {
      flush();
      const ordered = /\d/.test(li[2]);
      const list = h(ordered ? 'ol' : 'ul');
      while (i < lines.length) {
        const m = lines[i].match(/^(\s*)([-*]|\d+\.)\s+(.*)$/);
        if (!m || /\d/.test(m[2]) !== ordered) break;
        const item = h('li', {}, mdInline(m[3]));
        i++;
        // continuation lines and nested fenced code belong to the item
        while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s+/.test(lines[i])) {
          const f = lines[i].match(/^\s*```\s*([\w-]*)\s*$/);
          if (f) {
            const indent = lines[i].match(/^\s*/)[0].length;
            const buf = [];
            i++;
            while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) { buf.push(lines[i].slice(Math.min(indent, lines[i].match(/^\s*/)[0].length))); i++; }
            i++;
            item.append(codeBlock(buf.join('\n'), f[1]));
          } else { item.append(' ', ...mdInline(lines[i].trim())); i++; }
        }
        list.append(item);
      }
      root.append(list);
      continue;
    }
    if (!line.trim()) { flush(); i++; continue; }
    para.push(line.trim());
    i++;
  }
  flush();
  return root;
}
