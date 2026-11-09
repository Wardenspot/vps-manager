// Команда viewer — клиент NordDesk (смотрелка/управление).
//
// Это нативное приложение: Go-бэкенд поднимает локальный веб-сервер на
// 127.0.0.1 и открывает окно HTML в Edge (режим приложения) — ровно как окно
// NordKey.exe. Внутри окна — canvas с экраном хоста, захват мыши/клавиатуры и
// перетаскивание файлов на удалённый рабочий стол.
//
// Бэкенд — это мост: локальный WebSocket (окно) ⇆ сквозной шифрованный канал
// (хост через релей). Никакой логики ввода/экрана на стороне окна нет, только
// отрисовка и события.
package main

import (
	"bytes"
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"sync"
	"time"

	"norddesk/internal/localws"
	"norddesk/internal/relaylink"
	"norddesk/internal/secure"
	"norddesk/internal/wire"
)

//go:embed web
var webFS embed.FS

type app struct {
	relayAddr string
	token     string
	presetID  string

	mu    sync.Mutex
	relay net.Conn     // соединение с релеем (через него — хост)
	host  *secure.Conn // защищённый канал к хосту
	ui    *localws.Conn
}

func main() {
	relayAddr := flag.String("relay", "127.0.0.1:8765", "адрес релея host:port")
	port := flag.Int("port", 0, "локальный порт окна (0 — случайный)")
	presetID := flag.String("id", "", "подставить ID в окно")
	noOpen := flag.Bool("no-open", false, "не открывать окно автоматически")
	flag.Parse()

	tok := make([]byte, 16)
	rand.Read(tok)
	a := &app{relayAddr: *relayAddr, token: hex.EncodeToString(tok), presetID: *presetID}

	sub, _ := fs.Sub(webFS, "web")
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.serveIndex(sub))
	mux.Handle("/static/", http.StripPrefix("/static/", http.FileServer(http.FS(sub))))
	mux.HandleFunc("/ws", a.serveWS)

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", *port))
	if err != nil {
		log.Fatalf("локальный сервер: %v", err)
	}
	url := fmt.Sprintf("http://%s/?token=%s", ln.Addr().String(), a.token)
	log.Printf("NordDesk viewer: %s", url)

	if !*noOpen {
		go func() {
			time.Sleep(300 * time.Millisecond)
			if err := launchUI(url); err != nil {
				log.Printf("не удалось открыть окно автоматически: %v (откройте ссылку вручную)", err)
			}
		}()
	}
	log.Fatal(http.Serve(ln, mux))
}

func (a *app) serveIndex(sub fs.FS) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		raw, err := fs.ReadFile(sub, "index.html")
		if err != nil {
			http.Error(w, "нет index.html", 500)
			return
		}
		// Подставляем токен и пресет ID в страницу.
		boot := fmt.Sprintf(`<script>window.NDSK_TOKEN=%q;window.NDSK_ID=%q;</script>`,
			a.token, a.presetID)
		out := bytes.Replace(raw, []byte("<!--BOOT-->"), []byte(boot), 1)
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(out)
	}
}

func (a *app) serveWS(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("token") != a.token {
		http.Error(w, "forbidden", 403)
		return
	}
	c, err := localws.Accept(w, r)
	if err != nil {
		log.Printf("ws accept: %v", err)
		return
	}
	a.mu.Lock()
	a.ui = c
	a.mu.Unlock()
	a.uiLoop(c)
}

// uiLoop читает сообщения окна: текст — команды (JSON), бинарь — ввод/файлы.
func (a *app) uiLoop(c *localws.Conn) {
	defer a.teardown()
	for {
		op, data, err := c.ReadMessage()
		if err != nil {
			return
		}
		if op == localws.OpText {
			a.handleCmd(data)
		} else if op == localws.OpBinary && len(data) >= 1 {
			a.handleBinary(data[0], data[1:])
		}
	}
}

func (a *app) handleCmd(raw []byte) {
	var m struct {
		Cmd  string `json:"cmd"`
		ID   string `json:"id"`
		Code string `json:"code"`
		Text string `json:"text"`
		Name string `json:"name"`
		Size int64  `json:"size"`
		Q    int    `json:"q"`
		FPS  int    `json:"fps"`
	}
	if json.Unmarshal(raw, &m) != nil {
		return
	}
	switch m.Cmd {
	case "connect":
		go a.connect(m.ID, m.Code)
	case "disconnect":
		a.teardownHost()
	case "clip":
		a.toHost(wire.TypeSetClip, []byte(m.Text))
	case "combo":
		a.toHost(wire.TypeCombo, []byte(m.Name))
	case "quality":
		a.toHost(wire.TypeQuality, []byte{byte(m.Q), byte(m.FPS)})
	case "filestart":
		meta, _ := json.Marshal(map[string]any{"name": m.Name, "size": m.Size})
		a.toHost(wire.TypeFileStart, meta)
	case "fileend":
		a.toHost(wire.TypeFileEnd, nil)
	}
}

