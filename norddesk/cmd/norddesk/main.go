// Команда norddesk — единое приложение NordDesk (как AnyDesk).
//
// Одно окно, два блока:
//   - «Это рабочее место»: постоянный ID этого компьютера и одноразовый код;
//   - «Подключиться к другому»: ID и (необязательно) код другого компьютера.
//
// С кодом подключение идёт сразу. Без кода на той стороне спросят «Разрешить?»
// и покажут проверочный номер, который виден и у подключающегося.
//
// Окно — HTML в Edge (режим приложения), бэкенд — на 127.0.0.1. Закрыл окно —
// приложение завершилось и компьютер не в сети.
package main

import (
	"embed"
	"encoding/json"
	"errors"
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
	"norddesk/internal/wininput"
	"norddesk/internal/winsys"
	"norddesk/internal/wire"
)

//go:embed web
var webFS embed.FS

// defaultRelay задаётся при сборке: -ldflags "-X main.defaultRelay=IP:PORT".
var defaultRelay = "127.0.0.1:8765"

// uiPort — постоянный порт окна: по нему вторая копия находит первую.
const uiPort = 47812

const handshakeTimeout = 20 * time.Second

type app struct {
	relayAddr string
	dir       string
	token     string
	agent     *hostagent.Agent // nil — входящие подключения выключены
	hook      *keyHook
	stop      chan struct{}
	stopOnce  sync.Once
	xferMu    sync.Mutex // одна передача файлов за раз

	mu      sync.Mutex
	relay   net.Conn     // исходящее: соединение с релеем
	host    *secure.Conn // исходящее: сквозной канал к чужому хосту
	hostID  string       // к кому подключены
	resume  string       // код переподключения (та сторона перезапускается с правами администратора)
	syskeys bool         // перехватывать системные сочетания клавиш
	inSess  bool         // экран уже пришёл
	ui      *localws.Conn
	uiOpen  int
	uiEver  bool
	handoff string // токен окна новой копии после перезапуска с правами администратора

	hmu sync.Mutex // запись в host

	// состояние Ctrl+V (только горутина клавиш)
	ctrlDown   bool
	vSwallowed bool
}

func main() {
	relayAddr := flag.String("relay", defaultRelay, "адрес релея host:port")
	port := flag.Int("port", uiPort, "локальный порт окна (0 — случайный)")
	noOpen := flag.Bool("no-open", false, "не открывать окно автоматически")
	noHost := flag.Bool("no-host", false, "не принимать входящие подключения")
	cfgDir := flag.String("config", "", `папка настроек (по умолчанию %AppData%\NordDesk)`)
	resume := flag.Bool("resume", false, "служебный: продолжить после перезапуска с правами администратора")
	flag.Parse()

	dir := *cfgDir
	if dir == "" {
		dir = defaultConfigDir()
	}
	os.MkdirAll(dir, 0o700)
	setupLog(dir)

	var rs *resumeState
	if *resume {
		rs = takeResume(dir)
	}

	var ln net.Listener
	var err error
	if rs != nil {
		ln, err = waitListen(*port, 20*time.Second) // ждём, пока прежняя копия освободит порт
	} else {
		ln, err = listenUI(*port, dir)
		if ln == nil && err == nil {
			return // уже запущено — показали окно первой копии
		}
	}
	if err != nil {
		log.Fatalf("окно: %v", err)
	}

	a := &app{relayAddr: *relayAddr, dir: dir, token: randomHex(16), stop: make(chan struct{}), syskeys: true}
	if rs != nil {
		a.token = rs.UIToken // старое окно перейдёт на новую копию с этим токеном
	}
	a.hook = newKeyHook(a.onHookKey)
	uiURL := fmt.Sprintf("http://%s/?token=%s", ln.Addr().String(), a.token)
	os.WriteFile(filepath.Join(dir, "ui.url"), []byte(uiURL), 0o600)
	log.Printf("NordDesk запущен (администратор: %v), релей %s, окно %s", winsys.IsElevated(), *relayAddr, ln.Addr())

	if hostSupported && !*noHost {
		cfg := hostagent.Config{
			Relay: *relayAddr,
			ID:    loadOrCreateID(dir),
			Confirm: func(sas string) bool {
				return hostagent.ConfirmDialog("NordDesk — входящее подключение", hostagent.ConfirmText(sas))
			},
			OnStatus:   a.sendSelf,
			Logf:       log.Printf,
			Elevate:    a.elevate,
			OnElevated: a.onElevated,
		}
		if rs != nil {
			cfg.ResumeCode, cfg.ResumeUntil = rs.Code, rs.Until
		}
		a.agent = hostagent.New(cfg)
		go a.agent.Run(a.stop)
	}

	sub, _ := fs.Sub(webFS, "web")
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.serveIndex(sub))
	mux.Handle("/static/", http.StripPrefix("/static/", http.FileServer(http.FS(sub))))
	mux.HandleFunc("/ws", a.serveWS)
	mux.HandleFunc("/ping", a.servePing)

	if !*noOpen && rs == nil {
		go func() {
			time.Sleep(200 * time.Millisecond)
			if err := launchUI(uiURL); err != nil {
				log.Printf("не удалось открыть окно: %v", err)
			}
		}()
	}
	go a.watchdog(rs != nil, *noOpen, uiURL)
	log.Fatal(http.Serve(ln, mux))
}

