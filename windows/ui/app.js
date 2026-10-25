// NordKey для Windows — окно приложения. Всё общение — только со службой NordKey на этом ПК (127.0.0.1).
'use strict';

// ───────── ключ окна ─────────
const KEY = (() => {
  const m = location.hash.match(/k=([0-9a-f]+)/);
  try {
    if (m) sessionStorage.setItem('nk', m[1]);
    history.replaceState(null, '', location.pathname);
    return m ? m[1] : (sessionStorage.getItem('nk') || '');
  } catch (e) { return m ? m[1] : ''; }
})();

// ───────── связь со службой ─────────
class ApiError extends Error { constructor(code, msg) { super(msg); this.code = code; } }

async function req(method, path, body) {
  let r;
  try {
    r = await fetch(path, {
      method, headers: { 'X-NK-Key': KEY, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: body !== undefined ? JSON.stringify(body) : (method === 'POST' ? '{}' : undefined),
    });
  } catch (e) { throw new ApiError(0, 'Служба NordKey не отвечает. Перезапустите NordKey с ярлыка.'); }
  const text = await r.text();
  let j = {};
  try { j = text ? JSON.parse(text) : {}; } catch (e) { j = {}; }
  if (r.status === 401 && path.startsWith('/api/')) { S.logged_in = false; setTimeout(boot, 0); }
  if (!r.ok) throw new ApiError(r.status, j.error || `Ошибка (${r.status})`);
  return j;
}
const local = (m, p, b) => req(m, '/local' + p, b);
const api = { get: p => req('GET', '/api' + p), post: (p, b) => req('POST', '/api' + p, b || {}) };

// ───────── мелочи ─────────
// null/false среди детей не превращаем в текст «null»
for (const m of ['replaceChildren', 'append']) {
  const orig = Element.prototype[m];
  Element.prototype[m] = function (...k) { return orig.apply(this, k.flat(Infinity).filter(x => x != null && x !== false)); };
}
function h(sel, attrs, ...kids) {
  const [tag, ...cls] = sel.split('.');
  const el = document.createElement(tag || 'div');
  if (cls.length) el.className = cls.join(' ');
  if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) { kids.unshift(attrs); attrs = null; }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k === 'html') el.innerHTML = v;
    else if (k in el && k !== 'list' && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat(Infinity)) if (k != null && k !== false) el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  return el;
}
const P = {
  power: 'M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z',
  shield: 'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z',
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  card: 'M20 4H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V6c0-1.11-.89-2-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2z',
  more: 'M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
  people: 'M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z',
  spark: 'M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z',
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
  refresh: 'M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
  router: 'M20.2 5.9l.8-.8C19.6 3.7 17.8 3 16 3s-3.6.7-5 2.1l.8.8C13 4.8 14.5 4.2 16 4.2s3 .6 4.2 1.7zm-.9.8c-.9-.9-2.1-1.4-3.3-1.4s-2.4.5-3.3 1.4l.8.8c.7-.7 1.6-1 2.5-1 .9 0 1.8.3 2.5 1l.8-.8zM19 13h-2V9h-2v4H5c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-4c0-1.1-.9-2-2-2zM8 18H6v-2h2v2zm3.5 0h-2v-2h2v2zm3.5 0h-2v-2h2v2z',
  robot: 'M20 9V7c0-1.1-.9-2-2-2h-3c0-1.66-1.34-3-3-3S9 3.34 9 5H6c-1.1 0-2 .9-2 2v2c-1.66 0-3 1.34-3 3s1.34 3 3 3v4c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2v-4c1.66 0 3-1.34 3-3s-1.34-3-3-3zM7.5 11.5c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5S9.83 13 9 13s-1.5-.67-1.5-1.5zM16 17H8v-2h8v2zm-1-4c-.83 0-1.5-.67-1.5-1.5S14.17 10 15 10s1.5.67 1.5 1.5S15.83 13 15 13z',
  search: 'M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  lock: 'M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z',
  globe: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z',
  list: 'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
  build: 'M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z',
  share: 'M15 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm-9-2V7H4v3H1v2h3v3h2v-3h3v-2H6zm9 4c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  restart: 'M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z',
  doc: 'M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
  wifi: 'M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z',
  send: 'M2.01 21L23 12 2.01 3 2 10l15 2-15 2z',
  event: 'M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11zM7 10h5v5H7z',
  dns: 'M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z',
  person: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  support: 'M21 12.22C21 6.73 16.74 3 12 3c-4.69 0-9 3.65-9 9.28-.6.34-1 .98-1 1.72v2c0 1.1.9 2 2 2h1v-6.1c0-3.87 3.13-7 7-7s7 3.13 7 7V19h-8v2h8c1.1 0 2-.9 2-2v-1.22c.59-.31 1-.92 1-1.64v-2.3c0-.7-.41-1.31-1-1.62z',
  devices: 'M4 6h18V4H4c-1.1 0-2 .9-2 2v11H0v3h14v-3H4V6zm19 2h-6c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h6c.55 0 1-.45 1-1V9c0-.55-.45-1-1-1zm-1 9h-4v-7h4v7z',
  logout: 'M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z',
  history: 'M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
  campaign: 'M18 11v2h4v-2h-4zm-2 6.61c.96.71 2.21 1.65 3.2 2.39.4-.53.8-1.07 1.2-1.6-.99-.74-2.24-1.68-3.2-2.4-.4.54-.8 1.08-1.2 1.61zM20.4 5.6c-.4-.53-.8-1.07-1.2-1.6-.99.74-2.24 1.68-3.2 2.4.4.53.8 1.07 1.2 1.6.96-.72 2.21-1.65 3.2-2.4zM4 9c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2h1v4h2v-4h1l5 3V6L8 9H4zm11.5 3c0-1.33-.58-2.53-1.5-3.35v6.69c.92-.81 1.5-2.01 1.5-3.34z',
  badge: 'M20 7h-5V4c0-1.1-.9-2-2-2h-2c-1.1 0-2 .9-2 2v3H4c-1.1 0-2 .9-2 2v11c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V9c0-1.1-.9-2-2-2zM9 12c.83 0 1.5.67 1.5 1.5S9.83 15 9 15s-1.5-.67-1.5-1.5S8.17 12 9 12zm3 6H6v-.75c0-1 2-1.5 3-1.5s3 .5 3 1.5V18zm1-9h-2V4h2v5zm5 7.5h-4V15h4v1.5zm0-3h-4V12h4v1.5z',
  key: 'M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z',
  close: 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  copy: 'M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z',
};
function ico(name) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('fill', 'currentColor'); s.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path'); p.setAttribute('d', P[name] || P.more);
  s.append(p); return s;
}
const ago = s => s == null ? '—' : s < 60 ? `${s} с` : s < 3600 ? `${Math.floor(s / 60)} мин` : s < 86400 ? `${Math.floor(s / 3600)} ч` : `${Math.floor(s / 86400)} д`;
const dateRu = iso => (!iso || iso.length < 10) ? '—' : `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}`;
const hm = ts => { const d = new Date(ts * 1000); const p = n => String(n).padStart(2, '0'); return `${p(d.getDate())}.${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`; };
const bytes = n => n < 1024 ? `${n} Б` : n < 1048576 ? `${(n / 1024).toFixed(0)} КБ` : n < 1073741824 ? `${(n / 1048576).toFixed(1)} МБ` : `${(n / 1073741824).toFixed(2)} ГБ`;
const nowS = () => Math.floor(Date.now() / 1000);
const arr = a => Array.isArray(a) ? a : [];

let toastT;
function toast(msg) {
  if (!msg) return;
  const t = document.getElementById('toast');
  t.textContent = msg; t.classList.add('show');
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('show'), Math.min(9000, 2500 + msg.length * 40));
}
// act — действие с сообщением об ошибке; функция может вернуть текст для всплывашки
async function act(f) {
  try { const m = await f(); if (typeof m === 'string') toast(m); } catch (e) { toast('⚠️ ' + (e.message || e)); }
}
async function copy(text, msg) {
  try { await navigator.clipboard.writeText(text); toast(msg || 'Скопировано'); }
  catch (e) { dialog.info('Скопируйте вручную', h('textarea.in.mono', { rows: 6, readOnly: true }, text)); }
}
function download(name, blobOrText, type) {
  const b = blobOrText instanceof Blob ? blobOrText : new Blob([blobOrText], { type: type || 'text/plain' });
  const a = h('a', { href: URL.createObjectURL(b), download: name }); document.body.append(a); a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
}
// внешние ссылки: Telegram — сразу в приложение Telegram, остальное — в браузере
function tgLink(u) {
  const m = /^https:\/\/t\.me\/([A-Za-z0-9_]+)(?:\?(.*))?$/.exec(u || '');
  return m ? `tg://resolve?domain=${m[1]}${m[2] ? '&' + m[2] : ''}` : null;
}
function openExt(u) {
  if (!u) return;
  const tg = tgLink(u);
  if (tg) { location.href = tg; return; }
  window.open(u, '_blank', 'noopener');
}

// ───────── окна ─────────
const dialog = {
  open(title, body, buttons, onClose) {
    const bg = h('div.modal-bg');
    const close = () => { bg.remove(); document.removeEventListener('keydown', esc); onClose && onClose(); };
    const esc = e => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', esc);
    bg.addEventListener('mousedown', e => { if (e.target === bg) close(); });
    const m = h('div.modal', { role: 'dialog', 'aria-modal': 'true' }, title ? h('h2', title) : null, body,
      h('div.btns', buttons.map(b => h('button.btn' + (b.kind ? '.' + b.kind : ''), {
        onclick: async () => { if (b.keep) { await b.do(close); } else { close(); b.do && b.do(); } }, disabled: b.disabled,
      }, b.text))));
    bg.append(m); document.body.append(bg);
    const f = m.querySelector('input,textarea'); if (f) setTimeout(() => f.focus(), 30);
    return { close, el: m };
  },
  info(title, body, extra) { return dialog.open(title, h('div.col', typeof body === 'string' ? h('div.pre', body) : body, extra), [{ text: 'Закрыть', kind: 'text' }]); },
  confirm(title, text, ok, f, danger) {
    return dialog.open(title, h('div.pre.muted', text), [{ text: 'Отмена', kind: 'text' }, { text: ok, kind: danger ? 'danger' : null, do: f }]);
  },
  input(title, hint, placeholder, ok, f, multiline) {
    const inp = multiline ? h('textarea.in', { rows: 5, placeholder }) : h('input.in', { placeholder, spellcheck: false });
    const go = () => { const v = inp.value.trim(); if (!v) return; d.close(); f(v); };
    if (!multiline) inp.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
    const d = dialog.open(title, h('div.col', hint ? h('div.muted', hint) : null, inp), [{ text: 'Отмена', kind: 'text' }, { text: ok, keep: true, do: go }]);
    return d;
  },
};

