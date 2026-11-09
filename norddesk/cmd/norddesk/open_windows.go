//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
)

// launchUI открывает окно как HTML-приложение в Edge (режим --app), как это
// сделано у NordKey.exe. Если Edge не найден — пробуем Chrome, затем системный
// обработчик ссылок.
func launchUI(url string) error {
	candidates := []string{
		filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(os.Getenv("ProgramFiles"), "Google", "Chrome", "Application", "chrome.exe"),
		filepath.Join(os.Getenv("ProgramFiles(x86)"), "Google", "Chrome", "Application", "chrome.exe"),
	}
	for _, exe := range candidates {
		if _, err := os.Stat(exe); err == nil {
			cmd := exec.Command(exe,
				"--app="+url,
				"--window-size=1280,800",
				"--disable-features=Translate",
			)
			return cmd.Start()
		}
	}
	// Фолбэк — системный обработчик (откроется во вкладке браузера).
	return exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
}
