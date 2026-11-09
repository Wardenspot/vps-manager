//go:build windows

package main

import (
	"os"
	"path/filepath"
	"syscall"
	"unsafe"
)

var (
	user32          = syscall.NewLazyDLL("user32.dll")
	procMessageBoxW = user32.NewProc("MessageBoxW")
)

const (
	mbYesNo         = 0x00000004
	mbIconQuestion  = 0x00000020
	mbSystemModal   = 0x00001000
	mbSetForeground = 0x00010000
	mbTopMost       = 0x00040000
	idYes           = 6
)

// confirmDialog показывает модальное окно «разрешить подключение?» поверх всех
// окон. Возвращает true, если человек за хостом нажал «Да».
func confirmDialog(title, text string) bool {
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString(title)
	ret, _, _ := procMessageBoxW.Call(
		0,
		uintptr(unsafe.Pointer(t)),
		uintptr(unsafe.Pointer(c)),
		mbYesNo|mbIconQuestion|mbSystemModal|mbSetForeground|mbTopMost,
	)
	return ret == idYes
}

// desktopDir возвращает папку рабочего стола пользователя (куда класть принятые
// файлы). При недоступности — домашняя папка.
func desktopDir() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return "."
	}
	d := filepath.Join(home, "Desktop")
	if st, err := os.Stat(d); err == nil && st.IsDir() {
		return d
	}
	return home
}