// ───────── каркас: вкладки и экраны ─────────
let S = {};          // состояние службы (/local/state)
let ME = null;       // /v1/me
let tabs = [], tab = 0, stack = [];
let timers = [], current = null;

function every(ms, f) { const id = setInterval(f, ms); timers.push(id); return id; }
function clearTimers() { timers.forEach(clearInterval); timers = []; }
const can = p => !!ME && arr(ME.account.perms).includes(p);
const isStaff = () => can('clients.view');

function go(fn, ...args) { stack.push({ fn, args }); show(); }
function back() { stack.pop(); show(); }

// page — заготовка экрана: заголовок, кнопки, тело
function page(title, opts = {}) {
  const titleEl = h('h1', title), subEl = h('div.sub', opts.sub || '');
  const top = h('header.top',
    stack.length ? h('button.icon-btn', { title: 'Назад', onclick: back }, ico('back')) : null,
    h('div.grow', titleEl, opts.sub !== undefined ? subEl : null),
    opts.actions || null,
    opts.refresh ? h('button.icon-btn', { title: 'Обновить', onclick: opts.refresh }, ico('refresh')) : null);
  const prog = h('div', { style: { height: '3px' } });
  const body = h('div.body' + (opts.flush ? '.flush' : ''));
  const el = h('section.page', top, prog, body);
  return {
    el, body, top,
    title(t, s) { titleEl.textContent = t; if (s !== undefined) subEl.textContent = s; },
    busy(on) { prog.className = on ? 'bar-progress' : ''; },
  };
}
// load — загрузить и построить; ошибки — с кнопкой «Повторить»
async function load(into, f, build) {
  into.replaceChildren(h('div.loading', h('div.spin')));
  try {
    const d = await f();
    into.replaceChildren(...[].concat(build(d)).filter(Boolean));
  } catch (e) {
    into.replaceChildren(h('div.col', { style: { padding: '24px 0' } }, h('div.card.warn', h('div.t', e.message)),
      h('button.btn.ghost', { onclick: () => load(into, f, build) }, 'Повторить')));
  }
}

function show() {
  clearTimers();
  if (current && current.leave) current.leave();
  const top = stack[stack.length - 1];
  const scr = top ? top.fn(...top.args) : tabs[tab].fn();
  current = scr;
  const root = document.getElementById('app');
  const nav = h('nav.nav', h('div.brand', h('img', { src: 'icon.svg', alt: '' }), 'NordKey'),
    tabs.map((t, i) => h('button' + (i === tab && !stack.length ? '.on' : ''), { onclick: () => { stack = []; tab = i; show(); } }, ico(t.icon), h('span', t.title))));
  root.replaceChildren(h('div.shell', nav, h('main.main', updateBanner(), scr.el)));
}

function updateBanner() {
  const a = ME && ME.app;
  if (!a || !a.win_version || !a.win_url || a.win_version === S.version) return null;
  return h('div.banner', { style: { cursor: 'pointer' }, onclick: () => openExt(a.win_url) }, `Доступна новая версия NordKey для Windows (${a.win_version}) — нажмите, чтобы скачать`);
}

// ───────── вход ─────────
function loginScreen() {
  const root = document.getElementById('app');
  const box = h('div.login');
  root.replaceChildren(box);
  let pollT = null;
  const start = (msg) => {
    clearInterval(pollT);
    const btn = h('button.btn.big.wide', {
      onclick: async () => {
        btn.disabled = true; btn.textContent = 'Подключаюсь…';
        try { waiting(await local('POST', '/login/start')); } catch (e) { start(e.message); }
      },
    }, 'Войти через Telegram');
    box.replaceChildren(
      h('img.logo', { src: 'icon.svg', alt: '' }), h('h1', 'NordKey'), h('div.muted', 'VPN для дома, роутера и компьютера'),
      h('div', { style: { height: '20px' } }),
      h('div.muted', 'Вход — через ваш Telegram-бот NordKey. Пароль не нужен.'), btn,
      msg ? h('div.warnc', msg) : null,
      h('div.small', { style: { color: 'var(--off)', marginTop: '24px' } }, 'NordKey ' + (S.version || '')));
  };
  const waiting = (r) => {
    const tg = r.tg_url || tgLink(r.bot_url);
    if (tg) location.href = tg; else openExt(r.bot_url);
    box.replaceChildren(
      h('img.logo', { src: 'icon.svg', alt: '' }), h('h1', 'NordKey'),
      h('div.card', { style: { width: '100%' } }, h('div.muted', 'В Telegram нажмите это число:'), h('div.pick', String(r.pick)),
        h('div.muted.small', 'Бот покажет три числа — выберите это. Так никто не сможет войти в ваш аккаунт по чужой ссылке.')),
      h('div.row', h('div.spin'), h('span.muted', 'Жду подтверждения в Telegram…')),
      h('button.btn.ghost.wide', { onclick: () => { if (tg) location.href = tg; else openExt(r.bot_url); } }, 'Открыть Telegram ещё раз'),
      h('a.small', { href: r.bot_url, target: '_blank', rel: 'noopener' }, 'Нет Telegram на компьютере? Открыть ссылку в браузере'),
      h('button.btn.text', { onclick: () => { local('POST', '/login/cancel').catch(() => {}); start(); } }, 'Отмена'));
    const until = Date.now() + (r.expires_in || 300) * 1000;
    pollT = setInterval(async () => {
      if (Date.now() > until) { start('Время на подтверждение вышло. Попробуйте ещё раз.'); return; }
      try {
        const p = await local('POST', '/login/poll');
        if (p.status === 'ok') { clearInterval(pollT); boot(); }
        else if (p.status === 'denied') start('Вход отклонён в Telegram. Если входили вы — попробуйте ещё раз и выберите правильное число.');
        else if (p.status === 'expired') start('Время на подтверждение вышло. Попробуйте ещё раз.');
      } catch (e) { /* служба занята — спросим ещё */ }
    }, 2000);
  };
  start();
}

// ───────── VPN ─────────
const MODES = [
  ['smart', 'Как на роутере', 'Через VPN — только то, что не открывается из России (Instagram, YouTube, ChatGPT…). Остальное напрямую, с обычной скоростью.'],
  ['except_ru', 'Всё, кроме российского', 'Весь интернет через VPN, а российские сайты, банки и Госуслуги — напрямую.'],
  ['all', 'Всё через VPN', 'Весь интернет через VPN. Российские сайты и банки могут не открываться.'],
];

