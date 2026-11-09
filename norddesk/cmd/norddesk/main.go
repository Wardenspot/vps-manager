// Команда norddesk — единое приложение NordDesk (как AnyDesk).
//
// Одно окно, два блока:
//   - «Это рабочее место»: постоянный ID этого компьютера и одноразовый код —
//     их диктуют тому, кто будет подключаться;
//   - «Подключиться к другому»: ввести ID и код другого компьютера.
//
// Окно — HTML в Edge (режим приложения), бэкенд — на 127.0.0.1. Входящую часть
// ведёт hostagent, исходящая — мост «окно ⇆ сквозной канал к чужому хосту».
//
// Закрыл окно — приложение завершилось и компьютер больше не в сети: никакого
// невидимого фонового доступа.
package main

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"norddesk/internal/hostagent"
	"norddesk/internal/localws"
	"norddesk/internal/relaylink"
	"norddesk/internal/relaysrv"
	"norddesk/internal/secure"
	"norddesk/internal/wire"
)

//go:embed web
var webFS embed.FS

// defaultRelay задаётся при сборке: -ldflags "-X main.defaultRelay=IP:PORT".
// Так скачанный NordDesk.exe сразу работает через нужный релей.
var defaultRelay = "127.0.0.1:8765"

// uiPort — постоянный порт окна: по нему вторая копия находит первую.
const uiPort = 47812

const handshakeTimeout = 20 * time.Second

type app struct {
	relayAddr string
	token     string
	agent     *hostagent.Agent // nil — входящие подключения выключены
	stop      chan struct{}
	stopOnce  sync.Once

	mu     sync.Mutex
	relay  net.Conn     // исходящее: соединение с релеем
	host   *secure.Conn // исходящее: сквозной канал к чужому хосту
	hmu    sync.Mutex   // запись в host (окон может быть несколько)
	ui     *localws.Conn
	uiOpen int
	uiEver bool
}

func main() {
	relayAddr := flag.String("relay", defaultRelay, "адрес релея host:port")
	port := flag.Int("port", uiPort, "локальный порт окна (0 — случайный)")
	noOpen := flag.Bool("no-open", false, "не открывать окно автоматически")
	noHost := flag.Bool("no-host", false, "не принимать входящие подключения")
	cfgDir := flag.String("config", "", `папка настроек (по умолчанию %AppData%\NordDesk)`)
	flag.Parse()

	dir := *cfgDir
	if dir == "" {
		dir = defaultConfigDir()
	}
	os.MkdirAll(dir, 0o700)
	setupLog(dir)

	ln, err := listenUI(*port, dir)
	if ln == nil {
		if err != nil {
			log.Fatalf("окно: %v", err)
		}
		return // уже запущено — показали окно первой копии
	}

	tok := make([]byte, 16)
	rand.Read(tok)
	a := &app{relayAddr: *relayAddr, token: hex.EncodeToString(tok), stop: make(chan struct{})}
	uiURL := fmt.Sprintf("http://%s/?token=%s", ln.Addr().String(), a.token)
	os.WriteFile(filepath.Join(dir, "ui.url"), []byte(uiURL), 0o600)
	log.Printf("NordDesk запущен, релей %s, окно %s", *relayAddr, ln.Addr())

	if hostSupported && !*noHost {
		a.agent = hostagent.New(hostagent.Config{
			Relay: *relayAddr,
			ID:    loadOrCreateID(dir),
			Confirm: func() bool {
				return hostagent.ConfirmDialog("NordDesk — входящее подключение",
					"К этому компьютеру запрашивают удалённый доступ.\n"+
						"Код подключения введён верно.\n\n"+
						"Разрешить управление компьютером?")
			},
			OnStatus: a.sendSelf,
			Logf:     log.Printf,
		})
		go a.agent.Run(a.stop)
	}

	sub, _ := fs.Sub(webFS, "web")
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.serveIndex(sub))
	mux.Handle("/static/", http.StripPrefix("/static/", http.FileServer(http.FS(sub))))
	mux.HandleFunc("/ws", a.serveWS)
	mux.HandleFunc("/ping", a.servePing)

	if !*noOpen {
		go func() {
			time.Sleep(200 * time.Millisecond)
			if err := launchUI(uiURL); err != nil {
				log.Printf("не удалось открыть окно: %v", err)
			}
		}()
		// Если окно так и не открылось — не остаёмся невидимо в сети.
		go func() {
			time.Sleep(60 * time.Second)
			a.mu.Lock()
			ever := a.uiEver
			a.mu.Unlock()
			if !ever {
				log.Printf("окно не открылось за минуту — выход")
				a.shutdown()
			}
		}()
	}
	log.Fatal(http.Serve(ln, mux))
}

