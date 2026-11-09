"use strict";
// Окно NordDesk. Логики экрана/ввода тут нет: отрисовка тайлов на canvas,
// отправка событий в Go-бэкенд по локальному WebSocket и показ статуса.

const TOKEN = window.NDSK_TOKEN || "";
let ws = null;
let screenW = 0, screenH = 0;
let connected = false;
let self = { id: "", code: "", state: "connecting" };

const $ = (id) => document.getElementById(id);
const home = $("home");
const sessionScreen = $("session");
const statusEl = $("status");
const canvas = $("screen");
const ctx = canvas.getContext("2d", { alpha: false });

// 123456789 → «123 456 789», 482913 → «482 913»
const spaced = (s) => (s || "").replace(/(\d{3})(?=\d)/g, "$1 ");

// --- связь с бэкендом ---
function openWS() {
  ws = new WebSocket(`ws://${location.host}/ws?token=${TOKEN}`);
  ws.binaryType = "arraybuffer";
  ws.onmessage = onMessage;
  ws.onclose = () => {
    // бэкенд перезапускается или закрылся — пробуем переподключиться
    setTimeout(openWS, 1000);
  };
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
    case "self":
      renderSelf(m);
      break;
    case "status":
      statusEl.textContent = m.text;
      break;
    case "screeninfo":
      startSession(m.w, m.h);
      break;
    case "clip":
      if (navigator.clipboard && m.text) navigator.clipboard.writeText(m.text).catch(() => {});
      break;
    case "ended":
      endSession(m.text || "Сеанс завершён.");
      break;
  }
}

// --- «Это рабочее место» ---
const STATES = {
  connecting: ["", "Подключение к серверу…"],
  offline: ["bad", "Нет связи с сервером"],
  ready: ["ok", "Готов к подключению"],
  incoming: ["warn", "Входящее подключение — подтвердите в окне Windows"],
  active: ["live", "Вашим компьютером сейчас управляют"],
};

function renderSelf(m) {
  const disabled = m.state === "disabled";
  $("selfBody").classList.toggle("hidden", disabled);
  $("selfDisabled").classList.toggle("hidden", !disabled);
  if (disabled) return;
  self = m;
  $("selfId").textContent = m.id ? spaced(m.id) : "··· ··· ···";
  $("selfCode").textContent = m.code ? spaced(m.code) : "постоянный пароль";
  const [cls, text] = STATES[m.state] || ["", m.state];
  const st = $("selfState");
  st.className = "state " + cls;
  $("selfStateText").textContent = m.note ? `${text}: ${m.note}` : text;
  $("endIncomingBtn").classList.toggle("hidden", !(m.state === "active" || m.state === "incoming"));
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
  if (!code) { statusEl.textContent = "Введите код с экрана того компьютера."; return; }
  statusEl.textContent = "Подключение…";
  sendCmd({ cmd: "connect", id, code });
});
$("id").addEventListener("keydown", (e) => { if (e.key === "Enter") $("code").focus(); });
$("code").addEventListener("keydown", (e) => { if (e.key === "Enter") $("connectBtn").click(); });

// --- отрисовка тайлов ---
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

// --- переходы экранов ---
function startSession(w, h) {
  screenW = w; screenH = h;
  canvas.width = w; canvas.height = h;
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, w, h);
  connected = true;
  home.classList.add("hidden");
  sessionScreen.classList.remove("hidden");
  $("peer").textContent = `${spaced($("id").value.replace(/\D+/g, ""))}  ·  ${w}×${h}`;
  canvas.focus();
}

function endSession(text) {
  connected = false;
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  sessionScreen.classList.add("hidden");
  home.classList.remove("hidden");
  statusEl.textContent = text || "";
}

let toastTimer = 0;
function toast(text) {
  const t = $("toast");
  t.textContent = text;
  t.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), 3000);
}

