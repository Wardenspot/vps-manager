package hostagent

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/user"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"norddesk/internal/screen"
	"norddesk/internal/secure"
	"norddesk/internal/wininput"
	"norddesk/internal/winsys"
	"norddesk/internal/wire"
)

// Capturer — источник кадров экрана (на Windows — screen.Capturer).
type Capturer interface {
	Size() (int, int)
	Grab(quality int) ([]wire.Tile, error)
	Close() error
}

var newCapturer = func() (Capturer, error) {
	c, err := screen.New()
	if err != nil {
		return nil, err
	}
	return c, nil
}

// SetCaptureFunc подменяет захват экрана — для проверок полного сеанса без
// Windows (сеть, шифрование, ввод и файлы при этом настоящие).
func SetCaptureFunc(f func() (Capturer, error)) { newCapturer = f }

// sessionEnv — то, что сеансу даёт агент.
type sessionEnv struct {
	filesDir   string
	logf       func(string, ...any)
	elevate    func() (string, error) // перезапуск NordDesk с правами администратора
	onElevated func()                 // вызывается после отправки кода переподключения
}

// session — один входящий сеанс. Защищённый канал не потокобезопасен на
// запись, поэтому все отправки идут под wmu.
type session struct {
	sc     *secure.Conn
	env    sessionEnv
	wmu    sync.Mutex
	q      atomic.Int32 // качество JPEG
	fps    atomic.Int32
	ownSeq atomic.Uint32 // номер изменения буфера, сделанного нами (не отправлять обратно)
	busy   atomic.Bool   // идёт действие, которое ждёт UAC
	done   chan struct{}
	once   sync.Once
}

func (s *session) stop() { s.once.Do(func() { close(s.done) }) }

func (s *session) send(t wire.Type, data []byte) error {
	s.wmu.Lock()
	defer s.wmu.Unlock()
	return s.sc.WriteFrame(wire.Encode(t, data))
}

func (s *session) notice(text string) { s.send(wire.TypeNotice, []byte(text)) }

// runSession транслирует экран и обрабатывает ввод до разрыва соединения.
func runSession(sc *secure.Conn, env sessionEnv) error {
	s := &session{sc: sc, env: env, done: make(chan struct{})}
	s.q.Store(70)
	s.fps.Store(12)

	grab, err := newCapturer()
	if err != nil {
		s.send(wire.TypeSessionEnd, []byte("На той стороне не удалось захватить экран: "+err.Error()))
		return fmt.Errorf("захват экрана: %w", err)
	}
	defer func() {
		if grab != nil {
			grab.Close()
		}
	}()

	w, h := grab.Size()
	if err := s.sendScreenInfo(w, h); err != nil {
		return err
	}
	s.send(wire.TypeHostInfo, hostInfo())

	go s.readLoop()
	go s.clipLoop()

	unavailable := false
	for {
		select {
		case <-s.done:
			return nil
		default:
		}
		start := time.Now()

		// Пока открыт запрос UAC или экран блокировки, рабочий стол недоступен
		// для захвата. Не рвём сеанс, а ждём и пересоздаём захват.
		if grab == nil {
			if g, err := newCapturer(); err == nil {
				grab = g
				if nw, nh := grab.Size(); nw != w || nh != h {
					w, h = nw, nh
					s.sendScreenInfo(w, h)
				}
			}
		}
		var tiles []wire.Tile
		if grab != nil {
			if tiles, err = grab.Grab(int(s.q.Load())); err != nil {
				grab.Close()
				grab = nil
			}
		}
		if grab == nil {
			if !unavailable {
				unavailable = true
				s.notice("Экран той стороны сейчас недоступен: открыт запрос UAC или экран блокировки. Там нужно ответить на запрос.")
			}
			if !s.sleep(500 * time.Millisecond) {
				return nil
			}
			continue
		}
		if unavailable {
			unavailable = false
			s.notice("Экран снова доступен.")
		}

		for _, t := range tiles {
			if err := s.send(wire.TypeTile, t.Encode()); err != nil {
				s.stop()
				return nil // клиент отключился
			}
		}
		frame := time.Second / time.Duration(max(1, int(s.fps.Load())))
		if d := frame - time.Since(start); d > 0 && !s.sleep(d) {
			return nil
		}
	}
}

// sleep ждёт d; false — сеанс завершён.
func (s *session) sleep(d time.Duration) bool {
	select {
	case <-s.done:
		return false
	case <-time.After(d):
		return true
	}
}

func (s *session) sendScreenInfo(w, h int) error {
	info, _ := json.Marshal(map[string]int{"w": w, "h": h})
	return s.send(wire.TypeScreenInfo, info)
}

