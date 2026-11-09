//go:build windows

package hostagent

import (
	"syscall"
	"unsafe"
)

var (
	shell32                  = syscall.NewLazyDLL("shell32.dll")
	ole32                    = syscall.NewLazyDLL("ole32.dll")
	user32                   = syscall.NewLazyDLL("user32.dll")
	procSHGetKnownFolderPath = shell32.NewProc("SHGetKnownFolderPath")
	procCoTaskMemFree        = ole32.NewProc("CoTaskMemFree")
	procMessageBoxW          = user32.NewProc("MessageBoxW")
)

type guid struct {
	Data1 uint32
	Data2 uint16
	Data3 uint16
	Data4 [8]byte
}

// FOLDERID_Desktop {B4BFCC3A-DB2C-424C-B029-7FE99A87C641}
var folderIDDesktop = guid{0xB4BFCC3A, 0xDB2C, 0x424C, [8]byte{0xB0, 0x29, 0x7F, 0xE9, 0x9A, 0x87, 0xC6, 0x41}}

// DesktopDir возвращает настоящий рабочий стол пользователя. Если он перенесён
// в OneDrive, вернётся именно путь в OneDrive — туда, куда человек смотрит.
func DesktopDir() string {
	var p *uint16
	hr, _, _ := procSHGetKnownFolderPath.Call(
		uintptr(unsafe.Pointer(&folderIDDesktop)), 0, 0, uintptr(unsafe.Pointer(&p)))
	if hr == 0 && p != nil {
		defer procCoTaskMemFree.Call(uintptr(unsafe.Pointer(p)))
		n := 0
		for n < 32767 && *(*uint16)(unsafe.Add(unsafe.Pointer(p), n*2)) != 0 {
			n++
		}
		if s := syscall.UTF16ToString(unsafe.Slice(p, n)); s != "" {
			return s
		}
	}
	return fallbackDesktop()
}

const (
	mbYesNo         = 0x00000004
	mbIconQuestion  = 0x00000020
	mbDefButton2    = 0x00000100 // по умолчанию выделена «Нет»
	mbSystemModal   = 0x00001000
	mbSetForeground = 0x00010000
	mbTopMost       = 0x00040000
	idYes           = 6
)

// ConfirmDialog показывает модальное окно поверх всех окон. true — «Да».
// По умолчанию выделена «Нет», чтобы случайный Enter не открыл доступ.
func ConfirmDialog(title, text string) bool {
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString(title)
	ret, _, _ := procMessageBoxW.Call(0,
		uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)),
		mbYesNo|mbIconQuestion|mbDefButton2|mbSystemModal|mbSetForeground|mbTopMost)
	return ret == idYes
}
