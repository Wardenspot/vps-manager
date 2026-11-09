"use strict";
// Окно NordDesk: отрисовка экрана на canvas, ввод, перетаскивание файлов и
// статус. Вся сетевая работа — в Go-бэкенде (локальный WebSocket).

const TOKEN = window.NDSK_TOKEN || "";
const MARKER = window.NDSK_MARKER || "NordDesk · сеанс";
let ws = null;
let screenW = 0, screenH = 0;
let connected = false;
let handoff = false;
let self = { id: "", code: "", state: "connecting" };

const $ = (id) => document.getElementById(id);
const home = $("home");
const sessionScreen = $("session");
const statusEl = $("status");
const canvas = $("screen");
const ctx = canvas.getContext("2d", { alpha: false });

// 123456789 → «123 456 789», 482913 → «482 913»
const spaced = (s) => (s || "").replace(/(\d{3})(?=\d)/g, "$1 ");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- связь с бэкендом ---
function openWS() {
  ws = new WebSocket(`ws://${location.host}/ws?token=${TOKEN}`);
  ws.binaryType = "arraybuffer";
  ws.onmessage = onMessage;
  ws.onclose = () => { if (!handoff) setTimeout(openWS, 1000); };
}
function sendCmd(obj) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
}
function sendBytes(arr) {
  if (ws && ws.readyState === 1) ws.send(new Uint8Array(arr));
}
const u16 = (n) => [(n >> 8) & 0xff, n & 0xff];

function onMessage(ev) {
  if (typeof ev.data === "string") {
    handleEvent(JSON.parse(ev.data));
    return;
  }
  const b = new Uint8Array(ev.data);
  if (b.length >= 9 && b[0] === 2) drawTile(b);
}

function handleEvent(m) {
  switch (m.ev) {
    case "self": renderSelf(m); break;
    case "status": statusEl.textContent = m.text; break;
    case "screeninfo": startSession(m.w, m.h); break;
    case "hostinfo": renderHostInfo(m); break;
    case "notice": toast(m.text, 7000); break;
    case "xfer": toast(m.text, m.done ? 4000 : 0); break;
    case "clip":
      if (navigator.clipboard && m.text) navigator.clipboard.writeText(m.text).catch(() => {});
      break;
    case "ended": endSession(m.text || "Сеанс завершён."); break;
    case "handoff": startHandoff(m.token); break;
  }
}

// --- «Это рабочее место» ---
const STATES = {
  connecting: ["", "Подключение к серверу…"],
  offline: ["bad", "Нет связи с сервером"],
  ready: ["ok", "Готов к подключению"],
  incoming: ["warn", "Запрос на подключение — ответьте в окне Windows"],
  active: ["live", "Вашим компьютером сейчас управляют"],
};

function renderSelf(m) {
  const disabled = m.state === "disabled";
  $("selfBody").classList.toggle("hidden", disabled);
  $("selfDisabled").classList.toggle("hidden", !disabled);
  if (disabled) return;
  self = m;
  $("adminBadge").classList.toggle("hidden", !m.admin);
  $("selfId").textContent = m.id ? spaced(m.id) : "··· ··· ···";
  $("selfCode").textContent = m.code ? spaced(m.code) : "постоянный пароль";
  const [cls, text] = STATES[m.state] || ["", m.state];
  $("selfState").className = "state " + cls;
  $("selfStateText").textContent = m.note ? `${text} (${m.note})` : text;
  $("endIncomingBtn").classList.toggle("hidden", !(m.state === "active" || m.state === "incoming"));
  const failed = $("selfFailed");
  failed.classList.toggle("hidden", !(m.failed > 0));
  if (m.failed > 0) {
    failed.textContent = `Неверный код вводили ${m.failed} раз подряд. Если это не ваш знакомый — кто-то подбирает код; нажмите ↻ для нового кода.`;
  }
}

$("newCodeBtn").addEventListener("click", () => sendCmd({ cmd: "newcode" }));
$("endIncomingBtn").addEventListener("click", () => sendCmd({ cmd: "endincoming" }));
$("copyBtn").addEventListener("click", async () => {
  const text = `NordDesk\nID: ${spaced(self.id)}\nКод: ${spaced(self.code)}`;
  try {
    await navigator.clipboard.writeText(text);
    flashButton($("copyBtn"), "Скопировано ✓");
  } catch {
    flashButton($("copyBtn"), "Не удалось скопировать");
  }
});
function flashButton(btn, text) {
  const old = btn.textContent;
  btn.textContent = text;
  setTimeout(() => (btn.textContent = old), 1500);
}