// hostInfo — имя компьютера, пользователь и права (для панели клиента).
func hostInfo() []byte {
	pc, _ := os.Hostname()
	name := ""
	if u, err := user.Current(); err == nil {
		name = u.Username
		if i := strings.LastIndexAny(name, `\/`); i >= 0 {
			name = name[i+1:]
		}
	}
	b, _ := json.Marshal(map[string]any{"pc": pc, "user": name, "admin": winsys.IsElevated()})
	return b
}

// clipLoop: скопировали текст на этом компьютере → отправляем клиенту, чтобы
// Ctrl+C здесь и Ctrl+V у себя работали как в одной системе.
func (s *session) clipLoop() {
	last := wininput.ClipboardSeq()
	t := time.NewTicker(500 * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-s.done:
			return
		case <-t.C:
		}
		seq := wininput.ClipboardSeq()
		if seq == last {
			continue
		}
		last = seq
		if seq == s.ownSeq.Load() {
			continue // это мы сами положили присланное клиентом
		}
		text, err := wininput.GetClipboardText()
		if err != nil || text == "" || len(text) > 1<<20 {
			continue
		}
		s.send(wire.TypeClipboard, []byte(text))
	}
}

// readLoop принимает от клиента ввод, буфер обмена, файлы и действия.
func (s *session) readLoop() {
	defer s.stop()
	rx := newFileReceiver(s.env.filesDir, s.env.logf)
	defer rx.abort()

	for {
		raw, err := s.sc.ReadFrame()
		if err != nil {
			return
		}
		m, err := wire.Decode(raw)
		if err != nil {
			return
		}
		d := m.Data
		switch m.Type {
		case wire.TypeMouseMove:
			if len(d) >= 4 {
				wininput.MouseMove(int(be16(d, 0)), int(be16(d, 2)))
			}
		case wire.TypeMouseBtn:
			if len(d) >= 2 {
				wininput.MouseButton(int(d[0]), d[1] != 0)
			}
		case wire.TypeMouseWheel:
			if len(d) >= 2 {
				wininput.MouseWheel(int(int16(be16(d, 0))))
			}
		case wire.TypeKey:
			if len(d) >= 3 {
				ext := len(d) >= 4 && d[3]&1 != 0
				wininput.Key(be16(d, 0), d[2] != 0, ext)
			}
		case wire.TypeSetClip:
			if err := wininput.SetClipboardText(string(d)); err == nil {
				s.ownSeq.Store(wininput.ClipboardSeq())
			}
		case wire.TypeCombo:
			if err := wininput.SendCombo(string(d)); err != nil {
				s.env.logf("комбинация: %v", err)
			}
		case wire.TypeQuality:
			if len(d) >= 2 {
				s.q.Store(int32(clamp(int(d[0]), 20, 95)))
				s.fps.Store(int32(clamp(int(d[1]), 1, 30)))
			}
		case wire.TypeAction:
			go s.runAction(string(d))
		case wire.TypeFileStart:
			var meta fileMeta
			if json.Unmarshal(d, &meta) == nil {
				rx.start(meta)
			}
		case wire.TypeFileData:
			rx.write(d)
		case wire.TypeFileEnd:
			rx.finish()
		case wire.TypePasteDone:
			var p struct {
				Batch string   `json:"batch"`
				Items []string `json:"items"`
				To    string   `json:"to"` // "desktop" — на рабочий стол, иначе — вставить (Ctrl+V)
			}
			if json.Unmarshal(d, &p) == nil {
				if p.To == "desktop" {
					s.toDesktop(rx, p.Batch, p.Items)
				} else {
					s.paste(rx, p.Batch, p.Items)
				}
			}
		}
	}
}

// paste кладёт принятые файлы в буфер обмена как «Копировать» в Проводнике и
// нажимает Ctrl+V — файлы вставляются туда, где сейчас фокус (папка, рабочий стол).
func (s *session) paste(rx *fileReceiver, batch string, items []string) {
	paths, err := rx.batchItems(batch, items)
	if err != nil {
		s.notice("Не удалось вставить файлы: " + err.Error())
		return
	}
	if err := wininput.SetClipboardFiles(paths); err != nil {
		s.notice("Не удалось положить файлы в буфер обмена: " + err.Error())
		return
	}
	s.ownSeq.Store(wininput.ClipboardSeq())
	time.Sleep(60 * time.Millisecond)
	wininput.SendCombo("ctrl-v")
	s.env.logf("вставлено файлов: %d", len(paths))
}