function vpnScreen() {
  const pg = page('VPN на этом компьютере', { sub: S.device || '' });
  const btn = h('button.power', { title: 'Включить / выключить VPN' }, ico('power'));
  const title = h('div.state-title'), sub = h('div.muted.small');
  const warn = h('div.col');
  const tiles = h('div.tiles');
  const staff = isStaff();
  const modes = staff ? h('div.modes') : h('div.card',
    h('div.t', 'Как на роутере'),
    h('div.muted.small', 'Через VPN идёт только то, что не открывается из России (Instagram, YouTube, ChatGPT…), и то, что настроила для вас поддержка. ' +
      'Игры, банки, российские сайты и всё остальное — напрямую, с обычной скоростью и пингом.'));
  let lastVer = null;
  const sites = h('div.col');
  const info = h('div.small.muted');
  let busy = false;

  btn.onclick = async () => {
    if (busy) return;
    busy = true;
    const on = S.vpn && (S.vpn.state === 'on' || S.vpn.state === 'connecting');
    if (!on) { S.vpn = { ...(S.vpn || {}), state: 'connecting' }; paint(); }
    try { S = await local('POST', on ? '/disconnect' : '/connect'); }
    catch (e) { toast('⚠️ ' + e.message); await refresh(); }
    busy = false; paint();
  };

  function paint() {
    const v = S.vpn || { state: 'off' };
    const st = busy && v.state !== 'on' ? 'connecting' : v.state;
    btn.className = 'power ' + st;
    const stale = st === 'on' && v.since && nowS() - v.since > 60 && (!v.handshake_ago || v.handshake_ago > 180);
    title.textContent = { on: stale ? 'Нет связи с сервером' : 'VPN включён', connecting: 'Подключаю…', error: 'Не подключилось', off: 'VPN выключен' }[st] || st;
    title.className = 'state-title ' + (st === 'on' ? (stale ? 'warnc' : 'okc') : st === 'error' ? 'warnc' : '');
    sub.textContent = st === 'on' ? `адрес ${v.ip || S.ip || ''} · ${MODES.find(m => m[0] === S.mode)?.[1] || ''} · включён ${ago(nowS() - v.since)}`
      : st === 'off' ? 'Нажмите, чтобы включить' : '';
    tiles.replaceChildren(...(st === 'on' ? [
      tile('Ответ сервера', v.handshake_ago ? ago(v.handshake_ago) : 'ждём…', stale ? 'warn' : 'ok'),
      tile('Получено', bytes(v.rx || 0)), tile('Отправлено', bytes(v.tx || 0)),
      tile('Сайтов в VPN', String((v.dyn_routes || 0))),
    ] : []));
    // предупреждения
    const w = [];
    const pc = S.pc || {};
    const sub_ = pc.subscription;
    if (S.error) w.push(h('div.card.warn', h('div.t', S.error)));
    if (sub_ && !sub_.active && !pc.staff) w.push(h('div.card.warn', h('div.t', 'Подписка закончилась'), h('div', 'VPN на компьютере работает, пока активна подписка. Продлите её во вкладке «Подписка».')));
    if (stale) w.push(h('div.card.warn', h('div.t', 'Сервер не отвечает'), h('div.small', 'Проверьте интернет. Если интернет есть, а связи нет больше пары минут — выключите и включите VPN или напишите в поддержку.')));
    if (st === 'on' && v.dns_ok === false) w.push(h('div.card.warn', h('div.t', 'Windows обходит NordKey при поиске сайтов'),
      h('div.small', 'Сайты по спискам могут идти мимо VPN. Помогает выключить и включить VPN; если не помогло — перезагрузите компьютер. Мешать могут другие VPN и «защитники DNS» в антивирусе.')));
    warn.replaceChildren(...w);
    if (staff) modes.replaceChildren(...MODES.map(([k, t, d]) => h('button.mode' + (S.mode === k ? '.on' : ''), {
      onclick: () => act(async () => { S = await local('POST', '/mode', { mode: k }); paint(); }),
    }, h('span.radio'), h('div', h('div.t', t), h('div.d', d)))));
    const r = S.rules;
    if (r && r.version !== lastVer) { if (lastVer !== null) loadSites(); lastVer = r.version; } // поддержка пустила сайт — покажем
    info.textContent = r ? `Списки сайтов: через VPN ${r.vpn}, напрямую ${r.direct}, сетей ${r.nets} · версия ${r.version}` : 'Списки сайтов загрузятся при первом включении';
  }
  const tile = (k, v, cls) => h('div.tile' + (cls ? '.' + cls : ''), h('div.k', k), h('div.v', v));

  async function loadSites() {
    try {
      const s = await api.get('/v1/pc/sites');
      const rows = [...arr(s.vpn).map(d => [d, 'vpn']), ...arr(s.direct).map(d => [d, 'direct'])];
      sites.replaceChildren(rows.length ? h('div.card', rows.map(([d, m]) => h('div.site',
        h('span.chip', { style: m === 'direct' ? { background: 'var(--ok-soft)', color: 'var(--ok-text)' } : {} }, m === 'vpn' ? 'через VPN' : 'напрямую'),
        h('span.grow', d),
        staff ? h('button.icon-btn', { title: 'Убрать', onclick: () => setSite(d, 'remove') }, ico('close')) : null))) : h('div.muted.small', 'Пока ничего — всё идёт по общим спискам NordKey.'));
    } catch (e) { sites.replaceChildren(h('div.muted.small', e.message)); }
  }
  async function setSite(domain, mode) {
    pg.busy(true);
    await act(async () => {
      const r = await api.post('/v1/pc/site', { domain, mode });
      await local('POST', '/rules/refresh').catch(() => {});
      await loadSites(); await refresh();
      return mode === 'remove' ? 'Убрано' : `✅ ${r.domain} — ${mode === 'vpn' ? 'через VPN' : 'напрямую'}`;
    });
    pg.busy(false);
  }
  const dom = h('input.in', { placeholder: 'сайт, например example.com', spellcheck: false });
  const add = mode => { const d = dom.value.trim(); if (d) { dom.value = ''; setSite(d, mode); } };
  dom.addEventListener('keydown', e => { if (e.key === 'Enter') add('vpn'); });

  pg.body.append(h('div.center.col',
    h('div.power-wrap', btn, title, sub), warn, tiles,
    h('div.sect', staff ? 'Что пускать через VPN' : 'Как работает'), modes,
    h('div.sect', 'Настроено для вас'),
    staff ? h('div.card', h('div.muted.small', 'Сайт не открывается — «через VPN». Банк или российский сайт работает плохо — «напрямую». Проверка не даст сломать то, что должно идти иначе.'),
      dom, h('div.row', h('button.btn', { onclick: () => add('vpn') }, ico('lock'), 'Через VPN'), h('button.btn.ghost', { onclick: () => add('direct') }, ico('globe'), 'Напрямую')))
      : h('div.card.accent', h('div.t', 'Не открывается сайт, игра или приложение?'),
        h('div.small', 'Напишите в поддержку — например «не работает Castle Clash на компьютере». Поддержка проверит и настроит, изменение дойдёт до компьютера само за пару минут.'),
        h('button.btn', { onclick: () => go(supportScreen) }, ico('support'), 'Написать в поддержку')),
    sites, info,
    h('div.row.wrap', h('button.btn.text', { onclick: () => act(async () => { S = await local('POST', '/rules/refresh'); paint(); return 'Списки обновлены'; }) }, 'Обновить списки'))));
  paint(); loadSites();
  const refresh = async () => { try { S = await local('GET', '/state'); paint(); } catch (e) { } };
  every(2000, refresh);
  return pg;
}

// ───────── роутеры ─────────
function routerRow(r, extra, onclick) {
  const rtt = r.rtt, slow = rtt != null && rtt > 120, online = !!r.online;
  const s = r.subscription;
  return h('div.card.click', { onclick }, h('div.row',
    h('span.dot', { style: { background: !online ? 'var(--off)' : slow ? 'var(--warn)' : 'var(--ok)' } }),
    h('div.grow', h('div.t', r.name || r.ip), h('div.row.small.muted', h('span.mono', (r.ip || '').replace('10.66.66.', '')),
      r.migrated === false ? h('span.warnc', 'старый сервер') : null, extra ? h('span', extra) : null)),
    h('div', { style: { textAlign: 'right' } },
      h('div.t', { style: { color: !online ? 'var(--muted)' : slow ? 'var(--warn-text)' : '' } }, !online ? 'офлайн' : rtt != null ? `${Math.round(rtt)} мс` : 'онлайн'),
      s ? h('div.small', { style: { color: s.active ? 'var(--muted)' : 'var(--warn-text)' } }, s.active ? 'до ' + dateRu(s.expires_at).slice(0, 5) : 'нет подписки') : null)));
}

function linkDialog(after) {
  dialog.input('Добавить роутер', 'Введите код из бота (например ABCD-EFGH). Код действует 10 минут.', 'ABCD-EFGH', 'Добавить',
    code => act(async () => { const r = await api.post('/v1/link', { code }); after && after(); return r.message; }));
}

function myRoutersScreen() {
  const pg = page('Мои роутеры', { refresh: () => run() });
  const run = () => load(pg.body, () => api.get('/v1/routers'), j => h('div.center.col',
    arr(j.routers).length ? null : h('div.card', h('div.t', 'Пока нет роутеров'), h('div.muted.small',
      'Если NordKey настроил вам роутер — откройте персональную ссылку из бота. Если роутер оформлен на другой аккаунт Telegram — получите там код в «🛜 Мой роутер» → «👥 Поделиться роутером».')),
    arr(j.routers).map(r => routerRow(r, null, () => go(routerScreen, r.ip))),
    h('button.btn.ghost', { onclick: () => linkDialog(run) }, ico('add'), 'Добавить роутер по коду')));
  run();
  return pg;
}

function pingChart(data) {
  const pts = arr(data && data.points).map(p => p.rtt);
  if (!pts.length) return h('div.muted.small', 'Данных пока нет — сервер собирает их каждые 5 минут.');
  const vals = pts.filter(v => v != null);
  const max = Math.max(80, ...vals);
  const med = vals.length ? [...vals].sort((a, b) => a - b)[Math.floor(vals.length / 2)] : null;
  return h('div.col', h('div.chart', pts.map(v => h('i' + (v == null ? '.n' : v > 120 ? '.w' : ''), { style: { height: (v == null ? 3 : Math.max(3, v / max * 96)) + 'px' }, title: v == null ? 'нет связи' : Math.round(v) + ' мс' }))),
    h('div.muted.small', `обычно ≈ ${med != null ? Math.round(med) : '—'} мс · максимум ${vals.length ? Math.round(Math.max(...vals)) : '—'} мс`));
}

function jobDialog(id, title, onDone) {
  const body = h('div.col');
  let t;
  const d = dialog.open(title, body, [{ text: 'Закрыть', kind: 'text' }], () => { clearInterval(t); onDone && onDone(); });
  const poll = async () => {
    let j;
    try { j = await api.get('/v1/jobs/' + id); } catch (e) { return; }
    const st = j.status || 'running';
    body.replaceChildren(
      st === 'running' ? h('div.row', h('div.spin'), h('span.muted', 'Сервер работает с роутером, это может занять несколько минут…'))
        : st === 'done' ? h('div.t.okc', '✅ Готово') : h('div.t.badc', '❌ Не получилось'),
      j.output ? h('div.mono.pre.muted', { style: { maxHeight: '300px', overflow: 'auto' } }, String(j.output).slice(-1500)) : null);
    if (st !== 'running') clearInterval(t);
  };
  poll(); t = setInterval(poll, 3000);
  return d;
}

function actTile(text, icon, f, o = {}) {
  return h('button.act' + (o.primary ? '.primary' : '') + (o.risky ? '.risky' : ''), { onclick: f, disabled: o.enabled === false }, ico(icon), h('span', text));
}