// watchdog: если окно так и не подключилось — не остаёмся невидимо в сети.
// После перезапуска с правами администратора старое окно должно перейти на
// новую копию само; если нет — открываем своё.
func (a *app) watchdog(resumed, noOpen bool, uiURL string) {
	if noOpen && !resumed {
		return
	}
	if resumed {
		time.Sleep(15 * time.Second)
		if !a.uiSeen() {
			launchUI(uiURL)
		}
	}
	time.Sleep(60 * time.Second)
	if !a.uiSeen() {
		log.Printf("окно не открылось — выход")
		a.shutdown()
	}
}

func (a *app) uiSeen() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.uiEver
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
		boot := fmt.Sprintf(`<script>window.NDSK_TOKEN=%q;window.NDSK_MARKER=%q;</script>`, a.token, sessionTitleMarker)
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
	w.Header().Set("Cache-Control", "no-store")
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
	n, handoff := a.uiOpen, a.handoff != ""
	a.mu.Unlock()
	if cur && !handoff {
		a.teardownHost()
	}
	if n > 0 || handoff {
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
		Cmd   string   `json:"cmd"`
		ID    string   `json:"id"`
		Code  string   `json:"code"`
		Text  string   `json:"text"`
		Name  string   `json:"name"`
		Size  int64    `json:"size"`
		Batch string   `json:"batch"`
		Rel   string   `json:"rel"`
		Dir   bool     `json:"dir"`
		Items []string `json:"items"`
		To    string   `json:"to"`
		On    bool     `json:"on"`
		Q     int      `json:"q"`
		FPS   int      `json:"fps"`
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
	case "syskeys":
		a.mu.Lock()
		a.syskeys = m.On
		a.mu.Unlock()
		a.updateHook()
	case "ctrlv":
		// Ctrl+V из окна (когда системные клавиши не перехватываются).
		if !a.ctrlV() {
			a.sendKey('V', true, false)
			a.sendKey('V', false, false)
		}
	case "action":
		a.toHost(wire.TypeAction, []byte(m.Name))
	case "combo":
		a.toHost(wire.TypeCombo, []byte(m.Name))
	case "quality":
		a.toHost(wire.TypeQuality, []byte{byte(m.Q), byte(m.FPS)})
	case "filestart":
		meta, _ := json.Marshal(map[string]any{
			"name": m.Name, "size": m.Size, "batch": m.Batch, "rel": m.Rel, "dir": m.Dir,
		})
		a.toHost(wire.TypeFileStart, meta)
	case "fileend":
		a.toHost(wire.TypeFileEnd, nil)
	case "pastedone":
		b, _ := json.Marshal(map[string]any{"batch": m.Batch, "items": m.Items, "to": m.To})
		a.toHost(wire.TypePasteDone, b)
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

// updateHook включает перехват системных клавиш, пока идёт сеанс.
func (a *app) updateHook() {
	a.mu.Lock()
	on := a.inSess && a.syskeys
	sess := a.inSess
	a.mu.Unlock()
	if sess {
		a.hook.setActive(on)
	} else {
		a.hook.stop()
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

// connect: с кодом — сразу; без кода — та сторона спросит «Разрешить?».
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
	host, relay, err := a.dialHost(id, code)
	if err != nil {
		a.status(err.Error())
		return
	}
	a.mu.Lock()
	a.relay, a.host, a.hostID = relay, host, id
	a.mu.Unlock()

	if code == "" {
		a.status(fmt.Sprintf("Ждём, пока на той стороне нажмут «Да». Проверочный номер: %s — он должен совпадать с номером в окне на том компьютере.", sas2(host.SAS())))
	} else {
		a.status("Подключено, загружаю экран...")
	}
	a.hostReadLoop(host)
}

// dialHost: релей → режим → рукопожатие. Ошибки — уже человеческим текстом.
func (a *app) dialHost(id, code string) (*secure.Conn, net.Conn, error) {
	relay, err := relaylink.DialJoin(a.relayAddr, id, 15*time.Second)
	if err != nil {
		switch err {
		case relaylink.ErrNoHost:
			return nil, nil, errors.New("Компьютер с таким ID не в сети (NordDesk на нём закрыт?).")
		case relaylink.ErrBusy:
			return nil, nil, errors.New("К этому компьютеру уже подключены.")
		}
		log.Printf("релей: %v", err)
		return nil, nil, errors.New("Нет связи с сервером NordDesk.")
	}
	mode := relaylink.ModePassword
	if code == "" {
		mode = relaylink.ModeRequest
	}
	relay.SetDeadline(time.Now().Add(handshakeTimeout))
	err = relaylink.SendMode(relay, mode)
	var host *secure.Conn
	if err == nil {
		host, err = secure.Handshake(relay, secure.RoleViewer, id, code)
	}
	relay.SetDeadline(time.Time{})
	if err != nil {
		relay.Close()
		if err == secure.ErrConfirm {
			return nil, nil, errors.New("Неверный код подключения.")
		}
		log.Printf("рукопожатие: %v", err)
		return nil, nil, errors.New("Не удалось установить защищённое соединение.")
	}
	return host, relay, nil
}

func (a *app) connected() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.host != nil
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
			if a.tryResume(host) {
				return
			}
			a.endSession("Соединение закрыто.")
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
			a.mu.Lock()
			a.inSess = true
			a.mu.Unlock()
			a.updateHook()
			a.sendUIText(map[string]any{"ev": "screeninfo", "w": si.W, "h": si.H})
		case wire.TypeTile:
			a.sendUIBinary(2, m.Data) // окно знает: 2 = тайл
		case wire.TypeClipboard:
			// Скопировали текст там → кладём в свой буфер обмена.
			if err := wininput.SetClipboardText(string(m.Data)); err != nil {
				a.sendUIText(map[string]any{"ev": "clip", "text": string(m.Data)})
			}
		case wire.TypeNotice:
			a.sendUIText(map[string]any{"ev": "notice", "text": string(m.Data)})
		case wire.TypeHostInfo:
			var hi map[string]any
			if json.Unmarshal(m.Data, &hi) == nil {
				hi["ev"] = "hostinfo"
				a.sendUIText(hi)
			}
		case wire.TypeElevated:
			a.mu.Lock()
			a.resume = string(m.Data)
			a.mu.Unlock()
			a.sendUIText(map[string]any{"ev": "notice", "text": "Та сторона перезапускается с правами администратора — переподключаюсь..."})
		case wire.TypeSessionEnd:
			a.endSession(string(m.Data))
			return
		}
	}
}

