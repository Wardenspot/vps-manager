"use strict";
// Окно клиента NordDesk. Логики ввода/экрана тут нет — только отрисовка тайлов
// на canvas и отправка событий в Go-бэкенд по локальному WebSocket.

const TOKEN = window.NDSK_TOKEN || "";
let ws = null;
let screenW = 0, screenH = 0;
let connected = false;

const $ = (id) => document.getElementById(id);
const connectScreen = $("connect");
const sessionScreen = $("session");
const statusEl = $("status");
const canvas = $("screen");
const ctx = canvas.getContext("2d", { alpha: false });

if (window.NDSK_ID) $("id").value = window.NDSK_ID;

// --- соединение с бэкендом ---
function openWS() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${proto}://${location.host}/ws?token=${TOKEN}`);
  ws.binaryType = "arraybuffer";
  ws.onopen = () => {};
  ws.onclose = () => { if (connected) endSession("Соединение с приложением закрыто."); };
  ws.onmessage = onMessage;
}

function sendCmd(obj) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
}
function sendBytes(arr) {
  if (ws && ws.readyState === 1) ws.send(new Uint8Array(arr));
}
function u16(n) { return [(n >> 8) & 0xff, n & 0xff]; }

// --- приём сообщений ---
function onMessage(ev) {
  if (typeof ev.data === "string") {
    handleEvent(JSON.parse(ev.data));
    return;
  }
  const b = new Uint8Array(ev.data);
  if (b.length >= 1 && b[0] === 2) drawTile(b);
}

function handleEvent(m) {
  switch (m.ev) {
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
      endSession(m.text || "Сессия завершена.");
      break;
  }
}

// --- отрисовка тайлов ---
function drawTile(b) {
  const dv = new DataView(b.buffer);
  const x = dv.getUint16(1), y = dv.getUint16(3);
  const w = dv.getUint16(5), h = dv.getUint16(7);
  const jpeg = b.subarray(9);
  const blob = new Blob([jpeg], { type: "image/jpeg" });
  createImageBitmap(blob).then((bmp) => {
    ctx.drawImage(bmp, x, y, w, h);
    bmp.close && bmp.close();
  }).catch(() => {});
}

// --- переходы экранов ---
function startSession(w, h) {
  screenW = w; screenH = h;
  canvas.width = w; canvas.height = h;
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, w, h);
  connected = true;
  connectScreen.classList.add("hidden");
  sessionScreen.classList.remove("hidden");
  $("peer").textContent = `${$("id").value}  ·  ${w}×${h}`;
  canvas.focus();
}

function endSession(text) {
  connected = false;
  sessionScreen.classList.add("hidden");
  connectScreen.classList.remove("hidden");
  statusEl.textContent = text || "";
}

// --- форма подключения ---
$("connectBtn").addEventListener("click", () => {
  const id = $("id").value.replace(/\s+/g, "");
  const code = $("code").value.replace(/\s+/g, "");
  if (!id) { statusEl.textContent = "Введите ID."; return; }
  statusEl.textContent = "Подключение...";
  sendCmd({ cmd: "connect", id, code });
});
$("code").addEventListener("keydown", (e) => { if (e.key === "Enter") $("connectBtn").click(); });

// --- панель инструментов ---
document.querySelectorAll("#toolbar button[data-combo]").forEach((btn) => {
  btn.addEventListener("click", () => sendCmd({ cmd: "combo", name: btn.dataset.combo }));
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
});
$("clipBtn").addEventListener("click", async () => {
  try {
    const t = await navigator.clipboard.readText();
    sendCmd({ cmd: "clip", text: t });
  } catch {
    const t = prompt("Текст для буфера обмена хоста:");
    if (t !== null) sendCmd({ cmd: "clip", text: t });
  }
});

// --- захват ввода на canvas ---
function toScreen(e) {
  const r = canvas.getBoundingClientRect();
  let x = Math.round((e.clientX - r.left) / r.width * screenW);
  let y = Math.round((e.clientY - r.top) / r.height * screenH);
  x = Math.max(0, Math.min(screenW - 1, x));
  y = Math.max(0, Math.min(screenH - 1, y));
  return [x, y];
}