function routerScreen(ip) {
  const pg = page(ip, { sub: ip, refresh: () => run() });
  let hours = 24, live = null;
  const run = () => load(pg.body, () => api.get('/v1/routers/' + ip), r => build(r));
  const busy = async f => { pg.busy(true); try { await act(f); } finally { pg.busy(false); } };

  function site(action, domain) {
    busy(async () => {
      const r = await api.post(`/v1/routers/${ip}/site`, { action, domain });
      if (action === 'check') { checkResult(r); return; }
      if (!r.ok) return r.error || 'Не получилось';
      const w = arr(r.warn).join('\n');
      return (action === 'vpn' ? `✅ ${domain} теперь через VPN` : action === 'direct' ? `✅ ${domain} теперь напрямую` : '✅ Убрано') + (w ? '\n' + w : '');
    });
  }
  function checkResult(r) {
    const dom = r.domain || '';
    let text;
    if (!r.ok) text = r.error || 'Не получилось проверить';
    else if (!r.resolved) text = `Сайт ${dom} не найден — проверьте адрес.`;
    else {
      text = `Сайт ${dom} идёт ${r.via === 'vpn' ? 'через VPN' : 'напрямую (без VPN)'}${r.how ? ' — ' + r.how : ''}.\n`;
      text += (r.loss == null || r.loss >= 100) ? 'Сайт не отвечает на проверку связи (это не всегда значит, что он не работает).' : `Отвечает: потери ${r.loss}%, пинг ${r.rtt != null ? Math.round(r.rtt) : '—'} мс.`;
    }
    const btns = [{ text: 'Закрыть', kind: 'text' }];
    if (r.resolved) btns.push(r.via === 'vpn' ? { text: 'Напрямую', kind: 'ghost', do: () => site('direct', dom) } : { text: 'Через VPN', do: () => site('vpn', dom) });
    dialog.open('Проверка: ' + dom, h('div.pre', text), btns);
  }
  const ask = action => dialog.input(
    { check: 'Проверить сайт', vpn: 'Сайт через VPN', direct: 'Сайт напрямую' }[action],
    { check: 'Какой сайт не открывается?', vpn: 'Этот сайт и все его поддомены пойдут через VPN.', direct: 'Этот сайт пойдёт мимо VPN, через обычный интернет (например, банки и госуслуги).' }[action],
    'example.com', action === 'check' ? 'Проверить' : 'Готово', d => site(action, d));
  const textDlg = (title, j) => dialog.info(title, h('div.mono.pre', { style: { maxHeight: '420px', overflow: 'auto' } }, pretty(j)));

  function build(r) {
    pg.title(r.name || ip, ip);
    const level = r.level, staff = level === 'staff', canWrite = !staff || can('routers.act');
    const online = !!r.online, rtt = r.rtt, sub = r.subscription;
    const liveBox = h('div');
    const paintLive = () => liveBox.replaceChildren(live
      ? h('div.muted.small', `Роутер: загрузка ${live.cpu}% · работает ${ago(live.uptime)} · устройств в сети ${live.devices}`)
      : h('button.btn.text', { onclick: () => busy(async () => { const l = await api.get(`/v1/routers/${ip}/live`); if (l.ok) { live = l; paintLive(); devTile.querySelector('.v').textContent = l.devices; } else return l.error || 'Роутер не ответил'; }) }, 'Спросить роутер: устройства, загрузка'));
    const devTile = h('div.tile', h('div.k', 'Устройств'), h('div.v', live ? String(live.devices) : '—'));
    paintLive();
    const chartBox = h('div');
    const seg = h('div.seg');
    const loadHist = () => {
      seg.replaceChildren(...[[24, '24 ч'], [168, '7 д'], [336, '14 д']].map(([v, t]) => h('button' + (hours === v ? '.on' : ''), { onclick: () => { hours = v; loadHist(); } }, t)));
      load(chartBox, () => api.get(`/v1/routers/${ip}/history?hours=${hours}`), pingChart);
    };
    loadHist();
    const game = r.game && r.game.available ? (() => {
      const cb = h('input', { type: 'checkbox', checked: !!r.game.on, disabled: !canWrite, style: { width: '20px', height: '20px' } });
      cb.onchange = () => act(async () => { const x = await api.post(`/v1/routers/${ip}/game`, { on: cb.checked }); cb.checked = !!x.on; return x.on ? 'Игровой режим включён' : 'Выключен'; });
      return h('div.card', h('label.row', { style: { cursor: 'pointer' } }, h('div.grow', h('div.t', 'Игровой режим'), h('div.muted.small', 'подскажет пустить игровой сервер напрямую, если через VPN высокий пинг')), cb));
    })() : null;
    const access = arr(r.access);
    return h('div.center.col',
      h('div.tiles',
        h('div.tile' + (online ? '.ok' : '.warn'), h('div.k', 'Туннель'), h('div.v', online ? ago(r.handshake_ago) : 'офлайн')),
        h('div.tile' + (rtt > 120 ? '.warn' : ''), h('div.k', 'Пинг'), h('div.v', rtt != null ? Math.round(rtt) + ' мс' : '—')),
        devTile,
        h('div.tile' + (sub && !sub.active ? '.warn' : ''), h('div.k', 'Подписка'), h('div.v', sub ? (sub.active ? dateRu(sub.expires_at).slice(0, 5) : 'нет') : '—'))),
      r.migrated === false ? h('div.card.warn', h('div.t', 'Роутер ещё на старом сервере — часть функций недоступна, пока его не перенесут.')) : null,
      liveBox,
      h('div.card', h('div.row', h('div.t.grow', 'Пинг до сервера'), seg), chartBox),
      h('div.sect', 'Сайты'),
      h('div.acts',
        actTile('Проверить сайт', 'search', () => ask('check'), { primary: true }),
        actTile('Сайт через VPN', 'lock', () => ask('vpn'), { enabled: canWrite }),
        actTile('Сайт напрямую', 'globe', () => ask('direct'), { enabled: canWrite }),
        actTile('Мои сайты', 'list', () => busy(async () => {
          const l = await api.get(`/v1/routers/${ip}/sites`);
          if (!l.ok) return l.error;
          const lst = k => arr(l[k]).map(x => '• ' + x).join('\n') || '—';
          dialog.info('Сайты на роутере', `Через VPN:\n${lst('vpn')}\n\nНапрямую:\n${lst('direct')}`);
        })),
        actTile('Починить', 'build', () => act(async () => { const x = await api.post(`/v1/routers/${ip}/fix`); jobDialog(x.job, 'Починка роутера', run); }), { primary: true, enabled: canWrite }),
        (level === 'owner' || (staff && can('routers.admin'))) ? actTile('Поделиться', 'share', () => shareDlg(staff)) : null),
      game,
      staff ? [
        h('div.sect', 'Для сотрудников'),
        h('div.acts',
          actTile('Google через VPN', 'globe', () => act(async () => { const x = await api.post(`/v1/staff/routers/${ip}/google`); jobDialog(x.job, 'Сети Google через VPN'); }), { enabled: can('routers.admin') }),
          actTile('Перезапустить туннель', 'restart', () => busy(async () => { await api.post(`/v1/staff/agent/routers/${ip}/restart`); return 'Туннель перезапускается'; }), { risky: true, enabled: can('routers.act') }),
          actTile('Лог роутера', 'doc', () => busy(async () => textDlg('Лог роутера', await api.get(`/v1/staff/agent/routers/${ip}/log`)))),
          actTile('Wi-Fi', 'wifi', () => busy(async () => textDlg('Wi-Fi', await api.get(`/v1/staff/agent/routers/${ip}/wifi`)))),
          actTile('Написать клиенту', 'send', () => dialog.input('Сообщение клиенту', 'Придёт от бота NordKey.', '', 'Отправить',
            t => act(async () => { await api.post('/v1/staff/msg', { ip, text: t }); return 'Отправлено'; }), true), { enabled: can('msg') }),
          actTile('Продлить подписку', 'event', () => dialog.input('Продлить подписку', 'На сколько дней?', '30', 'Продлить', t => act(async () => {
            const days = parseInt(t, 10); if (!days) return 'Нужно число дней';
            const x = await api.post('/v1/staff/subscription/extend', { ip, days }); run(); return 'Продлено до ' + dateRu(x.expires_at);
          })), { enabled: can('subs.edit') }),
          actTile('Российский DNS для сайта', 'dns', () => dialog.input('Российский DNS для сайта',
            'Для этого сайта роутер будет спрашивать DNS Яндекса напрямую — сайт отдаст ближние российские серверы (помогает, когда российский сайт медленный или без картинок).',
            'avito.ru', 'Включить', d => busy(async () => { const x = await api.post(`/v1/staff/routers/${ip}/dnssplit`, { domain: d }); return x.ok ? `✅ Готово: ${d} через российский DNS` : x.error; })), { enabled: can('routers.act') }),
          actTile('Перезагрузить роутер', 'power', () => dialog.confirm('Перезагрузить роутер?', 'Интернет у клиента пропадёт на 1–2 минуты.', 'Перезагрузить',
            () => act(async () => { await api.post(`/v1/staff/routers/${ip}/reboot`); return 'Роутер перезагружается'; }), true), { risky: true, enabled: can('routers.admin') }),
          r.owner_tg ? actTile('Аккаунт клиента', 'person', () => go(accountScreen, r.owner_tg)) : null),
        h('button.btn.ghost', { onclick: () => investigate(ip) }, ico('search'), 'Глубокая проверка сайта (автопилот)'),
        can('newclient') ? h('button.btn.ghost', { onclick: () => act(async () => { const x = await api.post('/v1/staff/claim', { ip }); await copy(`Ваша персональная ссылка NordKey (пробный период ${x.trial_hours} ч): ${x.url}`, 'Ссылка для клиента скопирована'); }) }, 'Персональная ссылка на бота для клиента') : null,
      ] : null,
      access.length ? [h('div.sect', 'Доступ к роутеру'), h('div.card', access.map(a => h('div.list-row',
        ico(a.level === 'owner' ? 'person' : 'people'),
        h('div.grow', h('div', a.name || (a.username ? '@' + a.username : 'ID ' + a.tg_id)),
          h('div.muted.small', a.main ? 'основной владелец' : a.level === 'owner' ? 'полный доступ' : 'участник')),
        a.main ? null : h('button.btn.text.badc', { onclick: () => dialog.confirm('Убрать доступ?', `${a.name || 'Этот аккаунт'} больше не увидит роутер в приложении.`, 'Убрать',
          () => act(async () => { await api.post(`/v1/routers/${ip}/access/remove`, { tg_id: a.tg_id }); run(); return 'Доступ убран'; }), true) }, 'Убрать'))))] : null);
  }
  function shareDlg(staff) {
    const mk = level => act(async () => {
      const r = await api.post(`/v1/routers/${ip}/share`, { level });
      const text = `Код доступа к роутеру NordKey: ${r.code}\nИли откройте ссылку в Telegram: ${r.bot_url}`;
      dialog.open('Код доступа', h('div.col', h('div.pick', { style: { fontSize: '34px' } }, r.code),
        h('div.muted.small', 'Введите его в приложении NordKey на другом аккаунте («Добавить роутер по коду») или откройте ссылку в Telegram с этого аккаунта.')),
        [{ text: 'Закрыть', kind: 'text', do: run }, { text: 'Скопировать', do: () => copy(text) }]);
    });
    dialog.open('Поделиться роутером', h('div.muted', `Для кого код? Он действует ${staff ? 'сутки' : '10 минут'} и срабатывает один раз.`), [
      { text: 'Отмена', kind: 'text' },
      { text: 'Член семьи / участник', kind: 'ghost', do: () => mk('member') },
      { text: 'Второй аккаунт / полный доступ', do: () => mk('owner') }]);
  }
  run();
  return pg;
}