// --- окно ---

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
		boot := fmt.Sprintf(`<script>window.NDSK_TOKEN=%q;</script>`, a.token)
		out := strings.Replace(string(raw), "<!--BOOT-->", boot, 1)
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		io.WriteString(w, out)
	}
}

func (a *app) servePing(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("token") != a.token {
		http.Error(w, "forbidden", 403)
		return
	}
	io.WriteString(w, "norddesk")
}

func (a *app) serveWS(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("token") != a.token {
		http.Error(w, "forbidden", 403)
		return
	}
	c, err := localws.Accept(w, r)
	if err != nil {
		log.Printf("ws: %v", err)
		return
	}
	a.mu.Lock()
	a.ui = c
	a.uiOpen++
	a.uiEver = true
	a.mu.Unlock()

	if a.agent != nil {
		a.sendSelf(a.agent.Status())
	} else {
		a.sendUIText(map[string]any{"ev": "self", "state": "disabled"})
	}
	a.uiLoop(c)
	a.uiGone(c)
}

// uiLoop читает сообщения окна: текст — команды (JSON), бинарь — ввод/файлы.
func (a *app) uiLoop(c *localws.Conn) {
	for {
		op, data, err := c.ReadMessage()
		if err != nil {
			return
		}
		switch {
		case op == localws.OpText:
			a.handleCmd(data)
		case op == localws.OpBinary && len(data) >= 1:
			a.handleBinary(data[0], data[1:])
		}
	}
}

// uiGone: окно закрылось. Если окон не осталось — через несколько секунд
// выходим (перезагрузка страницы успевает переподключиться).
func (a *app) uiGone(c *localws.Conn) {
	c.Close()
	a.mu.Lock()
	a.uiOpen--
	cur := a.ui == c
	if cur {
		a.ui = nil
	}
	n := a.uiOpen
	a.mu.Unlock()
	if cur {
		a.teardownHost()
	}
	if n > 0 {
		return
	}
	go func() {
		time.Sleep(5 * time.Second)
		a.mu.Lock()
		n := a.uiOpen
		a.mu.Unlock()
		if n == 0 {
			log.Printf("окно закрыто — выход")
			a.shutdown()
		}
	}()
}

