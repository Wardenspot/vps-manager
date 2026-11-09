//go:build windows

// Package wininput внедряет ввод (мышь, клавиатура) на хосте через SendInput и
// работает с буфером обмена. Используется только на хосте.
package wininput

import (
	"errors"
	"time"
	"unicode/utf16"
	"unsafe"
)

var (
	user32            = lazyDLL("user32.dll")
	kernel32          = lazyDLL("kernel32.dll")
	procSendInput     = user32.proc("SendInput")
	procGetSysMetrics = user32.proc("GetSystemMetrics")
	procOpenClip      = user32.proc("OpenClipboard")
	procCloseClip     = user32.proc("CloseClipboard")
	procEmptyClip     = user32.proc("EmptyClipboard")
	procGetClipData   = user32.proc("GetClipboardData")
	procSetClipData   = user32.proc("SetClipboardData")
	procGlobalAlloc   = kernel32.proc("GlobalAlloc")
	procGlobalLock    = kernel32.proc("GlobalLock")
	procGlobalUnlock  = kernel32.proc("GlobalUnlock")
)

// Константы WinAPI.
const (
	inputMouse    = 0
	inputKeyboard = 1

	mouseMove       = 0x0001
	mouseLeftDown   = 0x0002
	mouseLeftUp     = 0x0004
	mouseRightDown  = 0x0008
	mouseRightUp    = 0x0010
	mouseMiddleDown = 0x0020
	mouseMiddleUp   = 0x0040
	mouseWheel      = 0x0800
	mouseAbsolute   = 0x8000

	keyUp = 0x0002

	vkControl = 0x11
	vkMenu    = 0x12 // Alt
	vkDelete  = 0x2E
	vkEnd     = 0x23
	vkLWin    = 0x5B
	vkTab     = 0x09
	vkEscape  = 0x1B

	smCXScreen = 0
	smCYScreen = 1

	cfUnicodeText = 13
	gmemMoveable  = 0x0002
)

// INPUT на amd64 занимает 40 байт: тип(4) + выравнивание(4) + объединение(32).
// Объединение имеет размер MOUSEINPUT (самого большого варианта).
type inputMouseT struct {
	typ uint32
	_   uint32
	mi  mouseInputT
}

type mouseInputT struct {
	dx, dy      int32
	mouseData   uint32
	dwFlags     uint32
	time        uint32
	dwExtraInfo uintptr
}

type inputKbdT struct {
	typ uint32
	_   uint32
	ki  keybdInputT
	_   [8]byte // добить до размера объединения (как у mouse-варианта)
}

type keybdInputT struct {
	wVk, wScan  uint16
	dwFlags     uint32
	time        uint32
	dwExtraInfo uintptr
}

func screenSize() (int, int) {
	w, _, _ := procGetSysMetrics.call(smCXScreen)
	h, _, _ := procGetSysMetrics.call(smCYScreen)
	return int(w), int(h)
}

func sendMouse(mi mouseInputT) {
	in := inputMouseT{typ: inputMouse, mi: mi}
	procSendInput.call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
}

func sendKey(ki keybdInputT) {
	in := inputKbdT{typ: inputKeyboard, ki: ki}
	procSendInput.call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
}

// MouseMove перемещает курсор в абсолютные координаты экрана (пиксели основного
// монитора). Координаты нормализуются в диапазон 0..65535, как требует ABSOLUTE.
func MouseMove(x, y int) {
	w, h := screenSize()
	if w <= 1 || h <= 1 {
		return
	}
	nx := int32(int64(x) * 65535 / int64(w-1))
	ny := int32(int64(y) * 65535 / int64(h-1))
	sendMouse(mouseInputT{dx: nx, dy: ny, dwFlags: mouseMove | mouseAbsolute})
}

// Button: 0 — левая, 1 — правая, 2 — средняя.
func MouseButton(button int, down bool) {
	var flag uint32
	switch button {
	case 0:
		flag = pick(down, mouseLeftDown, mouseLeftUp)
	case 1:
		flag = pick(down, mouseRightDown, mouseRightUp)
	case 2:
		flag = pick(down, mouseMiddleDown, mouseMiddleUp)
	default:
		return
	}
	sendMouse(mouseInputT{dwFlags: flag})
}