// --- «Подключиться к другому» ---
$("connectBtn").addEventListener("click", () => {
  const id = $("id").value.replace(/\D+/g, "");
  const code = $("code").value.replace(/\s+/g, "");
  if (!id) { statusEl.textContent = "Введите ID."; return; }
  if (id === self.id) { statusEl.textContent = "Это ID этого же компьютера — введите ID другого."; return; }
  statusEl.textContent = "Подключение…";
  sendCmd({ cmd: "connect", id, code });
});
$("id").addEventListener("keydown", (e) => { if (e.key === "Enter") $("code").focus(); });
$("code").addEventListener("keydown", (e) => { if (e.key === "Enter") $("connectBtn").click(); });

// --- экран ---
function drawTile(b) {
  const dv = new DataView(b.buffer, b.byteOffset);
  const x = dv.getUint16(1), y = dv.getUint16(3);
  const w = dv.getUint16(5), h = dv.getUint16(7);
  const blob = new Blob([b.subarray(9)], { type: "image/jpeg" });
  createImageBitmap(blob).then((bmp) => {
    ctx.drawImage(bmp, x, y, w, h);
    if (bmp.close) bmp.close();
  }).catch(() => {});
}

let peerId = "", hostInfo = "";
function startSession(w, h) {
  screenW = w; screenH = h;
  canvas.width = w; canvas.height = h;
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, w, h);
  connected = true;
  peerId = $("id").value.replace(/\D+/g, "");
  // Метка в заголовке: по ней перехватчик клавиатуры узнаёт окно сеанса.
  document.title = `${MARKER} · ${spaced(peerId)}`;
  home.classList.add("hidden");
  sessionScreen.classList.remove("hidden");
  renderPeer();
  canvas.focus();
}

function renderHostInfo(m) {
  hostInfo = [m.pc, m.user, m.admin ? "администратор" : ""].filter(Boolean).join(" · ");
  renderPeer();
}
function renderPeer() {
  $("peer").textContent = [spaced(peerId), hostInfo, screenW ? `${screenW}×${screenH}` : ""].filter(Boolean).join("  ·  ");
}

function endSession(text) {
  connected = false;
  document.title = "NordDesk";
  hostInfo = "";
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  sessionScreen.classList.add("hidden");
  home.classList.remove("hidden");
  statusEl.textContent = text || "";
}

let toastTimer = 0;
function toast(text, ms = 4000) {
  const t = $("toast");
  t.textContent = text;
  t.classList.remove("hidden");
  clearTimeout(toastTimer);
  if (ms > 0) toastTimer = setTimeout(() => t.classList.add("hidden"), ms);
}

// Та сторона (это мы — входящая) перезапустилась с правами администратора:
// дожидаемся новой копии и переходим в её окно.
async function startHandoff(token) {
  handoff = true;
  $("overlayText").textContent = "NordDesk перезапускается с правами администратора…";
  $("overlay").classList.remove("hidden");
  for (let i = 0; i < 120; i++) {
    await sleep(500);
    try {
      const r = await fetch(`/ping?token=${token}`, { cache: "no-store" });
      if (r.ok && (await r.text()) === "norddesk") {
        location.href = `/?token=${token}`;
        return;
      }
    } catch { /* прежняя копия ещё не закрылась */ }
  }
  $("overlayText").textContent = "Не удалось перейти в новую копию. Закройте это окно.";
}

// --- панель сеанса ---
const toolsMenu = $("toolsMenu");
$("toolsBtn").addEventListener("click", (e) => { e.stopPropagation(); toolsMenu.classList.toggle("hidden"); });
document.addEventListener("click", () => toolsMenu.classList.add("hidden"));
toolsMenu.querySelectorAll("button[data-action]").forEach((btn) => {
  btn.addEventListener("click", () => {
    sendCmd({ cmd: "action", name: btn.dataset.action });
    toolsMenu.classList.add("hidden");
    canvas.focus();
  });
});
$("syskeys").addEventListener("change", (e) => { sendCmd({ cmd: "syskeys", on: e.target.checked }); canvas.focus(); });
$("quality").addEventListener("input", sendQuality);
$("fps").addEventListener("input", sendQuality);
function sendQuality() {
  sendCmd({ cmd: "quality", q: +$("quality").value, fps: +$("fps").value });
}
$("discBtn").addEventListener("click", () => { sendCmd({ cmd: "disconnect" }); endSession("Отключено."); });
$("fsBtn").addEventListener("click", () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen().catch(() => {});
  canvas.focus();
});
$("filesBtn").addEventListener("click", () => $("filePicker").click());
$("filePicker").addEventListener("change", (e) => {
  const files = [...e.target.files];
  e.target.value = "";
  if (files.length) sendEntries(files, "desktop");
  canvas.focus();
});

