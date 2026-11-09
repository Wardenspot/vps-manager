package hostagent

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"norddesk/internal/screen"
	"norddesk/internal/secure"
	"norddesk/internal/wininput"
	"norddesk/internal/wire"
)

// session — один входящий сеанс. Защищённый канал не потокобезопасен на
// запись, поэтому все отправки идут под wmu.
type session struct {
	sc    *secure.Conn
	wmu   sync.Mutex
	q     atomic.Int32 // качество JPEG
	fps   atomic.Int32
	done  chan struct{}
	once  sync.Once
	logf  func(string, ...any)
	files string
}

func (s *session) stop() { s.once.Do(func() { close(s.done) }) }

func (s *session) send(t wire.Type, data []byte) error {
	s.wmu.Lock()
	defer s.wmu.Unlock()
	return s.sc.WriteFrame(wire.Encode(t, data))
}

// runSession транслирует экран и обрабатывает ввод до разрыва соединения.
func runSession(sc *secure.Conn, filesDir string, logf func(string, ...any)) error {
	s := &session{sc: sc, done: make(chan struct{}), logf: logf, files: filesDir}
	s.q.Store(70)
	s.fps.Store(12)

	cap, err := screen.New()
	if err != nil {
		s.send(wire.TypeSessionEnd, []byte("На той стороне не удалось захватить экран: "+err.Error()))
		return fmt.Errorf("захват экрана: %w", err)
	}
	defer cap.Close()

	w, h := cap.Size()
	info, _ := json.Marshal(map[string]int{"w": w, "h": h})
	if err := s.send(wire.TypeScreenInfo, info); err != nil {
		return err
	}

	go s.readLoop()

	for {
		select {
		case <-s.done:
			return nil
		default:
		}
		start := time.Now()
		tiles, err := cap.Grab(int(s.q.Load()))
		if err != nil {
			s.stop()
			return fmt.Errorf("захват кадра: %w", err)
		}
		for _, t := range tiles {
			if err := s.send(wire.TypeTile, t.Encode()); err != nil {
				s.stop()
				return nil // клиент отключился
			}
		}
		frame := time.Second / time.Duration(max(1, int(s.fps.Load())))
		if d := frame - time.Since(start); d > 0 {
			select {
			case <-s.done:
				return nil
			case <-time.After(d):
			}
		}
	}
}

// readLoop принимает от клиента ввод, буфер обмена и файлы.
func (s *session) readLoop() {
	defer s.stop()
	rx := &fileReceiver{dir: s.files, logf: s.logf}
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
				wininput.Key(be16(d, 0), d[2] != 0)
			}
		case wire.TypeSetClip:
			wininput.SetClipboardText(string(d))
		case wire.TypeCombo:
			if err := wininput.SendCombo(string(d)); err != nil {
				s.logf("комбинация: %v", err)
			}
		case wire.TypeQuality:
			if len(d) >= 2 {
				s.q.Store(int32(clamp(int(d[0]), 20, 95)))
				s.fps.Store(int32(clamp(int(d[1]), 1, 30)))
			}
		case wire.TypeFileStart:
			var meta struct {
				Name string `json:"name"`
				Size int64  `json:"size"`
			}
			if json.Unmarshal(d, &meta) == nil {
				rx.start(meta.Name, meta.Size)
			}
		case wire.TypeFileData:
			rx.write(d)
		case wire.TypeFileEnd:
			rx.finish()
		}
	}
}

// fileReceiver принимает один файл за раз. Недокачанный файл удаляется.
type fileReceiver struct {
	dir  string
	logf func(string, ...any)
	f    *os.File
	path string
	left int64
}

func (r *fileReceiver) start(name string, size int64) {
	r.abort()
	if size < 0 {
		return
	}
	path := uniquePath(r.dir, safeName(name))
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		r.logf("файл: не удалось создать %s: %v", path, err)
		return
	}
	r.f, r.path, r.left = f, path, size
	r.logf("приём файла %s (%d байт)", path, size)
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
