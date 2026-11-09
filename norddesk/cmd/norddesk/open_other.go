//go:build !windows

package main

import (
	"os/exec"
	"runtime"
)

// launchUI на не-Windows открывает ссылку системным способом (для разработки и
// тестов). Рабочая платформа клиента — Windows (Edge app-mode).
func launchUI(url string) error {
	switch runtime.GOOS {
	case "darwin":
		return exec.Command("open", url).Start()
	default:
		return exec.Command("xdg-open", url).Start()
	}
}