function pretty(j) {
  if (j && typeof j.log === 'string') return j.log;
  if (j && Array.isArray(j.lines)) return j.lines.join('\n');
  return JSON.stringify(j, null, 2);
}

function investigate(ip) {
  dialog.input('Глубокая проверка сайта', 'Автопилот проверит маршрут, DNS, блокировку рекламы и нагрузку роутера и запишет выводы в инцидент.', 'avito.ru', 'Проверить', domain => {
    const body = h('div.row', h('div.spin'), h('span.muted', 'Проверяю… это 1–2 минуты'));
    let stop = false;
    const d = dialog.open('Проверка ' + domain, body, [{ text: 'Свернуть', kind: 'text' }], () => { stop = true; });
    (async () => {
      try {
        const jid = (await api.post('/v1/staff/guard/investigate', { ip, domain })).job;
        while (!stop) {
          await new Promise(r => setTimeout(r, 3000));
          const j = await api.get('/v1/jobs/' + jid);
          if (j.status === 'running') continue;
          const inc = j.result && j.result.incident;
          if (inc) { d.close(); go(incidentScreen, inc); } else body.replaceChildren(h('div.warnc.pre', 'Не получилось: ' + (j.output || '')));
          break;
        }
      } catch (e) { body.replaceChildren(h('div.warnc', e.message)); }
    })();
  });
}

// ───────── подписка, устройства, поддержка, сессии ─────────
function subscriptionScreen() {
  const pg = page('Подписка', { refresh: () => run() });
  const run = () => load(pg.body, () => api.get('/v1/subscription'), j => {
    const s = j.subscription, active = !!(s && s.active);
    return h('div.center.col',
      h('div.card' + (active ? '.ok' : '.warn'),
        h('div', { style: { fontSize: '20px', fontWeight: 700, color: active ? 'var(--ok-text)' : 'var(--warn-text)' } }, active ? 'Подписка активна' : 'Подписки нет'),
        s ? h('div', (active ? `до ${dateRu(s.expires_at)} · осталось ${s.days_left} дн.` : `закончилась ${dateRu(s.expires_at)}`) + (s.trial ? ' · пробный период' : '')) : null),
      h('button.btn.big.wide', { onclick: () => openExt(j.pay_url) }, active ? 'Продлить' : 'Оформить подписку'),
      h('div.muted.small', 'Оплата проходит в Telegram-боте NordKey. После оплаты срок здесь обновится сам. Подписка общая: роутер, телефон и этот компьютер.'));
  });
  run();
  return pg;
}

function devicesScreen() {
  const pg = page('Мои устройства', { sub: 'VPN для телефона (AmneziaWG)', refresh: () => run() });
  const run = () => load(pg.body, () => api.get('/v1/devices'), j => h('div.center.col',
    h('div.card.accent', h('div.t', 'Телефон'), h('div', { style: { fontSize: '14px' } }, '1. Установите на телефон AmneziaWG.\n2. Скачайте файл ниже и откройте его в AmneziaWG (или отправьте себе в Telegram).\n3. Включите VPN.'),
      h('div.small', 'Этому компьютеру отдельный конфиг не нужен — VPN включается на вкладке «VPN».')),
    arr(j.devices).length ? arr(j.devices).map(d => h('div.card', h('div.row', ico('key'), h('div.grow', h('div.t', d.name || 'NordKey'), h('div.mono.muted', d.ip || ''))),
      h('div.row', h('button.btn', { onclick: () => download((d.name || 'NordKey') + '.conf', d.conf) }, 'Скачать файл'),
        h('button.btn.ghost', { onclick: () => copy(d.conf, 'Конфиг скопирован') }, 'Скопировать')),
      h('div.warnc.small', 'Не пересылайте этот файл посторонним — по нему можно подключиться к вашему VPN.')))
      : h('div.card', h('div.t', 'Пока нет конфигураций для телефона.'), h('div.muted.small', 'Их выдаёт бот: «📄 Мой конфиг». Для роутера отдельный конфиг не нужен — он уже настроен.'))));
  run();
  return pg;
}

function supportScreen() {
  const pg = page('Поддержка', { sub: 'ответим здесь и в Telegram', flush: true });
  const chat = h('div.chat');
  const inp = h('textarea.in', { rows: 1, placeholder: 'Сообщение' });
  const send = h('button.btn', 'Отправить');
  let lastN = -1;
  const paint = async () => {
    try {
      const j = await api.get('/v1/support');
      const msgs = arr(j.messages);
      if (msgs.length === lastN) return;
      if (lastN >= 0 && msgs.length && msgs[msgs.length - 1].sender !== 'client') local('POST', '/rules/refresh').catch(() => {}); // поддержка могла пустить сайт
      lastN = msgs.length;
      chat.replaceChildren(...(msgs.length ? msgs.map(m => h('div.bubble' + (m.sender === 'client' ? '.mine' : m.sender === 'ai' ? '.ai' : ''), m.text))
        : [h('div.muted', 'Опишите проблему — например «не открывается YouTube на компьютере». Поддержка сразу проверит ваш VPN и роутер.')]),
        msgs.length && msgs[msgs.length - 1].sender === 'client' ? h('div.muted.small', 'Проверяем…') : '');
      chat.scrollTop = chat.scrollHeight;
    } catch (e) { }
  };
  const go_ = () => {
    const t = inp.value.trim(); if (!t) return;
    inp.value = ''; send.disabled = true;
    act(async () => { await api.post('/v1/support', { text: t }); lastN = -1; await paint(); }).finally(() => { send.disabled = false; });
  };
  send.onclick = go_;
  inp.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); go_(); } });
  pg.body.append(chat, h('div.compose', inp, send));
  paint(); every(5000, paint);
  return pg;
}

function sessionsScreen() {
  const pg = page('Где выполнен вход', { refresh: () => run() });
  const run = () => load(pg.body, () => api.get('/v1/me/sessions'), j => h('div.center.col',
    arr(j.sessions).map(s => h('div.card', h('div.row', ico('devices'),
      h('div.grow', h('div.t', s.device + (s.current ? ' · это устройство' : '')), h('div.muted.small', `активность ${ago(nowS() - s.last_seen)} назад`)),
      s.current ? null : h('button.btn.text.badc', { onclick: () => act(async () => { await api.post('/v1/me/sessions/revoke', { id: s.id }); run(); return 'Устройство отключено'; }) }, 'Выйти')))),
    h('button.btn.ghost', { onclick: () => dialog.confirm('Выйти везде?', 'Все другие устройства, где вы вошли в NordKey, потребуют войти заново.', 'Выйти везде',
      () => act(async () => { const r = await api.post('/v1/me/sessions/revoke', { all: true }); run(); return 'Отключено устройств: ' + r.revoked; })) }, 'Выйти на всех других устройствах')));
  run();
  return pg;
}

function logScreen() {
  const pg = page('Журнал NordKey', { sub: 'для поддержки', refresh: () => run(), actions: h('button.icon-btn', { title: 'Скопировать', onclick: async () => copy(((await local('GET', '/log')).lines || []).join('\n'), 'Журнал скопирован') }, ico('copy')) });
  const run = () => load(pg.body, () => local('GET', '/log'), j => h('div.mono.pre.muted', arr(j.lines).slice().reverse().join('\n') || 'Пусто'));
  run();
  return pg;
}

function moreScreen() {
  const pg = page('Ещё');
  const a = ME.account;
  const item = (t, icon, f, danger) => h('div.menu' + (danger ? '.danger' : ''), { onclick: f, role: 'button', tabindex: 0 }, ico(icon), h('span.grow', t));
  pg.body.append(h('div.center.col',
    h('div.card', h('div.row', h('span.linkc', { style: { width: '40px', height: '40px', display: 'grid' } }, ico('person')),
      h('div', h('div.t', a.name || 'Аккаунт'), h('div.muted.small', a.role_name || '')))),
    isStaff() ? [
      h('div.sect', 'Работа'),
      can('msg') ? item('Обращения в поддержку', 'support', () => go(supportInboxScreen)) : null,
      can('newclient') ? item('Новый клиент — ссылка на бота', 'add', () => go(newClientScreen)) : null,
      can('bat') ? item('Батник для настройки роутера', 'doc', () => go(batScreen)) : null,
      can('broadcast') ? item('Рассылка клиентам', 'campaign', () => go(broadcastScreen)) : null,
      can('users.view') ? item('Сотрудники и роли', 'badge', () => go(usersScreen)) : null,
      can('audit.view') ? item('Журнал действий', 'history', () => go(auditScreen)) : null,
      h('div.sect', 'Моё'),
      item('Мои роутеры', 'router', () => go(myRoutersScreen)),
      item('Моя подписка', 'card', () => go(subscriptionScreen)),
    ] : [
      item('Добавить роутер по коду', 'add', () => linkDialog()),
      item('Поддержка', 'support', () => go(supportScreen)),
    ],
    item('Телефон: конфиг AmneziaWG', 'key', () => go(devicesScreen)),
    item('Где выполнен вход', 'devices', () => go(sessionsScreen)),
    h('div.sect', 'Этот компьютер'),
    item('Журнал NordKey', 'doc', () => go(logScreen)),
    item('Отключить этот компьютер от VPN', 'close', () => dialog.confirm('Отключить компьютер?',
      'Ключ VPN этого компьютера перестанет работать. Включить VPN снова можно в любой момент — выдастся новый ключ.', 'Отключить',
      () => act(async () => { S = await local('POST', '/forget'); show(); return 'Компьютер отключён от VPN'; }), true)),
    item('Выйти из аккаунта', 'logout', () => dialog.confirm('Выйти?', 'VPN на этом компьютере выключится. Чтобы включить снова — войдите заново.', 'Выйти',
      () => act(async () => { await local('POST', '/logout'); ME = null; boot(); }), true), true),
    h('div.small', { style: { color: 'var(--off)', padding: '8px' } }, `NordKey для Windows ${S.version || ''}`)));
  return pg;
}

