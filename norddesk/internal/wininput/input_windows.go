//go:build windows

// Package wininput внедряет ввод (мышь, клавиатура) через SendInput и работает
// с буфером обмена Windows: текст и списки файлов (как у Проводника).
package wininput

import (
	"errors"
	"runtime"
	"time"
	"unicode/utf16"
	"unsafe"
)

var (
	user32             = lazyDLL("user32.dll")
	kernel32           = lazyDLL("kernel32.dll")
	shell32            = lazyDLL("shell32.dll")
	procSendInput      = user32.proc("SendInput")
	procGetSysMetrics  = user32.proc("GetSystemMetrics")
	procMapVirtualKeyW = user32.proc("MapVirtualKeyW")
	procOpenClip       = user32.proc("OpenClipboard")
	procCloseClip      = user32.proc("CloseClipboard")
	procEmptyClip      = user32.proc("EmptyClipboard")
	procGetClipData    = user32.proc("GetClipboardData")
	procSetClipData    = user32.proc("SetClipboardData")
	procClipFmtAvail   = user32.proc("IsClipboardFormatAvailable")
	procClipSeq        = user32.proc("GetClipboardSequenceNumber")
	procRegClipFmt     = user32.proc("RegisterClipboardFormatW")
	procGlobalAlloc    = kernel32.proc("GlobalAlloc")
	procGlobalFree     = kernel32.proc("GlobalFree")
	procGlobalLock     = kernel32.proc("GlobalLock")
	procGlobalUnlock   = kernel32.proc("GlobalUnlock")
	procDragQueryFileW = shell32.proc("DragQueryFileW")
)

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

	keyExtended = 0x0001
	keyUp       = 0x0002

	vkControl = 0x11
	vkMenu    = 0x12 // Alt
	vkLWin    = 0x5B
	vkTab     = 0x09
	vkEscape  = 0x1B

	smCXScreen = 0
	smCYScreen = 1

	cfUnicodeText = 13
	cfHDrop       = 15
	gmemMoveable  = 0x0002
	gmemZeroInit  = 0x0040
	dropEffCopy   = 1
)

// INPUT на amd64 — 40 байт: тип(4) + выравнивание(4) + объединение(32).
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
	_   [8]byte // добить до размера объединения
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

// MouseMove перемещает курсор в абсолютные координаты основного монитора.
func MouseMove(x, y int) {
	w, h := screenSize()
	if w <= 1 || h <= 1 {
		return
	}
	nx := int32(int64(x) * 65535 / int64(w-1))
	ny := int32(int64(y) * 65535 / int64(h-1))
	sendMouse(mouseInputT{dx: nx, dy: ny, dwFlags: mouseMove | mouseAbsolute})
}

// MouseButton: 0 — левая, 1 — правая, 2 — средняя.
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

// MouseWheel прокручивает колесо; delta кратна 120, вверх — положительная.
func MouseWheel(delta int) {
	sendMouse(mouseInputT{mouseData: uint32(int32(delta)), dwFlags: mouseWheel})
}

// Key нажимает/отпускает клавишу. Передаём и виртуальный код, и скан-код, и
// флаг extended — иначе стрелки, Del, правые Ctrl/Alt в части программ
// срабатывают как клавиши цифрового блока.
func Key(vk uint16, down, ext bool) {
	scan, _, _ := procMapVirtualKeyW.call(uintptr(vk), 0) // MAPVK_VK_TO_VSC
	ki := keybdInputT{wVk: vk, wScan: uint16(scan)}
	if ext || isExtended(vk) {
		ki.dwFlags |= keyExtended
	}
	if !down {
		ki.dwFlags |= keyUp
	}
	sendKey(ki)
}

func isExtended(vk uint16) bool {
	switch vk {
	case 0x21, 0x22, 0x23, 0x24, // PgUp PgDn End Home
		0x25, 0x26, 0x27, 0x28, // стрелки
		0x2C, 0x2D, 0x2E, // PrintScreen Ins Del
		0x5B, 0x5C, 0x5D, // LWin RWin Apps
		0x6F, 0x90, // Num / , NumLock
		0xA3, 0xA5: // правые Ctrl и Alt
		return true
	}
	return false
}

func tap(vk uint16) {
	Key(vk, true, false)
	time.Sleep(10 * time.Millisecond)
	Key(vk, false, false)
}

