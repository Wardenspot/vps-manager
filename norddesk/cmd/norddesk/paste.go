package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"time"

	"norddesk/internal/wininput"
	"norddesk/internal/wire"
)

// sessionTitleMarker — метка в заголовке окна сеанса: по ней перехватчик
// клавиатуры понимает, что активно именно окно сеанса (см. app.js).
const sessionTitleMarker = "NordDesk · сеанс"

// keyEvent — нажатие, перехваченное у системы.
type keyEvent struct {
	vk   uint16
	down bool
	ext  bool
}

// onHookKey: клавиша из перехватчика → на тот компьютер. Ctrl+V обрабатываем
// особо: если в буфере файлы — передаём их и вставляем там; если текст —
// сначала отправляем текст, потом само нажатие.
func (a *app) onHookKey(ev keyEvent) {
	switch ev.vk {
	case 0x11, 0xA2, 0xA3: // Ctrl
		a.ctrlDown = ev.down
	}
	if ev.vk == 'V' {
		if ev.down && a.ctrlDown && a.ctrlV() {
			a.vSwallowed = true
			return
		}
		if !ev.down && a.vSwallowed {
			a.vSwallowed = false
			return
		}
	}
	a.sendKey(ev.vk, ev.down, ev.ext)
}

func (a *app) sendKey(vk uint16, down, ext bool) {
	var flags byte
	if ext {
		flags = 1
	}
	a.toHost(wire.TypeKey, []byte{byte(vk >> 8), byte(vk), b2u(down), flags})
}

// ctrlV готовит вставку. true — файлы ушли передаваться, само нажатие V
// отправлять не нужно (его сделает та сторона, когда файлы дойдут).
func (a *app) ctrlV() bool {
	if files, err := wininput.GetClipboardFiles(); err == nil && len(files) > 0 {
		go a.sendPaths(files, "clipboard")
		return true
	}
	if text, err := wininput.GetClipboardText(); err == nil && text != "" {
		a.toHost(wire.TypeSetClip, []byte(text))
	}
	return false
}

// sendPaths передаёт файлы и папки на тот компьютер пакетом. to = "clipboard" —
// вставить через буфер обмена (Ctrl+V там, где фокус), "desktop" — на рабочий стол.
func (a *app) sendPaths(paths []string, to string) {
	a.xferMu.Lock()
	defer a.xferMu.Unlock()

	var total int64
	for _, p := range paths {
		filepath.WalkDir(p, func(_ string, d fs.DirEntry, err error) error {
			if err == nil && d.Type().IsRegular() {
				if info, err := d.Info(); err == nil {
					total += info.Size()
				}
			}
			return nil
		})
	}

	batch := randomHex(8)
	var items []string
	var sent int64
	lastUI := time.Time{}
	progress := func(name string) {
		if time.Since(lastUI) < 300*time.Millisecond {
			return
		}
		lastUI = time.Now()
		pct := 100
		if total > 0 {
			pct = int(sent * 100 / total)
		}
		a.sendUIText(map[string]any{"ev": "xfer", "text": fmt.Sprintf("Передача: %s — %d%%", name, pct)})
	}

	for _, root := range paths {
		base := filepath.Base(root)
		items = append(items, base)
		err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil // недоступное пропускаем
			}
			if !a.connected() {
				return fs.SkipAll
			}
			rel := base
			if path != root {
				r, _ := filepath.Rel(root, path)
				rel = base + "/" + filepath.ToSlash(r)
			}
			if d.IsDir() {
				a.sendMeta(map[string]any{"batch": batch, "rel": rel, "dir": true})
				return nil
			}
			if !d.Type().IsRegular() {
				return nil
			}
			info, err := d.Info()
			if err != nil {
				return nil
			}
			f, err := os.Open(path)
			if err != nil {
				return nil
			}
			defer f.Close()
			a.sendMeta(map[string]any{"batch": batch, "rel": rel, "name": d.Name(), "size": info.Size()})
			buf := make([]byte, 64<<10)
			for {
				n, rerr := f.Read(buf)
				if n > 0 {
					a.toHost(wire.TypeFileData, buf[:n])
					sent += int64(n)
					progress(d.Name())
				}
				if rerr == io.EOF {
					break
				}
				if rerr != nil {
					return nil // файл оборвётся — та сторона удалит недокачанный
				}
			}
			a.toHost(wire.TypeFileEnd, nil)
			return nil
		})
		if err != nil {
			log.Printf("передача %s: %v", root, err)
		}
	}
	if !a.connected() {
		a.sendUIText(map[string]any{"ev": "xfer", "text": "Связь прервалась — файлы не переданы.", "done": true})
		return
	}
	done, _ := json.Marshal(map[string]any{"batch": batch, "items": items, "to": to})
	a.toHost(wire.TypePasteDone, done)
	msg := "Файлы переданы — вставляются на той стороне."
	if to == "desktop" {
		msg = "Файлы переданы на рабочий стол той стороны."
	}
	a.sendUIText(map[string]any{"ev": "xfer", "text": msg, "done": true})
}

func (a *app) sendMeta(m map[string]any) {
	b, _ := json.Marshal(m)
	a.toHost(wire.TypeFileStart, b)
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func b2u(b bool) byte {
	if b {
		return 1
	}
	return 0
}
