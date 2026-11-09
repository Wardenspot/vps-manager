//go:build windows

// Команда host — агент NordDesk на управляемом ПК (Windows).
//
// Что делает:
//   - подключается к релею и регистрирует 9-значный ID;
//   - показывает ID и одноразовый код подключения;
//   - дожидается клиента, поднимает сквозной шифрованный канал (код служит
//     паролем, который клиент вводит);
//   - по умолчанию ещё и спрашивает согласие у человека за ПК («Разрешить?»);
//   - транслирует экран, принимает ввод, буфер обмена и файлы на рабочий стол.
//
// Модель доступа ровно как у готовых решений, но код свой: без верного кода и
// без «Да» на этом ПК сессия не начнётся.
package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"norddesk/internal/relaylink"
	"norddesk/internal/screen"
	"norddesk/internal/secure"
	"norddesk/internal/wininput"
	"norddesk/internal/wire"
)

func main() {
	relayAddr := flag.String("relay", "127.0.0.1:8765", "адрес релея host:port")
	fixedID := flag.String("id", "", "постоянный ID (по умолчанию случайный)")
	unattended := flag.String("pw", "", "постоянный пароль (режим без подтверждения, для своих машин)")
	noConfirm := flag.Bool("no-confirm", false, "не спрашивать согласие (ОПАСНО; по умолчанию спрашиваем)")
	once := flag.Bool("once", false, "завершиться после одной сессии")
	dir := flag.String("dir", "", "куда класть принятые файлы (по умолчанию Рабочий стол)")
	flag.Parse()

	if *dir == "" {
		*dir = desktopDir()
	}

	id := *fixedID
	if id == "" {
		id = relaylink.NewID()
	}

	for {
		if err := serveOne(*relayAddr, id, *unattended, !*noConfirm, *dir); err != nil {
			log.Printf("сессия: %v", err)
		}
		if *once {
			return
		}
		time.Sleep(1 * time.Second)
	}
}

func serveOne(relayAddr, id, unattended string, confirm bool, dir string) error {
	// Код подключения: постоянный (unattended) либо свежий 6-значный на сессию.
	code := unattended
	attended := unattended == ""
	if attended {
		code = relaylink.NewCode(6)
	}

	fmt.Println("==================================================")
	fmt.Println("  NordDesk — удалённый доступ к этому компьютеру")
	fmt.Println("==================================================")
	fmt.Printf("  ID:   %s\n", spaceID(id))
	if attended {
		fmt.Printf("  Код:  %s   (одноразовый, на это подключение)\n", code)
	} else {
		fmt.Printf("  Код:  (постоянный, задан при запуске)\n")
	}
	fmt.Println("  Ожидание подключения... (Ctrl+C — выход)")
	fmt.Println("--------------------------------------------------")

	conn, err := relaylink.DialHost(relayAddr, id, 15*time.Second)
	if err != nil {
		return fmt.Errorf("регистрация на релее: %w", err)
	}
	defer conn.Close()

	if err := relaylink.WaitPeer(conn, time.Time{}); err != nil {
		return fmt.Errorf("ожидание клиента: %w", err)
	}

	sc, err := secure.Handshake(conn, secure.RoleHost, id, code)
	if err != nil {
		// Чаще всего — неверный код либо попытка вмешательства в канал.
		return fmt.Errorf("защищённое рукопожатие: %w", err)
	}
	log.Printf("клиент прошёл проверку кода")

	// Согласие человека за ПК (в attended-режиме и если не отключено).
	if attended && confirm {
		ok := confirmDialog("NordDesk — входящее подключение",
			"К этому компьютеру запрашивают удалённый доступ.\n\n"+
				"Код подключения был введён верно.\n\nРазрешить управление?")
		if !ok {
			sendMsg(sc, nil, wire.TypeSessionEnd, []byte("подключение отклонено на стороне хоста"))
			return fmt.Errorf("отклонено пользователем")
		}
	}

	return runSession(sc, dir)
}

// session держит общий защищённый канал под мьютексом (канал не потокобезопасен).
type session struct {
	sc   *secure.Conn
	wmu  sync.Mutex
	q    atomic.Int32 // качество JPEG
	fps  atomic.Int32
	done chan struct{}
}

