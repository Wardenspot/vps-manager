//go:build windows

package main

import (
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"
)

// Перехват системных сочетаний клавиш.
//
// Окно клиента — это окно браузера, и Win, Alt+Tab, Ctrl+W, Alt+F4 и т.п.
// Windows и браузер обрабатывают раньше страницы — поэтому они срабатывали на
// своём ПК. Как и в AnyDesk, ставим низкоуровневый перехватчик клавиатуры
// (WH_KEYBOARD_LL): пока активно окно сеанса, все нажатия забираем себе и
// отправляем на удалённый компьютер. Перехватчик работает только во время
// сеанса и только когда окно сеанса на переднем плане. Ctrl+Alt+Del и Win+L
// система не отдаёт никому — это её защита.

var (
	hkUser32                  = syscall.NewLazyDLL("user32.dll")
	hkKernel32                = syscall.NewLazyDLL("kernel32.dll")
	procSetWindowsHookExW     = hkUser32.NewProc("SetWindowsHookExW")
	procCallNextHookEx        = hkUser32.NewProc("CallNextHookEx")
	procUnhookWindowsHookEx   = hkUser32.NewProc("UnhookWindowsHookEx")
	procGetMessageW           = hkUser32.NewProc("GetMessageW")
	procPostThreadMessageW    = hkUser32.NewProc("PostThreadMessageW")
	procGetForegroundWindow   = hkUser32.NewProc("GetForegroundWindow")
	procInternalGetWindowText = hkUser32.NewProc("InternalGetWindowText")
	procGetCurrentThreadId    = hkKernel32.NewProc("GetCurrentThreadId")
	procGetModuleHandleW      = hkKernel32.NewProc("GetModuleHandleW")
)

const (
	whKeyboardLL  = 13
	hcAction      = 0
	wmKeyDown     = 0x0100
	wmSysKeyDown  = 0x0104
	wmQuit        = 0x0012
	llkhfExtended = 0x01
	llkhfInjected = 0x10
)

type kbdLLHookStruct struct {
	vkCode, scanCode, flags, time uint32
	dwExtraInfo                   uintptr
}

// Состояние перехватчика. Колбэк вызывается на потоке перехватчика, поэтому
// pressed трогается только там.
var hk struct {
	active  atomic.Bool // перехватывать (сеанс идёт и «системные клавиши» включены)
	events  chan keyEvent
	pressed [256]bool // клавиши, нажатие которых мы забрали (их отпускание тоже наше)
}

var hookCallback = syscall.NewCallback(hookProc)

func hookProc(nCode, wParam, lParam uintptr) uintptr {
	if int32(nCode) == hcAction {
		// lParam указывает на структуру, которую система держит на время вызова.
		kb := (*kbdLLHookStruct)(unsafe.Pointer(lParam))
		if kb.flags&llkhfInjected == 0 && kb.vkCode < 256 {
			vk := kb.vkCode
			down := wParam == wmKeyDown || wParam == wmSysKeyDown
			ext := kb.flags&llkhfExtended != 0
			if down && hk.active.Load() && sessionInForeground() {
				hk.pressed[vk] = true
				emitKey(keyEvent{vk: uint16(vk), down: true, ext: ext})
				return 1 // не отдаём своему ПК
			}
			if !down && hk.pressed[vk] {
				// Отпускание клавиши, которую мы забрали, — тоже туда, даже если
				// окно уже потеряло фокус: иначе клавиша «залипнет» там.
				hk.pressed[vk] = false
				emitKey(keyEvent{vk: uint16(vk), down: false, ext: ext})
				return 1
			}
		}
	}
	r, _, _ := procCallNextHookEx.Call(0, nCode, wParam, lParam)
	return r
}

func emitKey(ev keyEvent) {
	select {
	case hk.events <- ev:
	default: // очередь переполнена — лучше потерять нажатие, чем подвесить ввод
	}
}

// sessionInForeground — активно ли окно сеанса NordDesk. InternalGetWindowText
// не шлёт сообщений окну, поэтому не может подвесить перехватчик.
func sessionInForeground() bool {
	hwnd, _, _ := procGetForegroundWindow.Call()
	if hwnd == 0 {
		return false
	}
	var buf [256]uint16
	n, _, _ := procInternalGetWindowText.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
	return strings.Contains(syscall.UTF16ToString(buf[:n]), sessionTitleMarker)
}

type keyHook struct {
	mu       sync.Mutex
	running  bool
	threadID uintptr
}

func newKeyHook(onKey func(keyEvent)) *keyHook {
	hk.events = make(chan keyEvent, 512)
	go func() {
		for ev := range hk.events {
			onKey(ev)
		}
	}()
	return &keyHook{}
}

// setActive включает/выключает перехват. Поток с перехватчиком создаётся при
// первом включении и живёт, пока идёт сеанс.
func (h *keyHook) setActive(on bool) {
	hk.active.Store(on)
	if on {
		h.start()
	}
}

func (h *keyHook) start() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.running {
		return
	}
	ready := make(chan uintptr)
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		tid, _, _ := procGetCurrentThreadId.Call()
		mod, _, _ := procGetModuleHandleW.Call(0)
		hook, _, _ := procSetWindowsHookExW.Call(whKeyboardLL, hookCallback, mod, 0)
		if hook == 0 {
			ready <- 0
			return
		}
		ready <- tid
		var msg [48]byte // MSG
		for {
			r, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&msg[0])), 0, 0, 0)
			if int32(r) <= 0 {
				break
			}
		}
		procUnhookWindowsHookEx.Call(hook)
		hk.pressed = [256]bool{}
	}()
	if tid := <-ready; tid != 0 {
		h.threadID, h.running = tid, true
	}
}

// stop снимает перехватчик (сеанс закончился).
func (h *keyHook) stop() {
	hk.active.Store(false)
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.running {
		procPostThreadMessageW.Call(h.threadID, wmQuit, 0, 0)
		h.running = false
	}
}