// ───────── сотрудники ─────────
function dashboardScreen() {
  const pg = page('NordKey', { sub: 'srv1 · Стокгольм · AmneziaWG', refresh: () => run() });
  const run = () => load(pg.body, () => api.get('/v1/staff/dashboard'), d => {
    const s = d.server || {}, services = d.services || {}, r = d.routers || {};
    const down = Object.keys(services).filter(k => services[k] !== 'active');
    const chip = (t, ok) => h('span.chip', { style: ok === undefined ? {} : { background: ok ? 'var(--ok-soft)' : 'var(--warn-soft)', color: ok ? 'var(--ok-text)' : 'var(--warn-text)' } }, t);
    return h('div.center.col',
      h('div.card', h('div.row', h('span.dot', { style: { background: down.length ? 'var(--warn)' : 'var(--ok)' } }), h('div.t', down.length ? 'Есть проблемы' : 'Сервер в норме')),
        h('div.tiles', [['CPU', Math.round(s.cpu || 0) + '%'], ['Память', Math.round(s.ram_pct || 0) + '%'],
          ['Трафик', s.net_tx_rate != null ? Math.round(s.net_tx_rate * 8 / 1e6) + ' Мб/с' : '—'], ['Работает', ago(s.uptime)]].map(([k, v]) => h('div', h('div.muted.small', k), h('div', { style: { fontSize: '18px', fontWeight: 600 } }, v)))),
        h('div.row.wrap', Object.keys(services).map(k => chip(k, services[k] === 'active')), chip('WARP · Google ' + (d.warp && d.warp.on ? '✓' : 'выкл')))),
      h('div.tiles', h('div.tile', h('div.k', 'роутеров онлайн'), h('div.v', `${r.online}/${r.total}`)), h('div.tile', h('div.k', 'перенесено'), h('div.v', `${r.migrated}/${r.total}`)),
        h('div.tile', h('div.k', 'подписок'), h('div.v', d.subscriptions_active != null ? String(d.subscriptions_active) : '—'))),
      arr(d.alerts).length ? [h('div.sect', 'Тревоги'), arr(d.alerts).map(a => h('div.card.warn' + (a.ip ? '.click' : ''), { onclick: a.ip ? () => go(routerScreen, a.ip) : null }, h('div.t', a.name || 'Сервер'), h('div', a.text)))] : null,
      h('div.sect', 'Последние события'),
      h('div.card', arr(d.events).length ? arr(d.events).slice(0, 15).map(e => h('div.list-row', { style: { cursor: e.ip ? 'pointer' : 'default' }, onclick: e.ip ? () => go(routerScreen, e.ip) : null },
        h('span.dot', { style: { width: '8px', height: '8px', background: e.kind === 'offline' ? 'var(--warn)' : e.kind === 'online' ? 'var(--ok)' : 'var(--link)' } }),
        h('div', h('div', e.text), h('div.muted.small', `${e.ip || 'сервер'} · ${hm(e.ts)}`)))) : h('div.muted', 'Пока событий нет')));
  });
  run();
  return pg;
}

function clientsScreen() {
  const pg = page('Клиенты', { refresh: () => run() });
  let q = '', filter = 'all', all = [], findT;
  const list = h('div.col'), found = h('div.col'), chips = h('div.seg');
  const inp = h('input.in', { placeholder: 'Имя, IP или @ник', spellcheck: false });
  const problem = c => (c.online && (c.rtt || 0) > 120) || (c.subscription && c.subscription.active === false);
  const F = { all: () => true, problems: problem, offline: c => !c.online && c.migrated, old: c => !c.migrated };
  const paint = () => {
    chips.replaceChildren(...[['all', 'Все'], ['problems', 'Проблемы'], ['offline', 'Офлайн'], ['old', 'На старом']].map(([k, t]) =>
      h('button' + (filter === k ? '.on' : ''), { onclick: () => { filter = k; paint(); } }, `${t} ${all.filter(F[k]).length}`)));
    const ql = q.toLowerCase();
    const shown = all.filter(c => (!q || ['name', 'ip', 'tg_name'].some(k => String(c[k] || '').toLowerCase().includes(ql))) && F[filter](c));
    list.replaceChildren(...shown.map(c => routerRow(c, null, () => go(routerScreen, c.ip))));
  };
  inp.addEventListener('input', () => {
    q = inp.value.trim(); paint();
    clearTimeout(findT); found.replaceChildren();
    if (q.length >= 3) findT = setTimeout(async () => {
      try {
        const r = await api.get('/v1/staff/find?q=' + encodeURIComponent(q));
        const acc = arr(r.found);
        found.replaceChildren(...(acc.length ? [h('div.sect', 'Аккаунты в боте'), ...acc.map(a => h('div.card.click', { onclick: () => go(accountScreen, a.tg_id) },
          h('div.t', (a.name || 'Без имени') + (a.username ? '  @' + a.username : '')),
          h('div.muted.small', `ID ${a.tg_id} · ` + (a.subscription && a.subscription.active ? 'подписка до ' + dateRu(a.subscription.expires_at) : 'без подписки') + (arr(a.routers).length ? ' · роутер ' + a.routers[0] : ''))))] : []));
      } catch (e) { }
    }, 400);
  });
  const run = () => load(list, () => api.get('/v1/staff/clients'), j => { all = arr(j.clients); paint(); return [...list.childNodes]; });
  pg.body.append(h('div.center.col', inp, chips, list, found));
  run();
  return pg;
}

const ST = {
  need_approval: ['Нужно решение', 'var(--accent-text)', 'var(--accent-soft)'], escalated: ['Сложный случай', 'var(--warn-text)', 'var(--warn-soft)'],
  open: ['Наблюдаю', 'var(--warn-text)', 'var(--warn-soft)'], auto_fixed: ['Починил сам', 'var(--ok-text)', 'var(--ok-soft)'],
  fixed: ['Исправлено', 'var(--ok-text)', 'var(--ok-soft)'], resolved: ['Прошло само', 'var(--muted)', 'var(--surface)'],
};
const stChip = s => { const [t, fg, bg] = ST[s] || ['Закрыто', 'var(--muted)', 'var(--surface)']; return h('span.chip', { style: { color: fg, background: bg } }, t); };

function autopilotScreen() {
  const pg = page('Автопилот', { sub: 'следит за сервером и роутерами, чинит простое сам', refresh: () => run() });
  const SET = [['enabled', 'Автопилот включён'], ['auto_fix', 'Чинить простое сам'], ['ai_support', 'ИИ отвечает клиентам в поддержке'],
    ['ai_triage', 'ИИ разбирает сложные случаи'], ['auto_sites', 'Сам пускать заблокированные сайты через VPN'], ['notify_sites', 'Сообщать клиенту, что сайт включён'], ['notify', 'Сообщать мне в Telegram']];
  const row = i => h('div.card.click', { onclick: () => go(incidentScreen, i.id) }, h('div.t', i.title), h('div.row.small', stChip(i.status), h('span.muted', hm(i.updated)), i.ip ? h('span.mono.muted', i.ip) : null));
  const run = () => load(pg.body, () => api.get('/v1/staff/guard'), j => {
    const s = j.settings || {}, list = arr(j.incidents);
    const active = list.filter(i => ['need_approval', 'escalated', 'open'].includes(i.status)), done = list.filter(i => !active.includes(i));
    return h('div.center.col',
      h('div.card', SET.map(([k, t]) => {
        const cb = h('input', { type: 'checkbox', checked: s[k] !== false, disabled: !can('routers.admin'), style: { width: '18px', height: '18px' } });
        cb.onchange = () => act(async () => { await api.post('/v1/staff/guard/settings', { [k]: cb.checked }); });
        return h('label.list-row', { style: { cursor: 'pointer' } }, h('span.grow', t), cb);
      }), h('div.muted.small', 'Сам: перезапуск служб сервера, чистка диска, починка DNS на роутере, WARP. По кнопке: перезагрузка роутера, изменения маршрутов и DNS. Никогда: удаление, оплаты, роли.')),
      active.length ? [h('div.sect', 'Требует внимания'), active.map(row)] : h('div.card.ok', h('div.t.okc', 'Всё спокойно — ничего не требует внимания')),
      done.length ? [h('div.sect', 'За последние 3 дня'), done.map(row)] : null);
  });
  run(); every(30000, run);
  return pg;
}

function incidentScreen(id) {
  const pg = page('Инцидент', { refresh: () => run() });
  const run = () => load(pg.body, () => api.get('/v1/staff/guard/incidents/' + id), i => h('div.center.col',
    h('div', { style: { fontSize: '20px', fontWeight: 700 } }, i.title),
    h('div.row', stChip(i.status), h('span.muted.small', 'с ' + hm(i.created))),
    i.detail ? h('div.pre', i.detail) : null,
    i.ip ? h('button.btn.ghost', { onclick: () => go(routerScreen, i.ip) }, 'Открыть роутер ' + i.ip) : null,
    arr(i.actions).length ? [h('div.sect', 'Что можно сделать'), arr(i.actions).map((a, n) => h('button.btn.wide', {
      disabled: a.done || !can('routers.act'),
      onclick: () => dialog.confirm('Выполнить?', a.title, 'Выполнить', () => act(async () => { const x = await api.post(`/v1/staff/guard/incidents/${id}/approve`, { n }); jobDialog(x.job, 'Выполняю', run); })),
    }, (a.done ? '✓ ' : '') + a.title))] : null,
    i.prompt ? h('div.card.warn', h('div.t', 'Сложный случай'), h('div', 'Автопилот собрал всё, что выяснил. Скопируйте запрос и отправьте его Claude — там будут шаги для сервера.'),
      h('button.btn', { onclick: () => copy(i.prompt, 'Запрос скопирован') }, ico('copy'), 'Скопировать запрос для Claude')) : null,
    h('div.sect', 'Что происходило'),
    h('div.card', arr(i.log).slice().reverse().map(l => h('div', { style: { padding: '3px 0' } }, h('div.muted.small', hm(l.ts)), h('div.pre', l.text)))),
    ['open', 'need_approval', 'escalated'].includes(i.status) && can('routers.act') ? h('button.btn.text', { style: { color: 'var(--muted)' }, onclick: () => act(async () => { await api.post(`/v1/staff/guard/incidents/${id}/dismiss`); run(); return 'Закрыто'; }) }, 'Закрыть — разобрался сам') : null));
  run();
  return pg;
}