func (a *app) shutdown() {
	a.stopOnce.Do(func() { close(a.stop) })
	a.teardownHost()
	time.Sleep(300 * time.Millisecond) // дать агенту закрыть соединение
	os.Exit(0)
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
	case "newcode":
		if a.agent != nil {
			a.agent.RefreshCode()
		}
	case "endincoming":
		if a.agent != nil {
			a.agent.EndSession()
		}
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

// handleBinary: первый байт = тип wire (мышь/клавиатура/кусок файла).
func (a *app) handleBinary(kind byte, payload []byte) {
	switch wire.Type(kind) {
	case wire.TypeMouseMove, wire.TypeMouseBtn, wire.TypeMouseWheel,
		wire.TypeKey, wire.TypeFileData:
		a.toHost(wire.Type(kind), payload)
	}
}

// --- статус «этого рабочего места» ---

type selfEvent struct {
	Ev string `json:"ev"`
	hostagent.Status
}

func (a *app) sendSelf(st hostagent.Status) {
	a.sendUIText(selfEvent{Ev: "self", Status: st})
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

// --- исходящее подключение ---

func (a *app) connect(id, code string) {
	a.teardownHost()
	id = strings.Join(strings.Fields(id), "")
	code = strings.Join(strings.Fields(code), "")
	if !relaysrv.ValidID(id) {
		a.status("Неверный ID — нужны только цифры.")
		return
	}
	if a.agent != nil && id == a.agent.Status().ID {
		a.status("Это ID этого же компьютера — введите ID другого.")
		return
	}
	a.status("Подключение...")

	relay, err := relaylink.DialJoin(a.relayAddr, id, 15*time.Second)
	if err != nil {
		switch err {
		case relaylink.ErrNoHost:
			a.status("Компьютер с таким ID не в сети (NordDesk на нём закрыт?).")
		case relaylink.ErrBusy:
			a.status("К этому компьютеру уже подключены.")
		default:
			a.status("Нет связи с сервером NordDesk.")
			log.Printf("релей: %v", err)
		}
		return
	}

	a.status("Проверка кода...")
	relay.SetDeadline(time.Now().Add(handshakeTimeout))
	host, err := secure.Handshake(relay, secure.RoleViewer, id, code)
	relay.SetDeadline(time.Time{})
	if err != nil {
		relay.Close()
		if err == secure.ErrConfirm {
			a.status("Неверный код подключения.")
		} else {
			a.status("Не удалось установить защищённое соединение.")
			log.Printf("рукопожатие: %v", err)
		}
		return
	}

	a.mu.Lock()
	a.relay, a.host = relay, host
	a.mu.Unlock()

	a.status("Код верный. Ждём, пока на той стороне нажмут «Да»...")
	a.hostReadLoop(host)
}

func (a *app) toHost(t wire.Type, data []byte) {
	a.mu.Lock()
	host := a.host
	a.mu.Unlock()
	if host == nil {
		return
	}
	a.hmu.Lock()
	host.WriteFrame(wire.Encode(t, data))
	a.hmu.Unlock()
}

// hostReadLoop переправляет сообщения чужого хоста в окно.
func (a *app) hostReadLoop(host *secure.Conn) {
	for {
		raw, err := host.ReadFrame()
		if err != nil {
			a.sendUIText(map[string]any{"ev": "ended", "text": "Соединение закрыто."})
			a.teardownHost()
			return
		}
		m, err := wire.Decode(raw)
		if err != nil {
			continue
		}
		switch m.Type {
		case wire.TypeScreenInfo:
			var si struct{ W, H int }
			json.Unmarshal(m.Data, &si)
			a.sendUIText(map[string]any{"ev": "screeninfo", "w": si.W, "h": si.H})
		case wire.TypeTile:
			a.sendUIBinary(2, m.Data) // окно знает: 2 = тайл
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

// --- настройки, лог, одна копия ---

func defaultConfigDir() string {
	d, err := os.UserConfigDir()
	if err != nil || d == "" {
		return "."
	}
	return filepath.Join(d, "NordDesk")
}

// loadOrCreateID: постоянный ID компьютера — создаётся один раз и хранится в
// папке настроек, как «адрес» у AnyDesk.
func loadOrCreateID(dir string) string {
	p := filepath.Join(dir, "id")
	if b, err := os.ReadFile(p); err == nil {
		if id := strings.TrimSpace(string(b)); relaysrv.ValidID(id) {
			return id
		}
	}
	id := relaylink.NewID()
	if err := os.WriteFile(p, []byte(id+"\n"), 0o600); err != nil {
		log.Printf("не удалось сохранить ID: %v", err)
	}
	return id
}

func setupLog(dir string) {
	p := filepath.Join(dir, "norddesk.log")
	if st, err := os.Stat(p); err == nil && st.Size() > 5<<20 {
		os.Rename(p, p+".old")
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	// Файл первым: у оконного exe нет консоли, запись в stderr может падать.
	log.SetOutput(io.MultiWriter(f, os.Stderr))
}

// listenUI занимает порт окна. Если его держит другая копия NordDesk —
// показывает её окно и возвращает (nil, nil): эта копия должна выйти.
func listenUI(port int, dir string) (net.Listener, error) {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err == nil {
		return ln, nil
	}
	if b, rerr := os.ReadFile(filepath.Join(dir, "ui.url")); rerr == nil {
		u := strings.TrimSpace(string(b))
		if pingUI(u) {
			log.Printf("NordDesk уже запущен — показываю его окно")
			return nil, launchUI(u)
		}
	}
	// Порт занят чем-то посторонним — берём случайный.
	return net.Listen("tcp", "127.0.0.1:0")
}

func pingUI(uiURL string) bool {
	u, err := url.Parse(uiURL)
	if err != nil {
		return false
	}
	u.Path = "/ping"
	client := &http.Client{Timeout: 2 * time.Second, Transport: &http.Transport{Proxy: nil}}
	resp, err := client.Get(u.String())
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 64))
	return resp.StatusCode == 200 && string(b) == "norddesk"
}