// handleBinary: первый байт окна = тип wire (мышь/клавиатура/кусок файла).
func (a *app) handleBinary(kind byte, payload []byte) {
	switch wire.Type(kind) {
	case wire.TypeMouseMove, wire.TypeMouseBtn, wire.TypeMouseWheel,
		wire.TypeKey, wire.TypeFileData:
		a.toHost(wire.Type(kind), payload)
	}
}

func (a *app) toHost(t wire.Type, data []byte) {
	a.mu.Lock()
	host := a.host
	a.mu.Unlock()
	if host == nil {
		return
	}
	var b bytes.Buffer
	wire.Write(&b, t, data)
	// Пишет только этот путь (из uiLoop), поэтому отдельный мьютекс не нужен.
	host.WriteFrame(b.Bytes())
}

func (a *app) status(text string) {
	a.sendUIText(map[string]any{"ev": "status", "text": text})
}

func (a *app) sendUIText(v any) {
	a.mu.Lock()
	ui := a.ui
	a.mu.Unlock()
	if ui == nil {
		return
	}
	b, _ := json.Marshal(v)
	ui.WriteText(b)
}

func (a *app) sendUIBinary(kind byte, payload []byte) {
	a.mu.Lock()
	ui := a.ui
	a.mu.Unlock()
	if ui == nil {
		return
	}
	ui.WriteBinary(append([]byte{kind}, payload...))
}

// connect: подключение к хосту по ID и коду через релей.
func (a *app) connect(id, code string) {
	a.teardownHost()
	a.status(fmt.Sprintf("Подключение к %s...", id))

	relay, err := relaylink.DialJoin(a.relayAddr, id, 15*time.Second)
	if err != nil {
		switch err {
		case relaylink.ErrNoHost:
			a.status("Хост с таким ID не в сети.")
		case relaylink.ErrBusy:
			a.status("К этому хосту уже подключены.")
		default:
			a.status("Ошибка связи с релеем: " + err.Error())
		}
		return
	}

	a.status("Проверка кода и установка шифрования...")
	host, err := secure.Handshake(relay, secure.RoleViewer, id, code)
	if err != nil {
		relay.Close()
		if err == secure.ErrConfirm {
			a.status("Неверный код подключения или вмешательство в канал.")
		} else {
			a.status("Рукопожатие не удалось: " + err.Error())
		}
		return
	}

	a.mu.Lock()
	a.relay = relay
	a.host = host
	a.mu.Unlock()

	a.status("Подключено. Ожидание экрана...")
	a.hostReadLoop(host)
}

// hostReadLoop читает сообщения хоста и переправляет их в окно.
func (a *app) hostReadLoop(host *secure.Conn) {
	for {
		plain, err := host.ReadFrame()
		if err != nil {
			a.sendUIText(map[string]any{"ev": "ended", "text": "Соединение закрыто."})
			a.teardownHost()
			return
		}
		m, err := wire.Read(bytes.NewReader(plain))
		if err != nil {
			continue
		}
		switch m.Type {
		case wire.TypeScreenInfo:
			var si struct{ W, H int }
			json.Unmarshal(m.Data, &si)
			a.sendUIText(map[string]any{"ev": "screeninfo", "w": si.W, "h": si.H})
		case wire.TypeTile:
			a.sendUIBinary(2, m.Data) // окно знает: kind 2 = тайл
		case wire.TypeClipboard:
			a.sendUIText(map[string]any{"ev": "clip", "text": string(m.Data)})
		case wire.TypeSessionEnd:
			a.sendUIText(map[string]any{"ev": "ended", "text": string(m.Data)})
			a.teardownHost()
			return
		}
	}
}

func (a *app) teardownHost() {
	a.mu.Lock()
	if a.relay != nil {
		a.relay.Close()
	}
	a.relay, a.host = nil, nil
	a.mu.Unlock()
}

func (a *app) teardown() {
	a.teardownHost()
	a.mu.Lock()
	a.ui = nil
	a.mu.Unlock()
}
