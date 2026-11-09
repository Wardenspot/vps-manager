//go:build windows

// Package winsys — запуск программ через оболочку Windows (в т.ч. от имени
// администратора) и проверка прав текущего процесса.
package winsys

import (
	"errors"
	"runtime"
	"syscall"
	"unsafe"
)

var (
	shell32           = syscall.NewLazyDLL("shell32.dll")
	procShellExecuteW = shell32.NewProc("ShellExecuteW")
)

// ErrDenied — на запрос UAC ответили «Нет» (или закрыли его).
var ErrDenied = errors.New("winsys: запрос прав администратора отклонён")

const (
	swShowNormal      = 1
	seErrAccessDenied = 5
	errorCancelled    = 1223
	tokenElevation    = 20
)

// ShellRun запускает программу через оболочку. verb "runas" — с правами
// администратора: если текущий процесс не повышен, Windows покажет запрос UAC.
// Функция блокируется, пока на запрос не ответят.
func ShellRun(verb, file, params, dir string) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	ptr := func(s string) uintptr {
		if s == "" {
			return 0
		}
		p, _ := syscall.UTF16PtrFromString(s)
		return uintptr(unsafe.Pointer(p))
	}
	r, _, lastErr := procShellExecuteW.Call(0, ptr(verb), ptr(file), ptr(params), ptr(dir), swShowNormal)
	if r > 32 {
		return nil
	}
	if r == seErrAccessDenied || errors.Is(lastErr, syscall.Errno(errorCancelled)) {
		return ErrDenied
	}
	return errors.New("winsys: не удалось запустить " + file)
}

// IsElevated — запущен ли текущий процесс с правами администратора.
func IsElevated() bool {
	proc, err := syscall.GetCurrentProcess()
	if err != nil {
		return false
	}
	var tok syscall.Token
	if err := syscall.OpenProcessToken(proc, syscall.TOKEN_QUERY, &tok); err != nil {
		return false
	}
	defer tok.Close()
	var elevated, n uint32
	err = syscall.GetTokenInformation(tok, tokenElevation, (*byte)(unsafe.Pointer(&elevated)), 4, &n)
	return err == nil && elevated != 0
}