const TOOL_RU = { site_route: 'маршрут сайта', fix_router: 'починка роутера', dns_split: 'российский DNS для сайта', google_via_vpn: 'Google через VPN', restart_tunnel: 'перезапуск туннеля' };
const QUICK = [['Что сейчас горит?', 'Что сейчас горит? Разберись и почини, что можно.'], ['Кто офлайн?', 'Какие роутеры сейчас не на связи?'],
  ['Кончаются подписки', 'У кого подписка кончается в ближайшие 3 дня?'], ['Перенести подписку', 'Перенеси подписку и конфиги с @ на @'],
  ['Не работает сайт', 'У клиента  не работает сайт . Разберись и почини.'], ['Продлить подписку', 'Продли подписку клиенту @ на 30 дней'], ['Найти клиента', 'Найди клиента ']];
const aiMsgs = []; // переживает переключение вкладок
function transferSummary(p) {
  const who = a => a ? `${a.name || ''}${a.username ? ' @' + a.username : ''} (ID ${a.tg_id})` : '?';
  const cfg = arr(p.configs).map(c => `#${c.id} ${c.client_ip}`).join(', ');
  const warn = arr(p.warnings).map(w => '⚠️ ' + w).join('\n');
  return `Откуда: ${who(p.from)}\nКуда: ${who(p.to)}\n\nКонфиги: ${cfg || 'нет'}\nПодписка до: ${dateRu(p.expires_at)}` + (warn ? '\n\n' + warn : '') + '\n\nVPN у клиента не прервётся. Перед переносом сервер сделает копию базы.';
}
function assistantScreen() {
  const pg = page('ИИ-оператор', { sub: 'сам проверяет и чинит; деньги и сообщения — по кнопке', flush: true,
    actions: h('button.btn.text', { onclick: () => { aiMsgs.length = 0; paint(); } }, 'Новый чат') });
  const chat = h('div.chat');
  const inp = h('textarea.in', { rows: 1, placeholder: 'Что сделать? Например: перенеси подписку…' });
  const sendB = h('button.btn', ico('send'));
  let thinking = false;
  const paint = () => {
    chat.replaceChildren(...(aiMsgs.length ? [] : [h('div.card', h('div.t', 'Что умею'), h('div.muted.pre.small',
      '• найти клиента по @нику, имени или адресу\n• проверить и починить роутер, сайт, DNS\n• пустить сайт через VPN или напрямую\n• перенести или продлить подписку, написать клиенту — по кнопке\n• сложное — передам в Автопилот с готовым запросом для Claude'))]),
    ...aiMsgs.map(m => h('div.col', { style: { alignItems: m.mine ? 'flex-end' : 'flex-start', gap: '6px' } },
      h('div.bubble' + (m.mine ? '.mine' : ''), m.text),
      arr(m.done).map(d => h('span.chip', { style: { color: d.ok ? 'var(--ok-text)' : 'var(--warn-text)', background: d.ok ? 'var(--ok-soft)' : 'var(--warn-soft)' } }, (d.ok ? '✓ сделал: ' : '✗ не вышло: ') + (TOOL_RU[d.tool] || d.tool))),
      arr(m.pending).map(p => h('div.card.accent', { style: { maxWidth: '520px' } }, h('div.small.t', 'Нужно ваше подтверждение'), h('div.t', { style: { color: 'var(--text)' } }, p.title),
        p.preview ? h('div.muted.small.pre', transferSummary(p.preview)) : null,
        p.done ? h('div.okc', '✓ Выполнено') : h('button.btn', { onclick: () => dialog.confirm('Выполнить?', p.title + (p.preview ? '\n\n' + transferSummary(p.preview) : ''), 'Выполнить', () => act(async () => {
          const r = await api.post('/v1/staff/ai/approve', { id: p.id }); p.done = true;
          aiMsgs.push({ text: '✅ ' + r.title + (r.result && r.result.expires_at ? ' — до ' + dateRu(r.result.expires_at) : '') }); paint();
        })) }, 'Выполнить'))),
      m.escalated ? h('button.btn.ghost', { onclick: () => go(incidentScreen, m.escalated) }, 'Открыть в Автопилоте') : null)),
    thinking ? h('div.muted', 'Проверяю… (до минуты, если нужно спросить роутер)') : '');
    chat.scrollTop = chat.scrollHeight;
  };
  const send = async q => {
    const history = aiMsgs.slice(-12).map(m => ({ role: m.mine ? 'user' : 'model', text: String(m.text).slice(0, 1500) }));
    aiMsgs.push({ mine: true, text: q }); thinking = true; sendB.disabled = true; paint();
    try {
      const r = await api.post('/v1/staff/ai/chat', { question: q, history });
      aiMsgs.push({ text: r.answer || '…', done: r.done, pending: r.pending, escalated: r.escalated || 0 });
    } catch (e) { aiMsgs.push({ text: '⚠️ ' + e.message }); }
    thinking = false; sendB.disabled = false; paint();
  };
  const go_ = () => { const q = inp.value.trim(); if (!q || thinking) return; inp.value = ''; send(q); };
  sendB.onclick = go_;
  inp.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); go_(); } });
  pg.body.append(chat, h('div.quick', QUICK.map(([l, p]) => h('button.btn.ghost', { style: { padding: '5px 10px', fontSize: '12.5px' }, onclick: () => { if (/[?.]$/.test(p)) send(p); else { inp.value = p; inp.focus(); } } }, l))),
    h('div.compose', inp, sendB));
  paint();
  return pg;
}

function accountScreen(tg) {
  const pg = page('Клиент', { sub: 'ID ' + tg, refresh: () => run() });
  const bubble = m => h('div.col', { style: { alignItems: m.sender === 'client' ? 'flex-start' : 'flex-end', gap: '2px' } },
    h('div.muted.small', (m.sender === 'client' ? 'Клиент' : m.sender === 'ai' ? '🤖 ИИ' : 'Сотрудник') + ' · ' + hm(m.ts)),
    h('div.bubble' + (m.sender === 'client' ? '' : m.sender === 'ai' ? '.ai' : '.mine'), m.text));
  const run = () => load(pg.body, () => api.get(`/v1/staff/accounts/${tg}/info`), a => {
    pg.title(a.name || 'Клиент', (a.username ? '@' + a.username + ' · ' : '') + 'ID ' + tg);
    const s = a.subscription, active = !!(s && s.active);
    const reply = h('textarea.in', { rows: 2, placeholder: 'Ответ клиенту (придёт в Telegram)' });
    return h('div.center.col',
      h('div.card' + (active ? '.ok' : '.warn'), h('div.t', { style: { color: active ? 'var(--ok-text)' : 'var(--warn-text)' } },
        active ? 'Подписка до ' + dateRu(s.expires_at) : s ? 'Подписка закончилась ' + dateRu(s.expires_at) : 'Подписки нет'),
        s && s.trial ? h('div.muted.small', 'пробный период') : null,
        can('subs.edit') ? h('div.row', h('button.btn', { onclick: () => dialog.input('Продлить подписку', 'На сколько дней?', '30', 'Продлить', t => act(async () => {
          const days = parseInt(t, 10); if (!days) return 'Нужно число дней';
          const r = await api.post('/v1/staff/subscription/extend', { tg_id: tg, days }); run(); return 'Продлено до ' + dateRu(r.expires_at);
        })) }, 'Продлить'), h('button.btn.ghost', { onclick: () => dialog.input('Перенести подписку', 'На какой аккаунт? Человек должен хотя бы раз нажать «Старт» в боте.', '@ник или ID', 'Дальше', dst => act(async () => {
          const p = await api.post('/v1/staff/transfer', { from: String(tg), to: dst, dry: true });
          dialog.confirm('Перенести?', transferSummary(p), 'Перенести', () => act(async () => {
            const r = await api.post('/v1/staff/transfer', { from: String(tg), to: dst, dry: false }); back(); return `Перенесено: конфигов ${r.moved_configs}, подписок ${r.moved_subscriptions}`;
          }));
        })) }, 'Перенести')) : null),
      arr(a.routers).length ? [h('div.sect', 'Роутеры'), arr(a.routers).map(ip => h('button.btn.ghost.wide', { onclick: () => go(routerScreen, ip) }, 'Роутер ' + ip))] : null,
      arr(a.configs).length ? [h('div.sect', 'Конфиги'), arr(a.configs).map(c => h('div.card', h('div.row', ico(c.kind === 'router' ? 'router' : 'devices'),
        h('div.grow', h('div.t', `#${c.id} · ${c.ip}`), h('div.muted.small', (c.kind === 'router' ? 'роутер' : c.kind === 'pc' ? 'компьютер' : 'телефон/ПК') + (c.active === 1 ? '' : ' · выключен'))),
        c.kind === 'phone' && c.active === 1 && can('msg') ? h('button.btn.text', { onclick: () => act(async () => { await api.post(`/v1/staff/configs/${c.id}/send`); return 'Конфиг отправлен клиенту в Telegram'; }) }, 'Отправить') : null)))] : null,
      h('div.sect', 'Переписка с поддержкой'),
      h('div.card', arr(a.support).length ? arr(a.support).map(bubble) : h('div.muted', 'Обращений не было'),
        can('msg') ? [reply, h('button.btn', { onclick: () => { const t = reply.value.trim(); if (!t) return; reply.value = ''; act(async () => { await api.post(`/v1/staff/support/${tg}/reply`, { text: t }); run(); return 'Отправлено'; }); } }, 'Ответить')] : null),
      arr(a.payments).length ? [h('div.sect', 'Платежи'), h('div.card', arr(a.payments).map(p => h('div.small', `${dateRu(p.created_at)} · ${p.amount} ₽ · ${p.plan} · ${p.status}`)))] : null);
  });
  run(); every(15000, () => { if (!document.querySelector('.modal') && !(document.activeElement && document.activeElement.tagName === 'TEXTAREA')) run(); });
  return pg;
}

function supportInboxScreen() {
  const pg = page('Обращения в поддержку', { sub: 'ИИ отвечает сам, сложное — в Автопилот', refresh: () => run() });
  const run = () => load(pg.body, () => api.get('/v1/staff/support'), j => h('div.center.col', arr(j.threads).length ? arr(j.threads).map(t => h('div.card.click', { onclick: () => go(accountScreen, t.tg_id) },
    h('div.row', h('div.t.grow', t.name || (t.username ? '@' + t.username : 'ID ' + t.tg_id)), h('span.muted.small', hm(t.ts))),
    h('div.muted.small', (t.sender === 'client' ? '⏳ ' : t.sender === 'ai' ? '🤖 ' : '✅ ') + t.text))) : h('div.empty', 'Обращений пока нет')));
  run(); every(20000, run);
  return pg;
}

