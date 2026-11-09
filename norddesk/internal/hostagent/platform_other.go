//go:build !windows

package hostagent

// DesktopDir — папка рабочего стола (на не-Windows — для разработки и тестов).
func DesktopDir() string { return fallbackDesktop() }

// ConfirmDialog на не-Windows нет графического окна согласия — отказываем.
func ConfirmDialog(title, text string) bool { return false }