// --- панель инструментов сеанса ---
document.querySelectorAll("#toolbar button[data-combo]").forEach((btn) => {
  btn.addEventListener("click", () => { sendCmd({ cmd: "combo", name: btn.dataset.combo }); canvas.focus(); });
});
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
$("clipBtn").addEventListener("click", async () => {
  try {
    const t = await navigator.clipboard.readText();
    sendCmd({ cmd: "clip", text: t });
    toast("Буфер обмена отправлен — вставьте там через Ctrl+V");
  } catch {
    const t = prompt("Текст для буфера обмена того компьютера:");
    if (t !== null) sendCmd({ cmd: "clip", text: t });
  }
  canvas.focus();
});

// --- захват ввода ---
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
canvas.addEventListener("mouseup", (e) => {
  e.preventDefault();
  sendBytes([21, BTN[e.button] ?? 0, 0]);
});
canvas.addEventListener("contextmenu", (e) => e.preventDefault());
canvas.addEventListener("wheel", (e) => {
  e.preventDefault();
  const delta = e.deltaY > 0 ? -120 : 120; // вверх — положительное, как в Windows
  sendBytes([22, ...u16(delta & 0xffff)]);
}, { passive: false });

canvas.addEventListener("keydown", (e) => { e.preventDefault(); sendKey(e, 1); });
canvas.addEventListener("keyup", (e) => { e.preventDefault(); sendKey(e, 0); });
function sendKey(e, down) {
  const vk = codeToVK(e.code);
  if (vk) sendBytes([23, ...u16(vk), down]);
}

// --- перетаскивание файлов на рабочий стол того компьютера ---
const stage = $("stage");
["dragenter", "dragover"].forEach((t) =>
  stage.addEventListener(t, (e) => { e.preventDefault(); stage.classList.add("dragover"); }));
stage.addEventListener("dragleave", (e) => { e.preventDefault(); stage.classList.remove("dragover"); });
stage.addEventListener("drop", async (e) => {
  e.preventDefault();
  stage.classList.remove("dragover");
  if (!connected || !e.dataTransfer) return;
  for (const file of e.dataTransfer.files) await sendFile(file);
});
// без этого браузер откроет брошенный мимо окна файл вместо отправки
window.addEventListener("dragover", (e) => e.preventDefault());
window.addEventListener("drop", (e) => e.preventDefault());

async function sendFile(file) {
  toast(`Отправка «${file.name}»…`);
  sendCmd({ cmd: "filestart", name: file.name, size: file.size });
  const CHUNK = 64 * 1024;
  let off = 0;
  while (off < file.size) {
    const part = new Uint8Array(await file.slice(off, off + CHUNK).arrayBuffer());
    const out = new Uint8Array(1 + part.length);
    out[0] = 41; // wire.TypeFileData
    out.set(part, 1);
    if (!ws || ws.readyState !== 1) { toast("Связь прервалась — файл не отправлен"); return; }
    ws.send(out);
    off += part.length;
    // не переполняем буфер отправки на больших файлах
    while (ws.bufferedAmount > 8 * 1024 * 1024) await sleep(20);
  }
  sendCmd({ cmd: "fileend" });
  toast(`«${file.name}» отправлен на рабочий стол`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- KeyboardEvent.code → виртуальная клавиша Windows ---
function codeToVK(code) {
  if (/^Key[A-Z]$/.test(code)) return code.charCodeAt(3);
  if (/^Digit[0-9]$/.test(code)) return code.charCodeAt(5);
  if (/^Numpad[0-9]$/.test(code)) return 0x60 + (code.charCodeAt(6) - 48);
  if (/^F([1-9]|1[0-2])$/.test(code)) return 0x70 + (parseInt(code.slice(1), 10) - 1);
  const map = {
    Enter: 0x0D, NumpadEnter: 0x0D, Escape: 0x1B, Backspace: 0x08, Tab: 0x09, Space: 0x20,
    ArrowLeft: 0x25, ArrowUp: 0x26, ArrowRight: 0x27, ArrowDown: 0x28,
    ShiftLeft: 0x10, ShiftRight: 0x10, ControlLeft: 0x11, ControlRight: 0x11,
    AltLeft: 0x12, AltRight: 0x12, MetaLeft: 0x5B, MetaRight: 0x5C,
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