let lastMove = 0;
canvas.addEventListener("mousemove", (e) => {
  const now = performance.now();
  if (now - lastMove < 16) return; // ~60 событий/с
  lastMove = now;
  const [x, y] = toScreen(e);
  sendBytes([20, ...u16(x), ...u16(y)]);
});

const BTN = { 0: 0, 1: 2, 2: 1 }; // DOM: 0 лев,1 сред,2 прав → хост: 0,1,2
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
  const delta = e.deltaY > 0 ? -120 : 120; // вверх = положительное (как в Windows)
  sendBytes([22, ...u16(delta & 0xffff)]);
}, { passive: false });

canvas.addEventListener("keydown", (e) => { e.preventDefault(); sendKey(e, 1); });
canvas.addEventListener("keyup", (e) => { e.preventDefault(); sendKey(e, 0); });
function sendKey(e, down) {
  const vk = codeToVK(e.code, e.key);
  if (vk) sendBytes([23, ...u16(vk), down]);
}

// --- перетаскивание файлов на рабочий стол хоста ---
const stage = $("stage");
["dragenter", "dragover"].forEach((t) =>
  stage.addEventListener(t, (e) => { e.preventDefault(); stage.classList.add("dragover"); }));
["dragleave", "drop"].forEach((t) =>
  stage.addEventListener(t, (e) => { e.preventDefault(); if (t !== "drop") stage.classList.remove("dragover"); }));
stage.addEventListener("drop", async (e) => {
  stage.classList.remove("dragover");
  if (!connected || !e.dataTransfer) return;
  for (const file of e.dataTransfer.files) await sendFile(file);
});

async function sendFile(file) {
  sendCmd({ cmd: "filestart", name: file.name, size: file.size });
  const CHUNK = 64 * 1024;
  const buf = new Uint8Array(await file.arrayBuffer());
  for (let off = 0; off < buf.length; off += CHUNK) {
    const slice = buf.subarray(off, Math.min(off + CHUNK, buf.length));
    const out = new Uint8Array(1 + slice.length);
    out[0] = 41; // wire.TypeFileData
    out.set(slice, 1);
    if (ws && ws.readyState === 1) ws.send(out);
    // небольшая пауза, чтобы не переполнить буфер отправки на больших файлах
    if (ws && ws.bufferedAmount > 8 * 1024 * 1024) await sleep(20);
  }
  sendCmd({ cmd: "fileend" });
  statusEl.textContent = `Файл «${file.name}» отправлен на рабочий стол хоста.`;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- карта DOM KeyboardEvent.code → виртуальная клавиша Windows ---
function codeToVK(code, key) {
  if (/^Key[A-Z]$/.test(code)) return code.charCodeAt(3);          // A..Z
  if (/^Digit[0-9]$/.test(code)) return code.charCodeAt(5);        // 0..9
  if (/^Numpad[0-9]$/.test(code)) return 0x60 + (code.charCodeAt(6) - 48);
  if (/^F([1-9]|1[0-2])$/.test(code)) return 0x70 + (parseInt(code.slice(1), 10) - 1);
  const map = {
    Enter: 0x0D, NumpadEnter: 0x0D, Escape: 0x1B, Backspace: 0x08, Tab: 0x09, Space: 0x20,
    ArrowLeft: 0x25, ArrowUp: 0x26, ArrowRight: 0x27, ArrowDown: 0x28,
    ShiftLeft: 0x10, ShiftRight: 0x10, ControlLeft: 0x11, ControlRight: 0x11,
    AltLeft: 0x12, AltRight: 0x12, MetaLeft: 0x5B, MetaRight: 0x5C,
    CapsLock: 0x14, Delete: 0x2E, Insert: 0x2D, Home: 0x24, End: 0x23,
    PageUp: 0x21, PageDown: 0x22,
    Semicolon: 0xBA, Equal: 0xBB, Comma: 0xBC, Minus: 0xBD, Period: 0xBE, Slash: 0xBF,
    Backquote: 0xC0, BracketLeft: 0xDB, Backslash: 0xDC, BracketRight: 0xDD, Quote: 0xDE,
    NumpadAdd: 0x6B, NumpadSubtract: 0x6D, NumpadMultiply: 0x6A, NumpadDivide: 0x6F,
    NumpadDecimal: 0x6E,
  };
  return map[code] || 0;
}

openWS();