// tryResume: та сторона перезапустилась с правами администратора — подключаемся
// к новой копии по коду переподключения, не выходя из сеанса.
func (a *app) tryResume(old *secure.Conn) bool {
	a.mu.Lock()
	code, id := a.resume, a.hostID
	stale := a.host != old
	a.resume = ""
	a.mu.Unlock()
	if code == "" || stale {
		return false
	}
	a.closeRelay()
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		time.Sleep(1500 * time.Millisecond)
		host, relay, err := a.dialHost(id, code)
		if err != nil {
			continue
		}
		a.mu.Lock()
		a.relay, a.host = relay, host
		a.mu.Unlock()
		a.sendUIText(map[string]any{"ev": "notice", "text": "Переподключено: на той стороне NordDesk работает с правами администратора."})
		go a.hostReadLoop(host)
		return true
	}
	a.endSession("Не удалось переподключиться после перезапуска с правами администратора.")
	return true
}

func (a *app) endSession(text string) {
	a.teardownHost()
	a.sendUIText(map[string]any{"ev": "ended", "text": text})
}

func (a *app) closeRelay() {
	a.mu.Lock()
	if a.relay != nil {
		a.relay.Close()
	}
	a.relay, a.host = nil, nil
	a.mu.Unlock()
}

func (a *app) teardownHost() {
	a.mu.Lock()
	if a.relay != nil {
		a.relay.Close()
	}
	a.relay, a.host, a.hostID, a.resume = nil, nil, "", ""
	a.inSess = false
	a.mu.Unlock()
	a.hook.stop()
}

