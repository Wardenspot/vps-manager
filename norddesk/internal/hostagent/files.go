package hostagent

import (
	"fmt"
	"io"
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

// safeRel очищает относительный путь внутри пакета: каждый компонент — через
// safeName, «..» и пустые части выбрасываются. Выйти за пределы пакета нельзя.
func safeRel(rel string) string {
	var clean []string
	for _, p := range strings.Split(strings.ReplaceAll(rel, "\\", "/"), "/") {
		if p == "" || p == "." || p == ".." {
			continue
		}
		clean = append(clean, safeName(p))
	}
	return filepath.Join(clean...)
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

// moveTree переносит файл или папку. Если переименование невозможно (другой
// диск, например рабочий стол в OneDrive на другом томе) — копирует и удаляет.
func moveTree(src, dst string) error {
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	if err := copyTree(src, dst); err != nil {
		os.RemoveAll(dst)
		return err
	}
	return os.RemoveAll(src)
}

func copyTree(src, dst string) error {
	st, err := os.Lstat(src)
	if err != nil {
		return err
	}
	if st.IsDir() {
		if err := os.MkdirAll(dst, 0o755); err != nil {
			return err
		}
		entries, err := os.ReadDir(src)
		if err != nil {
			return err
		}
		for _, e := range entries {
			if err := copyTree(filepath.Join(src, e.Name()), filepath.Join(dst, e.Name())); err != nil {
				return err
			}
		}
		return nil
	}
	if !st.Mode().IsRegular() {
		return nil // ссылки и прочее не переносим
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
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