// toDesktop перекладывает принятые файлы и папки на рабочий стол (перетаскивание
// в окно). Существующее не перезаписывается: «отчёт (1).docx», «Папка (1)».
func (s *session) toDesktop(rx *fileReceiver, batch string, items []string) {
	paths, err := rx.batchItems(batch, items)
	if err != nil {
		s.notice("Не удалось сохранить файлы: " + err.Error())
		return
	}
	var saved []string
	for _, src := range paths {
		dst := uniquePath(rx.desktop, filepath.Base(src))
		if err := moveTree(src, dst); err != nil {
			s.env.logf("на рабочий стол: %v", err)
			continue
		}
		saved = append(saved, filepath.Base(dst))
	}
	switch {
	case len(saved) == 0:
		s.notice("Не удалось сохранить файлы на рабочий стол.")
	case len(saved) == 1:
		s.notice("Сохранено на рабочий стол той стороны: «" + saved[0] + "»")
	default:
		s.notice(fmt.Sprintf("Сохранено на рабочий стол той стороны: %d шт.", len(saved)))
	}
	s.env.logf("на рабочий стол: %v", saved)
}

// --- действия из меню инструментов ---

type action struct {
	verb, file, params string
	title              string
}

var actions = map[string]action{
	"cmd-admin": {"runas", "cmd.exe", "", "Командная строка (администратор)"},
	"ps-admin":  {"runas", "powershell.exe", "-NoExit", "PowerShell (администратор)"},
	"cmd":       {"open", "cmd.exe", "", "Командная строка"},
	"taskmgr":   {"open", "taskmgr.exe", "", "Диспетчер задач"},
	"explorer":  {"open", "explorer.exe", "", "Проводник"},
	"control":   {"open", "control.exe", "", "Панель управления"},
	"settings":  {"open", "ms-settings:", "", "Параметры Windows"},
	"sysinfo":   {"open", "msinfo32.exe", "", "Сведения о системе"},
	"services":  {"open", "services.msc", "", "Службы"},
	"devmgmt":   {"open", "devmgmt.msc", "", "Диспетчер устройств"},
	"eventvwr":  {"open", "eventvwr.msc", "", "Просмотр событий"},
	"netconn":   {"open", "ncpa.cpl", "", "Сетевые подключения"},
	"appwiz":    {"open", "appwiz.cpl", "", "Программы и компоненты"},
}

func (s *session) runAction(name string) {
	switch name {
	case "run":
		wininput.SendCombo("win-r")
		return
	case "elevate":
		s.requestElevation()
		return
	}
	a, ok := actions[name]
	if !ok {
		s.notice("Неизвестное действие.")
		return
	}
	elevated := winsys.IsElevated()
	if a.verb == "runas" && !elevated {
		if !s.busy.CompareAndSwap(false, true) {
			s.notice("Уже ждём ответа на запрос UAC на той стороне.")
			return
		}
		defer s.busy.Store(false)
		s.notice("«" + a.title + "»: на той стороне появился запрос UAC — там нужно нажать «Да».")
	}
	dir, _ := os.UserHomeDir()
	err := winsys.ShellRun(a.verb, a.file, a.params, dir)
	switch {
	case errors.Is(err, winsys.ErrDenied):
		s.notice("«" + a.title + "»: на той стороне отказались от запроса UAC.")
	case err != nil:
		s.notice("«" + a.title + "»: не удалось запустить.")
		s.env.logf("действие %s: %v", name, err)
	case a.verb == "runas" && !elevated:
		s.notice("«" + a.title + "» открыта. Чтобы печатать в окнах администратора, выдайте NordDesk права: Инструменты → Права администратора.")
	default:
		s.notice("«" + a.title + "» открыт(а).")
	}
}

// requestElevation перезапускает NordDesk на той стороне с правами
// администратора (там появится UAC) и даёт клиенту код для переподключения.
func (s *session) requestElevation() {
	if winsys.IsElevated() {
		s.notice("NordDesk на той стороне уже работает с правами администратора.")
		return
	}
	if s.env.elevate == nil {
		s.notice("Перезапуск с правами администратора поддерживается только в приложении NordDesk.")
		return
	}
	if !s.busy.CompareAndSwap(false, true) {
		s.notice("Уже ждём ответа на запрос UAC на той стороне.")
		return
	}
	defer s.busy.Store(false)
	s.notice("На той стороне появился запрос UAC — там нужно нажать «Да». Сеанс переподключится сам.")
	code, err := s.env.elevate()
	if err != nil {
		if errors.Is(err, winsys.ErrDenied) {
			s.notice("На той стороне отказались выдавать права администратора.")
		} else {
			s.notice("Не удалось перезапустить с правами администратора.")
			s.env.logf("повышение прав: %v", err)
		}
		return
	}
	if err := s.send(wire.TypeElevated, []byte(code)); err != nil {
		return
	}
	if s.env.onElevated != nil {
		s.env.onElevated()
	}
}