// MouseWheel прокручивает колесо. delta кратна 120 (WHEEL_DELTA); вверх — положит.
func MouseWheel(delta int) {
	sendMouse(mouseInputT{mouseData: uint32(int32(delta)), dwFlags: mouseWheel})
}

// Key нажимает (down) или отпускает виртуальную клавишу vk.
func Key(vk uint16, down bool) {
	ki := keybdInputT{wVk: vk}
	if !down {
		ki.dwFlags = keyUp
	}
	sendKey(ki)
}

func tap(vk uint16) {
	Key(vk, true)
	time.Sleep(10 * time.Millisecond)
	Key(vk, false)
}

// SendCombo выполняет известную комбинацию. Ctrl+Alt+Del — это Secure Attention
// Sequence, её нельзя синтезировать через SendInput из обычного процесса; для неё
// нужен системный агент (роадмап). Остальные комбинации работают.
func SendCombo(name string) error {
	switch name {
	case "ctrl-alt-del":
		return errors.New("wininput: Ctrl+Alt+Del нельзя отправить из пользовательского процесса (нужен системный агент)")
	case "ctrl-alt-end": // частый заменитель в удалённых сессиях
		Key(vkControl, true)
		Key(vkMenu, true)
		tap(vkEnd)
		Key(vkMenu, false)
		Key(vkControl, false)
	case "win-d":
		Key(vkLWin, true)
		tap('D')
		Key(vkLWin, false)
	case "alt-tab":
		Key(vkMenu, true)
		tap(vkTab)
		Key(vkMenu, false)
	case "escape":
		tap(vkEscape)
	default:
		return errors.New("wininput: неизвестная комбинация")
	}
	return nil
}

func pick(b bool, yes, no uint32) uint32 {
	if b {
		return yes
	}
	return no
}

// --- буфер обмена ---

// SetClipboardText кладёт текст в буфер обмена хоста.
func SetClipboardText(s string) error {
	if r, _, _ := procOpenClip.call(0); r == 0 {
		return errors.New("wininput: OpenClipboard не удался")
	}
	defer procCloseClip.call()
	procEmptyClip.call()

	u := utf16.Encode([]rune(s))
	u = append(u, 0) // завершающий ноль
	nbytes := uintptr(len(u) * 2)

	h, _, _ := procGlobalAlloc.call(gmemMoveable, nbytes)
	if h == 0 {
		return errors.New("wininput: GlobalAlloc не удался")
	}
	ptr, _, _ := procGlobalLock.call(h)
	if ptr == 0 {
		return errors.New("wininput: GlobalLock не удался")
	}
	// ptr указывает на НЕ-Go память (GlobalAlloc), закреплённую GlobalLock до
	// GlobalUnlock, поэтому конверсия uintptr→Pointer здесь безопасна.
	dst := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), len(u))
	copy(dst, u)
	procGlobalUnlock.call(h)

	if r, _, _ := procSetClipData.call(cfUnicodeText, h); r == 0 {
		return errors.New("wininput: SetClipboardData не удался")
	}
	return nil
}

// GetClipboardText читает текст из буфера обмена хоста.
func GetClipboardText() (string, error) {
	if r, _, _ := procOpenClip.call(0); r == 0 {
		return "", errors.New("wininput: OpenClipboard не удался")
	}
	defer procCloseClip.call()

	h, _, _ := procGetClipData.call(cfUnicodeText)
	if h == 0 {
		return "", nil // буфер пуст или другой формат
	}
	ptr, _, _ := procGlobalLock.call(h)
	if ptr == 0 {
		return "", errors.New("wininput: GlobalLock не удался")
	}
	defer procGlobalUnlock.call(h)

	// читаем UTF-16 до нулевого терминатора (ограничим разумным максимумом).
	// ptr — закреплённая GlobalLock не-Go память, конверсия безопасна.
	const maxRunes = 1 << 20
	raw := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), maxRunes)
	n := 0
	for n < maxRunes && raw[n] != 0 {
		n++
	}
	return string(utf16.Decode(raw[:n])), nil
}