// --- перезапуск с правами администратора (входящая сторона) ---

type resumeState struct {
	Code    string    `json:"code"`
	UIToken string    `json:"ui_token"`
	Until   time.Time `json:"until"`
}

// elevate запускает копию NordDesk с правами администратора (на этом
// компьютере появится UAC) и возвращает код, по которому клиент переподключится.
func (a *app) elevate() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	rs := resumeState{Code: randomHex(16), UIToken: randomHex(16), Until: time.Now().Add(3 * time.Minute)}
	b, _ := json.Marshal(rs)
	p := filepath.Join(a.dir, "resume.json")
	if err := os.WriteFile(p, b, 0o600); err != nil {
		return "", err
	}
	// Папку настроек передаём явно: если права даёт другой (админский)
	// пользователь, ID и код переподключения должны остаться прежними.
	args := fmt.Sprintf(`--resume --config "%s" --relay "%s"`, a.dir, a.relayAddr)
	if err := winsys.ShellRun("runas", exe, args, ""); err != nil {
		os.Remove(p)
		return "", err
	}
	a.mu.Lock()
	a.handoff = rs.UIToken
	a.mu.Unlock()
	return rs.Code, nil
}

// onElevated: код отправлен клиенту — переводим своё окно на новую копию и выходим.
func (a *app) onElevated() {
	a.mu.Lock()
	tok := a.handoff
	a.mu.Unlock()
	a.sendUIText(map[string]any{"ev": "handoff", "token": tok})
	go func() {
		time.Sleep(800 * time.Millisecond)
		log.Printf("перезапуск с правами администратора — эта копия завершается")
		a.shutdown()
	}()
}

// takeResume читает и сразу удаляет файл переподключения.
func takeResume(dir string) *resumeState {
	p := filepath.Join(dir, "resume.json")
	b, err := os.ReadFile(p)
	os.Remove(p)
	if err != nil {
		return nil
	}
	var rs resumeState
	if json.Unmarshal(b, &rs) != nil || rs.Code == "" || rs.UIToken == "" || time.Now().After(rs.Until) {
		return nil
	}
	return &rs
}

// waitListen ждёт, пока порт освободится (прежняя копия завершается).
func waitListen(port int, timeout time.Duration) (net.Listener, error) {
	deadline := time.Now().Add(timeout)
	for {
		ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
		if err == nil || time.Now().After(deadline) {
			return ln, err
		}
		time.Sleep(200 * time.Millisecond)
	}
}

// --- настройки, лог, одна копия ---

func defaultConfigDir() string {
	d, err := os.UserConfigDir()
	if err != nil || d == "" {
		return "."
	}
	return filepath.Join(d, "NordDesk")
}

// loadOrCreateID: постоянный ID компьютера — создаётся один раз.
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
			launchUI(u)
			return nil, nil
		}
	}
	return net.Listen("tcp", "127.0.0.1:0") // порт занят посторонним — берём случайный
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

// sas2 оформляет проверочный номер: 4821 → «48 21».
func sas2(s string) string {
	if len(s) == 4 {
		return s[:2] + " " + s[2:]
	}
	return s
}