// --- приём файлов ---

type fileMeta struct {
	Name  string `json:"name"`
	Size  int64  `json:"size"`
	Batch string `json:"batch"` // пусто — на рабочий стол; иначе — пакет для вставки
	Rel   string `json:"rel"`   // путь внутри пакета (для папок)
	Dir   bool   `json:"dir"`   // это папка (создать)
}

// fileReceiver принимает один файл за раз. Недокачанный файл удаляется.
type fileReceiver struct {
	desktop string
	logf    func(string, ...any)
	batches map[string]string // пакет → временная папка
	f       *os.File
	path    string
	left    int64
}

func newFileReceiver(desktop string, logf func(string, ...any)) *fileReceiver {
	return &fileReceiver{desktop: desktop, logf: logf, batches: map[string]string{}}
}

func (r *fileReceiver) start(m fileMeta) {
	r.abort()
	if m.Size < 0 {
		return
	}
	var path string
	if m.Batch == "" {
		path = uniquePath(r.desktop, safeName(m.Name))
	} else {
		bdir, err := r.batchDir(m.Batch)
		if err != nil {
			r.logf("файл: %v", err)
			return
		}
		rel := m.Rel
		if rel == "" {
			rel = m.Name
		}
		rel = safeRel(rel)
		if rel == "" {
			return
		}
		path = filepath.Join(bdir, rel)
		if m.Dir {
			os.MkdirAll(path, 0o755)
			return
		}
		os.MkdirAll(filepath.Dir(path), 0o755)
		path = uniquePath(filepath.Dir(path), filepath.Base(path))
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		r.logf("файл: не удалось создать %s: %v", path, err)
		return
	}
	r.f, r.path, r.left = f, path, m.Size
}

func (r *fileReceiver) write(b []byte) {
	if r.f == nil {
		return
	}
	if int64(len(b)) > r.left {
		b = b[:r.left] // не даём записать больше заявленного размера
	}
	if _, err := r.f.Write(b); err != nil {
		r.logf("файл: запись: %v", err)
		r.abort()
		return
	}
	r.left -= int64(len(b))
}

func (r *fileReceiver) finish() {
	if r.f == nil {
		return
	}
	if r.left != 0 {
		r.logf("файл %s пришёл не полностью — удалён", r.path)
		r.abort()
		return
	}
	r.f.Close()
	r.logf("файл принят: %s", r.path)
	r.f = nil
}

// abort закрывает и удаляет недокачанный файл.
func (r *fileReceiver) abort() {
	if r.f == nil {
		return
	}
	r.f.Close()
	os.Remove(r.path)
	r.f = nil
}

// batchDir — временная папка пакета для вставки через буфер обмена. Файлы
// живут там, пока их не вставят (Проводник копирует их оттуда).
func (r *fileReceiver) batchDir(batch string) (string, error) {
	if d, ok := r.batches[batch]; ok {
		return d, nil
	}
	cleanupOldBatches()
	d, err := os.MkdirTemp("", "NordDesk-paste-")
	if err != nil {
		return "", err
	}
	r.batches[batch] = d
	return d, nil
}

// batchItems — полные пути верхнеуровневых элементов пакета.
func (r *fileReceiver) batchItems(batch string, items []string) ([]string, error) {
	bdir, ok := r.batches[batch]
	if !ok {
		return nil, errors.New("файлы не дошли")
	}
	var out []string
	for _, it := range items {
		p := filepath.Join(bdir, safeRel(it))
		if _, err := os.Lstat(p); err == nil {
			out = append(out, p)
		}
	}
	if len(out) == 0 {
		return nil, errors.New("файлы не дошли")
	}
	return out, nil
}

// cleanupOldBatches удаляет временные папки вставки старше суток.
func cleanupOldBatches() {
	dirs, _ := filepath.Glob(filepath.Join(os.TempDir(), "NordDesk-paste-*"))
	for _, d := range dirs {
		if st, err := os.Stat(d); err == nil && time.Since(st.ModTime()) > 24*time.Hour {
			os.RemoveAll(d)
		}
	}
}

func be16(b []byte, off int) uint16 { return binary.BigEndian.Uint16(b[off:]) }

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