// SendCombo выполняет известную комбинацию. Ctrl+Alt+Del — это Secure
// Attention Sequence, её нельзя отправить из обычного процесса.
func SendCombo(name string) error {
	switch name {
	case "ctrl-alt-del":
		return errors.New("wininput: Ctrl+Alt+Del нельзя отправить из обычного процесса (нужна служба)")
	case "ctrl-v":
		Key(vkControl, true, false)
		tap('V')
		Key(vkControl, false, false)
	case "win-d":
		Key(vkLWin, true, false)
		tap('D')
		Key(vkLWin, false, false)
	case "win-r":
		Key(vkLWin, true, false)
		tap('R')
		Key(vkLWin, false, false)
	case "alt-tab":
		Key(vkMenu, true, false)
		tap(vkTab)
		Key(vkMenu, false, false)
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
//
// OpenClipboard привязывает буфер к потоку ОС, а горутина может «переехать» на
// другой поток между вызовами. Поэтому каждая операция закрепляет поток.

func openClipboard() error {
	for i := 0; i < 20; i++ {
		if r, _, _ := procOpenClip.call(0); r != 0 {
			return nil
		}
		time.Sleep(15 * time.Millisecond) // буфер занят другой программой
	}
	return errors.New("wininput: буфер обмена занят")
}

// ClipboardSeq — номер изменения буфера обмена (растёт при каждом изменении).
func ClipboardSeq() uint32 {
	r, _, _ := procClipSeq.call()
	return uint32(r)
}

// SetClipboardText кладёт текст в буфер обмена.
func SetClipboardText(s string) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := openClipboard(); err != nil {
		return err
	}
	defer procCloseClip.call()
	procEmptyClip.call()

	u := append(utf16.Encode([]rune(s)), 0)
	h, err := globalWith(len(u)*2, func(p unsafe.Pointer) {
		copy(unsafe.Slice((*uint16)(p), len(u)), u)
	})
	if err != nil {
		return err
	}
	if r, _, _ := procSetClipData.call(cfUnicodeText, h); r == 0 {
		procGlobalFree.call(h)
		return errors.New("wininput: SetClipboardData не удался")
	}
	return nil
}

// GetClipboardText читает текст из буфера обмена ("" — текста нет).
func GetClipboardText() (string, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if r, _, _ := procClipFmtAvail.call(cfUnicodeText); r == 0 {
		return "", nil
	}
	if err := openClipboard(); err != nil {
		return "", err
	}
	defer procCloseClip.call()

	h, _, _ := procGetClipData.call(cfUnicodeText)
	if h == 0 {
		return "", nil
	}
	ptr, _, _ := procGlobalLock.call(h)
	if ptr == 0 {
		return "", errors.New("wininput: GlobalLock не удался")
	}
	defer procGlobalUnlock.call(h)
	// ptr — закреплённая GlobalLock не-Go память, конверсия безопасна.
	const maxRunes = 1 << 22
	raw := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), maxRunes)
	n := 0
	for n < maxRunes && raw[n] != 0 {
		n++
	}
	return string(utf16.Decode(raw[:n])), nil
}

// GetClipboardFiles возвращает файлы и папки, скопированные в Проводнике
// (nil — в буфере не файлы).
func GetClipboardFiles() ([]string, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if r, _, _ := procClipFmtAvail.call(cfHDrop); r == 0 {
		return nil, nil
	}
	if err := openClipboard(); err != nil {
		return nil, err
	}
	defer procCloseClip.call()

	hdrop, _, _ := procGetClipData.call(cfHDrop)
	if hdrop == 0 {
		return nil, nil
	}
	count, _, _ := procDragQueryFileW.call(hdrop, 0xFFFFFFFF, 0, 0)
	var out []string
	for i := uintptr(0); i < count; i++ {
		n, _, _ := procDragQueryFileW.call(hdrop, i, 0, 0)
		if n == 0 {
			continue
		}
		buf := make([]uint16, n+1)
		procDragQueryFileW.call(hdrop, i, uintptr(unsafe.Pointer(&buf[0])), n+1)
		out = append(out, string(utf16.Decode(buf[:n])))
	}
	return out, nil
}

// SetClipboardFiles кладёт в буфер список файлов так же, как «Копировать» в
// Проводнике: после этого Ctrl+V в любой папке вставит их.
func SetClipboardFiles(paths []string) error {
	if len(paths) == 0 {
		return errors.New("wininput: пустой список файлов")
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	// DROPFILES (20 байт) + пути UTF-16 с нулём + завершающий ноль.
	var list []uint16
	for _, p := range paths {
		list = append(list, utf16.Encode([]rune(p))...)
		list = append(list, 0)
	}
	list = append(list, 0)
	const hdr = 20
	h, err := globalWith(hdr+len(list)*2, func(p unsafe.Pointer) {
		b := unsafe.Slice((*byte)(p), hdr)
		b[0] = hdr // pFiles: смещение списка
		b[16] = 1  // fWide: пути в UTF-16
		copy(unsafe.Slice((*uint16)(unsafe.Add(p, hdr)), len(list)), list)
	})
	if err != nil {
		return err
	}
	effName := append(utf16.Encode([]rune("Preferred DropEffect")), 0)
	effFmt, _, _ := procRegClipFmt.call(uintptr(unsafe.Pointer(&effName[0])))
	eff, err := globalWith(4, func(p unsafe.Pointer) { *(*uint32)(p) = dropEffCopy })
	if err != nil {
		procGlobalFree.call(h)
		return err
	}

	if err := openClipboard(); err != nil {
		procGlobalFree.call(h)
		procGlobalFree.call(eff)
		return err
	}
	defer procCloseClip.call()
	procEmptyClip.call()
	if r, _, _ := procSetClipData.call(cfHDrop, h); r == 0 {
		procGlobalFree.call(h)
		procGlobalFree.call(eff)
		return errors.New("wininput: SetClipboardData(CF_HDROP) не удался")
	}
	if effFmt != 0 {
		if r, _, _ := procSetClipData.call(effFmt, eff); r == 0 {
			procGlobalFree.call(eff)
		}
	} else {
		procGlobalFree.call(eff)
	}
	return nil
}

// globalWith выделяет перемещаемую глобальную память нужного размера и даёт
// заполнить её. После SetClipboardData память принадлежит системе.
func globalWith(size int, fill func(p unsafe.Pointer)) (uintptr, error) {
	h, _, _ := procGlobalAlloc.call(gmemMoveable|gmemZeroInit, uintptr(size))
	if h == 0 {
		return 0, errors.New("wininput: GlobalAlloc не удался")
	}
	ptr, _, _ := procGlobalLock.call(h)
	if ptr == 0 {
		procGlobalFree.call(h)
		return 0, errors.New("wininput: GlobalLock не удался")
	}
	// ptr — не-Go память, закреплённая GlobalLock до GlobalUnlock.
	fill(unsafe.Pointer(ptr))
	procGlobalUnlock.call(h)
	return h, nil
}
