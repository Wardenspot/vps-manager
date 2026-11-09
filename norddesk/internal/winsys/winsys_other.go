//go:build !windows

// Заглушка для сборки на не-Windows.
package winsys

import "errors"

// ErrDenied — на запрос прав администратора ответили отказом.
var ErrDenied = errors.New("winsys: запрос прав администратора отклонён")

// ShellRun на не-Windows не поддерживается.
func ShellRun(verb, file, params, dir string) error {
	return errors.New("winsys: запуск программ доступен только на Windows")
}

// IsElevated на не-Windows всегда false.
func IsElevated() bool { return false }