func runSession(sc *secure.Conn, dir string) error {
	cap, err := screen.New()
	if err != nil {
		return fmt.Errorf("захват экрана: %w", err)
	}
	defer cap.Close()

	s := &session{sc: sc, done: make(chan struct{})}
	s.q.Store(70)
	s.fps.Store(12)

	w, h := cap.Size()
	info, _ := json.Marshal(map[string]int{"w": w, "h": h})
	if err := s.send(wire.TypeScreenInfo, info); err != nil {
		return err
	}

	go s.readLoop(dir)

	// Цикл трансляции экрана.
	for {
		select {
		case <-s.done:
			return nil
		default:
		}
		start := time.Now()
		tiles, err := cap.Grab(int(s.q.Load()))
		if err != nil {
			close(s.done)
			return fmt.Errorf("Grab: %w", err)
		}
		for _, t := range tiles {
			if err := s.send(wire.TypeTile, t.Encode()); err != nil {
				close(s.done)
				return nil // клиент отключился
			}
		}
		// держим целевой fps
		frame := time.Second / time.Duration(maxi(1, int(s.fps.Load())))
		if d := frame - time.Since(start); d > 0 {
			time.Sleep(d)
		}
	}
}

func (s *session) send(t wire.Type, data []byte) error {
	return sendMsg(s.sc, &s.wmu, t, data)
}

// readLoop принимает сообщения от клиента: ввод, буфер обмена, файлы.
func (s *session) readLoop(dir string) {
	defer func() {
		select {
		case <-s.done:
		default:
			close(s.done)
		}
	}()

	var curFile *os.File
	var curLeft int64
	closeFile := func() {
		if curFile != nil {
			curFile.Close()
			curFile = nil
		}
	}
	defer closeFile()

	for {
		plain, err := s.sc.ReadFrame()
		if err != nil {
			return
		}
		m, err := wire.Read(bytes.NewReader(plain))
		if err != nil {
			return
		}
		switch m.Type {
		case wire.TypeMouseMove:
			if len(m.Data) >= 4 {
				wininput.MouseMove(int(be16(m.Data, 0)), int(be16(m.Data, 2)))
			}
		case wire.TypeMouseBtn:
			if len(m.Data) >= 2 {
				wininput.MouseButton(int(m.Data[0]), m.Data[1] != 0)
			}
		case wire.TypeMouseWheel:
			if len(m.Data) >= 2 {
				wininput.MouseWheel(int(int16(be16(m.Data, 0))))
			}
		case wire.TypeKey:
			if len(m.Data) >= 3 {
				wininput.Key(be16(m.Data, 0), m.Data[2] != 0)
			}
		case wire.TypeSetClip:
			wininput.SetClipboardText(string(m.Data))
		case wire.TypeCombo:
			if err := wininput.SendCombo(string(m.Data)); err != nil {
				log.Printf("комбинация: %v", err)
			}
		case wire.TypeQuality:
			if len(m.Data) >= 2 {
				s.q.Store(int32(clamp(int(m.Data[0]), 20, 95)))
				s.fps.Store(int32(clamp(int(m.Data[1]), 1, 30)))
			}
		case wire.TypeFileStart:
			closeFile()
			var meta struct {
				Name string `json:"name"`
				Size int64  `json:"size"`
			}
			if json.Unmarshal(m.Data, &meta) == nil {
				name := safeName(meta.Name)
				f, err := os.Create(filepath.Join(dir, name))
				if err == nil {
					curFile = f
					curLeft = meta.Size
					log.Printf("приём файла: %s (%d байт) → %s", name, meta.Size, dir)
				} else {
					log.Printf("не удалось создать файл: %v", err)
				}
			}
		case wire.TypeFileData:
			if curFile != nil {
				n := m.Data
				if int64(len(n)) > curLeft {
					n = n[:curLeft] // не даём превысить заявленный размер
				}
				curFile.Write(n)
				curLeft -= int64(len(n))
			}
		case wire.TypeFileEnd:
			closeFile()
			log.Printf("файл принят")
		}
	}
}

// sendMsg сериализует wire-сообщение и пишет его в защищённый канал под мьютексом.
func sendMsg(sc *secure.Conn, mu *sync.Mutex, t wire.Type, data []byte) error {
	var b bytes.Buffer
	if err := wire.Write(&b, t, data); err != nil {
		return err
	}
	if mu != nil {
		mu.Lock()
		defer mu.Unlock()
	}
	return sc.WriteFrame(b.Bytes())
}

// safeName оставляет только имя файла, без путей и обходов каталога.
func safeName(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	name = filepath.Base(name)
	name = strings.TrimSpace(name)
	if name == "" || name == "." || name == ".." {
		return fmt.Sprintf("norddesk-%d", time.Now().Unix())
	}
	return name
}

func spaceID(id string) string {
	if len(id) == 9 {
		return id[0:3] + " " + id[3:6] + " " + id[6:9]
	}
	return id
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

func maxi(a, b int) int {
	if a > b {
		return a
	}
	return b
}