// --- мышь ---
function toScreen(e) {
  const r = canvas.getBoundingClientRect();
  const x = Math.round((e.clientX - r.left) / r.width * screenW);
  const y = Math.round((e.clientY - r.top) / r.height * screenH);
  return [Math.max(0, Math.min(screenW - 1, x)), Math.max(0, Math.min(screenH - 1, y))];
}
let lastMove = 0;
canvas.addEventListener("mousemove", (e) => {
  const now = performance.now();
  if (now - lastMove < 16) return; // ~60 событий/с
  lastMove = now;
  const [x, y] = toScreen(e);
  sendBytes([20, ...u16(x), ...u16(y)]);
});
const BTN = { 0: 0, 1: 2, 2: 1 }; // DOM: 0 лев, 1 сред, 2 прав → хост: 0 лев, 1 прав, 2 сред
canvas.addEventListener("mousedown", (e) => {
  canvas.focus();
  e.preventDefault();
  const [x, y] = toScreen(e);
  sendBytes([20, ...u16(x), ...u16(y)]);
  sendBytes([21, BTN[e.button] ?? 0, 1]);
});
canvas.addEventListener("mouseup", (e) => { e.preventDefault(); sendBytes([21, BTN[e.button] ?? 0, 0]); });
canvas.addEventListener("contextmenu", (e) => e.preventDefault());
canvas.addEventListener("wheel", (e) => {
  e.preventDefault();
  const delta = e.deltaY > 0 ? -120 : 120; // вверх — положительное, как в Windows
  sendBytes([22, ...u16(delta & 0xffff)]);
}, { passive: false });

// --- клавиатура (запасной путь) ---
// На Windows системные сочетания перехватывает бэкенд и сюда они не доходят;
// здесь — обычные клавиши, когда перехват выключен.
let vSwallowed = false;
canvas.addEventListener("keydown", (e) => {
  e.preventDefault();
  if (e.code === "KeyV" && (e.ctrlKey || e.metaKey) && !e.repeat) {
    sendCmd({ cmd: "ctrlv" }); // бэкенд вставит файлы или текст из буфера
    vSwallowed = true;
    return;
  }
  sendKey(e, 1);
});
canvas.addEventListener("keyup", (e) => {
  e.preventDefault();
  if (e.code === "KeyV" && vSwallowed) { vSwallowed = false; return; }
  sendKey(e, 0);
});
function sendKey(e, down) {
  const vk = codeToVK(e.code);
  if (vk) sendBytes([23, ...u16(vk), down]);
}

// --- перетаскивание файлов и папок в окно ---
const hasFiles = (e) => e.dataTransfer && [...e.dataTransfer.types].includes("Files");
let dragDepth = 0;
window.addEventListener("dragenter", (e) => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  dragDepth++;
  if (connected) document.body.classList.add("dragover");
});
window.addEventListener("dragover", (e) => {
  e.preventDefault(); // без этого браузер откроет файл вместо передачи
  if (e.dataTransfer) e.dataTransfer.dropEffect = connected ? "copy" : "none";
});
window.addEventListener("dragleave", () => {
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) document.body.classList.remove("dragover");
});
window.addEventListener("drop", (e) => {
  e.preventDefault();
  dragDepth = 0;
  document.body.classList.remove("dragover");
  if (!connected || !e.dataTransfer) return;
  // Записи нужно забрать прямо в обработчике — после него список пустеет.
  const entries = [];
  for (const it of e.dataTransfer.items || []) {
    if (it.kind !== "file") continue;
    const en = it.webkitGetAsEntry ? it.webkitGetAsEntry() : null;
    const f = en ? null : it.getAsFile();
    if (en) entries.push(en); else if (f) entries.push(f);
  }
  if (!entries.length) entries.push(...e.dataTransfer.files);
  if (entries.length) sendEntries(entries, "desktop");
  canvas.focus();
});

const CHUNK = 64 * 1024;
let xferBusy = false;

