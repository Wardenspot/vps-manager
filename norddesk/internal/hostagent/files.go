package hostagent

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// safeName оставляет только имя файла: без путей, обходов каталога,
// запрещённых в Windows символов и зарезервированных имён (CON, NUL, ...).
func safeName(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	name = filepath.Base(name)
	name = strings.Map(func(r rune) rune {
		if r < 32 || strings.ContainsRune(`<>:"/\|?*`, r) {
			return '_'
		}
		return r
	}, name)
	name = strings.TrimRight(strings.TrimSpace(name), ". ")
	if name == "" || name == "." || name == ".." {
		return fmt.Sprintf("norddesk-%d", time.Now().Unix())
	}
	base := strings.ToUpper(strings.TrimSuffix(name, filepath.Ext(name)))
	switch base {
	case "CON", "PRN", "AUX", "NUL",
		"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
		"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9":
		name = "_" + name
	}
	return name
}

// uniquePath не даёт перезаписать существующий файл: «отчёт.docx» →
// «отчёт (1).docx», «отчёт (2).docx» и т.д.
func uniquePath(dir, name string) string {
	p := filepath.Join(dir, name)
	if _, err := os.Lstat(p); os.IsNotExist(err) {
		return p
	}
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	for i := 1; i < 1000; i++ {
		p = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", base, i, ext))
		if _, err := os.Lstat(p); os.IsNotExist(err) {
			return p
		}
	}
	return filepath.Join(dir, fmt.Sprintf("%s-%d%s", base, time.Now().UnixNano(), ext))
}

// fallbackDesktop — папка Desktop в профиле, либо сам профиль.
func fallbackDesktop() string {
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