function usersScreen() {
  const pg = page('Сотрудники и роли', { refresh: () => run() });
  const roles = [['admin', 'Админ'], ['tech', 'Техник'], ['support', 'Поддержка']];
  const run = () => load(pg.body, () => api.get('/v1/staff/accounts?staff=1'), j => h('div.center.col',
    h('div.card.accent', h('div.t', 'Роли'), h('div.small.pre', 'Админ — всё, кроме ролей.\nТехник — роутеры, новые клиенты, батники (без денег и рассылок).\nПоддержка — видит клиентов и пишет им.')),
    arr(j.accounts).map(a => h('div.card' + (can('users.manage') && a.role !== 'owner' ? '.click' : ''), {
      onclick: can('users.manage') && a.role !== 'owner' ? () => dialog.open(a.name || 'Сотрудник', h('div.muted', `Сейчас: ${a.role_name}. Новая роль:`),
        [{ text: 'Отмена', kind: 'text' }, ...[...roles, ['client', 'Снять роль']].map(([k, t]) => ({ text: t, kind: k === 'client' ? 'danger' : 'ghost',
          do: () => act(async () => { await api.post(`/v1/staff/accounts/${a.tg_id}/role`, { role: k }); run(); return 'Готово'; }) }))]) : null,
    }, h('div.row', ico('badge'), h('div.grow', h('div.t', a.name || 'ID ' + a.tg_id), h('div.muted.small', `${a.role_name}${a.username ? ' · @' + a.username : ''} · устройств: ${a.sessions || 0}`))))),
    can('users.manage') ? h('button.btn', { onclick: () => dialog.open('Пригласить', h('div.muted', 'Человек откроет ссылку в Telegram, и роль привяжется к его аккаунту. Ссылка действует 48 часов, один раз.'),
      [{ text: 'Отмена', kind: 'text' }, ...roles.map(([k, t]) => ({ text: t, kind: 'ghost', do: () => act(async () => { const r = await api.post('/v1/staff/invites', { role: k }); await copy(`Приглашение в NordKey (${r.role_name}): ${r.bot_url}`, 'Приглашение скопировано — отправьте его сотруднику'); }) }))]) }, ico('add'), 'Пригласить сотрудника') : null));
  run();
  return pg;
}

function auditScreen() {
  const pg = page('Журнал действий', { refresh: () => run() });
  const run = () => load(pg.body, () => api.get('/v1/staff/audit?limit=300'), j => h('div.center.col', arr(j.audit).map(a => h('div.card', { style: { padding: '10px', gap: '2px' } },
    h('div.row', h('div.t.grow', { style: { color: a.ok === 0 ? 'var(--bad)' : '' } }, a.action), h('span.muted.small', hm(a.ts))),
    h('div.muted.small', `${a.role || ''} ${a.tg_id || ''} · ${a.target || ''} ${a.detail || ''}`.trim())))));
  run();
  return pg;
}

function batScreen() {
  const pg = page('Батник для настройки');
  const router = h('input.in', { placeholder: '192.168.1.1 (необязательно)', spellcheck: false });
  const client = h('input.in', { placeholder: '10.66.66.19', spellcheck: false });
  const ok = () => !router.value.trim() || /^\d{1,3}(\.\d{1,3}){3}$/.test(router.value.trim());
  const q = () => router.value.trim() ? '?router=' + encodeURIComponent(router.value.trim()) : '';
  pg.body.append(h('div.center.col',
    h('div.card', h('div.t', 'Один файл для всех случаев'), h('div.muted.small', 'Запускается на компьютере, подключённом к роутеру Keenetic: старых клиентов переносит на новый сервер, новых — подключает (нужен код администратора). После настройки новый клиент получает персональную ссылку на бота.')),
    h('div.muted.small', 'Адрес роутера — только если у роутера нестандартный адрес'), router,
    h('button.btn', { onclick: () => { if (!ok()) return toast('Неверный адрес роутера'); act(async () => { await api.post('/v1/staff/bat/send', { to: 'me', router: router.value.trim() || null }); return 'Отправлено вам в Telegram'; }); } }, ico('send'), 'Себе в Telegram'),
    h('button.btn.ghost', { onclick: () => { if (!ok()) return toast('Неверный адрес роутера'); act(async () => {
      const r = await fetch('/api/v1/staff/bat' + q(), { headers: { 'X-NK-Key': KEY } });
      if (!r.ok) throw new Error('Не удалось получить батник (' + r.status + ')');
      download('NordKey_setup.bat', await r.blob()); return 'Сохранено в «Загрузки»';
    }); } }, 'Скачать на этот компьютер'),
    h('div.sect', 'Клиенту через бота'), client,
    h('button.btn.ghost', { onclick: () => { if (!ok() || !/^10\.66\.66\.\d{1,3}$/.test(client.value.trim())) return toast('Нужен адрес вида 10.66.66.X'); act(async () => { await api.post('/v1/staff/bat/send', { ip: client.value.trim(), to: 'client', router: router.value.trim() || null }); return 'Батник отправлен клиенту'; }); } }, 'Отправить клиенту в Telegram')));
  return pg;
}

function broadcastScreen() {
  const pg = page('Рассылка');
  const targets = [['not_migrated', 'Ещё на старом сервере'], ['routers', 'Все клиенты с роутером'], ['expiring', 'Подписка кончается за 3 дня'], ['all', 'Все пользователи бота']];
  let target = 'not_migrated', preview = null;
  const radios = h('div.card'), info = h('div.muted.small'), text = h('textarea.in', { rows: 6, placeholder: 'Текст сообщения' });
  const sendB = h('button.btn.wide', 'Отправить');
  const paint = async () => {
    radios.replaceChildren(...targets.map(([k, t]) => h('label.list-row', { style: { cursor: 'pointer' } }, h('input', { type: 'radio', name: 'tg', checked: target === k, onchange: () => { target = k; paint(); } }), t)));
    preview = null; info.textContent = 'Считаю получателей…';
    try {
      preview = await api.post('/v1/staff/broadcast', { target, dry: true });
      info.textContent = `Получателей: ${preview.count}` + (arr(preview.recipients).length ? '\n' + arr(preview.recipients).slice(0, 30).map(r => r.name || r.tg_id).join(', ') : '');
    } catch (e) { info.textContent = e.message; }
  };
  sendB.onclick = () => {
    if (!text.value.trim() || !preview || !preview.count) return toast('Нужен текст и хотя бы один получатель');
    dialog.confirm('Отправить рассылку?', `Сообщение уйдёт ${preview.count} получателям от бота NordKey.`, 'Отправить', () => act(async () => {
      const r = await api.post('/v1/staff/broadcast', { target, text: text.value, dry: false }); text.value = ''; return 'Рассылка запущена: ' + r.count;
    }));
  };
  info.classList.add('pre');
  pg.body.append(h('div.center.col', radios, info, text, sendB));
  paint();
  return pg;
}

function newClientScreen() {
  const pg = page('Новый клиент', { refresh: () => run() });
  const run = () => load(pg.body, () => api.get('/v1/staff/clients'), j => h('div.center.col',
    h('div.card.accent', h('div.t', 'Как подключить нового клиента'), h('div.small.pre', '1. Отправьте батник (Ещё → Батник) и запустите его у клиента — роутер настроится сам.\n2. Скрипт сам выдаст персональную ссылку на бота с пробным днём.\n3. Если ссылка потерялась — выберите роутер ниже, и приложение сделает новую.')),
    arr(j.clients).filter(c => c.owner_tg == null).map(c => routerRow(c, 'без Telegram', () => act(async () => {
      const x = await api.post('/v1/staff/claim', { ip: c.ip });
      await copy(`Ваша персональная ссылка NordKey (пробный период ${x.trial_hours} ч): ${x.url}`, 'Ссылка для клиента скопирована');
    })))));
  run();
  return pg;
}

// ───────── запуск ─────────
async function boot() {
  stack = [];
  if (!KEY) {
    document.getElementById('app').replaceChildren(h('div.login', h('img.logo', { src: 'icon.svg', alt: '' }), h('h1', 'NordKey'),
      h('div.muted', 'Откройте NordKey с ярлыка на рабочем столе или в меню «Пуск».')));
    return;
  }
  try { S = await local('GET', '/state'); } catch (e) {
    document.getElementById('app').replaceChildren(h('div.login', h('img.logo', { src: 'icon.svg', alt: '' }), h('h1', 'NordKey'),
      h('div.warnc', e.message), h('button.btn', { onclick: boot }, 'Повторить')));
    return;
  }
  if (!S.logged_in) { ME = null; loginScreen(); return; }
  try { ME = await api.get('/v1/me'); } catch (e) {
    if (e.code === 401) { loginScreen(); return; }
    ME = { account: { name: '', role: 'client', perms: [] } }; // нет связи с сервером — хотя бы VPN
    toast('⚠️ ' + e.message);
  }
  const staff = isStaff();
  tabs = [{ title: 'VPN', icon: 'shield', fn: vpnScreen }];
  if (staff) {
    tabs.push({ title: 'Главная', icon: 'home', fn: dashboardScreen }, { title: 'Клиенты', icon: 'router', fn: clientsScreen }, { title: 'Автопилот', icon: 'robot', fn: autopilotScreen });
    if (can('ai')) tabs.push({ title: 'ИИ', icon: 'spark', fn: assistantScreen });
  } else {
    tabs.push({ title: 'Роутеры', icon: 'router', fn: myRoutersScreen }, { title: 'Подписка', icon: 'card', fn: subscriptionScreen });
  }
  tabs.push({ title: 'Ещё', icon: 'more', fn: moreScreen });
  if (tab >= tabs.length) tab = 0;
  show();
}

window.addEventListener('hashchange', () => { if (/k=/.test(location.hash)) location.reload(); });
document.addEventListener('keydown', e => { if (e.key === 'Escape' && stack.length && !document.querySelector('.modal')) back(); if (e.altKey && e.key === 'ArrowLeft' && stack.length) back(); });
boot();