// sendEntries передаёт файлы/папки пакетом; to = "desktop" — на рабочий стол той стороны.
async function sendEntries(entries, to) {
  if (xferBusy) { toast("Подождите — идёт предыдущая передача."); return; }
  xferBusy = true;
  try {
    const batch = Math.random().toString(16).slice(2) + Date.now().toString(16);
    const list = [], items = [];
    for (const en of entries) {
      items.push(en.name);
      if (en instanceof File) list.push({ rel: en.name, file: en });
      else await walk(en, en.name, list);
    }
    const total = list.reduce((s, it) => s + (it.file ? it.file.size : 0), 0);
    let sent = 0, last = 0;
    for (const it of list) {
      if (!connected || ws.readyState !== 1) throw new Error("связь прервалась");
      if (it.dir) { sendCmd({ cmd: "filestart", batch, rel: it.rel, dir: true }); continue; }
      sendCmd({ cmd: "filestart", batch, rel: it.rel, name: it.file.name, size: it.file.size });
      for (let off = 0; off < it.file.size; off += CHUNK) {
        const part = new Uint8Array(await it.file.slice(off, off + CHUNK).arrayBuffer());
        const out = new Uint8Array(1 + part.length);
        out[0] = 41; // wire.TypeFileData
        out.set(part, 1);
        ws.send(out);
        sent += part.length;
        if (performance.now() - last > 250) {
          last = performance.now();
          toast(`Передача: ${it.file.name} — ${total ? Math.floor(sent * 100 / total) : 100}%`, 0);
        }
        while (ws.bufferedAmount > 8 * 1024 * 1024) await sleep(20);
      }
      sendCmd({ cmd: "fileend" });
    }
    sendCmd({ cmd: "pastedone", batch, items, to });
    // Итог («Сохранено на рабочий стол…») пришлёт та сторона, когда файлы лягут на место.
    toast("Передано, сохраняю на той стороне…", 0);
  } catch (err) {
    toast("Не удалось отправить: " + (err && err.message ? err.message : err), 6000);
  } finally {
    xferBusy = false;
  }
}

// walk обходит папку из перетаскивания (вложенность сохраняется).
async function walk(entry, rel, out) {
  if (entry.isFile) {
    const file = await new Promise((res, rej) => entry.file(res, rej));
    out.push({ rel, file });
    return;
  }
  if (!entry.isDirectory) return;
  out.push({ rel, dir: true });
  const reader = entry.createReader();
  for (;;) {
    const chunk = await new Promise((res, rej) => reader.readEntries(res, rej));
    if (!chunk.length) break;
    for (const child of chunk) await walk(child, `${rel}/${child.name}`, out);
  }
}

// --- KeyboardEvent.code → виртуальная клавиша Windows ---
function codeToVK(code) {
  if (/^Key[A-Z]$/.test(code)) return code.charCodeAt(3);
  if (/^Digit[0-9]$/.test(code)) return code.charCodeAt(5);
  if (/^Numpad[0-9]$/.test(code)) return 0x60 + (code.charCodeAt(6) - 48);
  if (/^F([1-9]|1[0-2])$/.test(code)) return 0x70 + (parseInt(code.slice(1), 10) - 1);
  const map = {
    Enter: 0x0D, NumpadEnter: 0x0D, Escape: 0x1B, Backspace: 0x08, Tab: 0x09, Space: 0x20,
    ArrowLeft: 0x25, ArrowUp: 0x26, ArrowRight: 0x27, ArrowDown: 0x28,
    ShiftLeft: 0xA0, ShiftRight: 0xA1, ControlLeft: 0xA2, ControlRight: 0xA3,
    AltLeft: 0xA4, AltRight: 0xA5, MetaLeft: 0x5B, MetaRight: 0x5C,
    CapsLock: 0x14, Delete: 0x2E, Insert: 0x2D, Home: 0x24, End: 0x23,
    PageUp: 0x21, PageDown: 0x22, PrintScreen: 0x2C, ContextMenu: 0x5D,
    Semicolon: 0xBA, Equal: 0xBB, Comma: 0xBC, Minus: 0xBD, Period: 0xBE, Slash: 0xBF,
    Backquote: 0xC0, BracketLeft: 0xDB, Backslash: 0xDC, BracketRight: 0xDD, Quote: 0xDE,
    NumpadAdd: 0x6B, NumpadSubtract: 0x6D, NumpadMultiply: 0x6A, NumpadDivide: 0x6F,
    NumpadDecimal: 0x6E,
  };
  return map[code] || 0;
}

openWS();
