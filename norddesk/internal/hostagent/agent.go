// Package hostagent — «входящая» часть NordDesk: держит компьютер в сети под
// постоянным ID, показывает одноразовый код, проверяет его в сквозном
// рукопожатии, спрашивает согласие человека за ПК и ведёт сеанс.
//
// Защита от подбора кода: после 3 неверных попыток код меняется, а после
// каждой неудачи — пауза. Одноразовый код меняется и после каждого сеанса
// или отказа, поэтому продиктованный однажды код повторно не сработает.
package hostagent

import (
	"errors"
	"net"
	"sync"
	"time"

	"norddesk/internal/relaylink"
	"norddesk/internal/secure"
	"norddesk/internal/winsys"
	"norddesk/internal/wire"
)

// State — состояние входящей части.
type State string

const (
	StateConnecting State = "connecting" // регистрация на релее
	StateOffline    State = "offline"    // нет связи с релеем, повторяем
	StateReady      State = "ready"      // в сети, ждём подключения
	StateIncoming   State = "incoming"   // код верный, ждём согласия
	StateActive     State = "active"     // идёт сеанс
)

// Status — то, что показывается пользователю.
type Status struct {
	ID    string `json:"id"`
	Code  string `json:"code"` // пусто, если задан постоянный пароль
	State State  `json:"state"`
	Note  string `json:"note,omitempty"`
	Admin bool   `json:"admin"` // процесс работает с правами администратора
}

// Config — настройки агента.
type Config struct {
	Relay    string
	ID       string
	Password string // постоянный пароль (свои машины); пусто — одноразовый код
	// Confirm спрашивает человека за ПК, когда подключаются без кода (только
	// по ID); sas — проверочный номер для сверки. nil — такие запросы отклоняются.
	// С верным кодом доступ даётся сразу, без вопроса.
	Confirm  func(sas string) bool
	FilesDir string // куда класть принятые файлы; пусто — рабочий стол
	OnStatus func(Status)
	Logf     func(format string, a ...any)

	// Переподключение после перезапуска с правами администратора: первое
	// подключение до ResumeUntil принимается по ResumeCode без повторного
	// «Да» (согласие уже дано, а повышение подтверждено через UAC).
	ResumeCode  string
	ResumeUntil time.Time

	// Elevate перезапускает приложение с правами администратора и возвращает
	// код для переподключения; nil — не поддерживается.
	Elevate func() (code string, err error)
	// OnElevated вызывается, когда код переподключения отправлен клиенту.
	OnElevated func()
}

const (
	maxFails         = 3
	handshakeTimeout = 20 * time.Second
)

// Agent — входящая часть одного компьютера.
type Agent struct {
	cfg Config

	mu          sync.Mutex
	st          Status
	code        string
	fails       int
	conn        net.Conn // текущее соединение с релеем
	resume      string
	resumeUntil time.Time
}

// New создаёт агента. Сеть не трогает до вызова Run.
func New(cfg Config) *Agent {
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...any) {}
	}
	if cfg.FilesDir == "" {
		cfg.FilesDir = DesktopDir()
	}
	a := &Agent{
		cfg:         cfg,
		st:          Status{ID: cfg.ID, State: StateConnecting, Admin: winsys.IsElevated()},
		resume:      cfg.ResumeCode,
		resumeUntil: cfg.ResumeUntil,
	}
	a.newCodeLocked()
	return a
}

// pickCode — каким кодом проверять очередное подключение: кодом
// переподключения (если он ещё действует) или обычным.
func (a *Agent) pickCode() (code string, resumed bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.resume != "" && time.Now().Before(a.resumeUntil) {
		return a.resume, true
	}
	a.resume = ""
	return a.code, false
}

func (a *Agent) dropResume() {
	a.mu.Lock()
	a.resume = ""
	a.mu.Unlock()
}

// Status возвращает текущее состояние.
func (a *Agent) Status() Status {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.st
}

// RefreshCode выдаёт новый одноразовый код; старый перестаёт подходить.
func (a *Agent) RefreshCode() {
	a.mu.Lock()
	a.newCodeLocked()
	st := a.st
	a.mu.Unlock()
	a.emit(st)
}

// EndSession разрывает текущее входящее подключение (идущее или ожидающее согласия).
func (a *Agent) EndSession() {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.conn != nil && (a.st.State == StateActive || a.st.State == StateIncoming) {
		a.conn.Close()
	}
}

// Run держит компьютер в сети, пока не закрыт stop.
func (a *Agent) Run(stop <-chan struct{}) {
	for {
		select {
		case <-stop:
			return
		default:
		}
		wait := a.cycle(stop)
		if wait > 0 {
			select {
			case <-stop:
				return
			case <-time.After(wait):
			}
		}
	}
}

// cycle — одна регистрация на релее и (если пришёл клиент) один сеанс.
// Возвращает паузу перед следующим циклом.
func (a *Agent) cycle(stop <-chan struct{}) time.Duration {
	a.set(StateConnecting, "")
	conn, err := relaylink.DialHost(a.cfg.Relay, a.cfg.ID, 15*time.Second)
	if err != nil {
		note := "нет связи с сервером"
		if errors.Is(err, relaylink.ErrTaken) {
			note = "этот ID уже в сети — приложение открыто на другом устройстве?"
		}
		a.cfg.Logf("релей: %v", err)
		a.set(StateOffline, note)
		return 5 * time.Second
	}
	a.setConn(conn)
	defer func() {
		a.setConn(nil)
		conn.Close()
	}()

	// При остановке приложения закрываем соединение, чтобы разблокировать чтение.
	cycleDone := make(chan struct{})
	defer close(cycleDone)
	go func() {
		select {
		case <-stop:
			conn.Close()
		case <-cycleDone:
		}
	}()

	a.set(StateReady, "")
	if err := relaylink.WaitPeer(conn, time.Time{}); err != nil {
		// Релей закрыл регистрацию по тайм-ауту или обрыв связи — заново.
		return time.Second
	}

	// Режим: с кодом — доступ сразу; без кода — спросить человека за ПК.
	mode, err := relaylink.ReadMode(conn, time.Now().Add(handshakeTimeout))
	if err != nil {
		a.cfg.Logf("режим подключения: %v", err)
		return time.Second
	}
	code, resumed := "", false
	if mode == relaylink.ModePassword {
		code, resumed = a.pickCode()
	}

	// Рукопожатие с ограничением по времени: иначе «молчащий» клиент мог бы
	// навсегда занять наш ID.
	conn.SetDeadline(time.Now().Add(handshakeTimeout))
	sc, err := secure.Handshake(conn, secure.RoleHost, a.cfg.ID, code)
	conn.SetDeadline(time.Time{})
	if err != nil {
		a.cfg.Logf("рукопожатие: %v", err)
		if errors.Is(err, secure.ErrConfirm) && mode == relaylink.ModePassword && !resumed {
			return a.noteFail()
		}
		return 2 * time.Second
	}
	if resumed {
		a.dropResume() // код переподключения одноразовый
		a.cfg.Logf("переподключение после повышения прав")
	}

	if mode == relaylink.ModeRequest {
		reject := func(why string) time.Duration {
			conn.SetDeadline(time.Now().Add(5 * time.Second))
			sc.WriteFrame(wire.Encode(wire.TypeSessionEnd, []byte(why)))
			return 3 * time.Second // пауза, чтобы запросами нельзя было «заспамить»
		}
		if a.cfg.Confirm == nil {
			return reject("Этот компьютер принимает подключения только по коду.")
		}
		a.set(StateIncoming, "проверочный номер "+sas2(sc.SAS()))
		if !a.cfg.Confirm(sc.SAS()) {
			return reject("Подключение отклонено на той стороне.")
		}
		a.cfg.Logf("подключение без кода разрешено (проверочный номер %s)", sc.SAS())
	} else {
		a.cfg.Logf("подключение по коду")
	}

	a.set(StateActive, "")
	env := sessionEnv{
		filesDir:   a.cfg.FilesDir,
		logf:       a.cfg.Logf,
		elevate:    a.cfg.Elevate,
		onElevated: a.cfg.OnElevated,
	}
	if err := runSession(sc, env); err != nil {
		a.cfg.Logf("сеанс: %v", err)
	}
	a.rotateOneTime()
	return 0
}

// noteFail учитывает неверный код: после maxFails код меняется. Пауза растёт
// с каждой неудачей, чтобы подбор был бессмысленно медленным.
func (a *Agent) noteFail() time.Duration {
	a.mu.Lock()
	a.fails++
	fails := a.fails
	rotated := false
	if fails >= maxFails && a.cfg.Password == "" {
		a.newCodeLocked()
		rotated = true
	}
	st := a.st
	a.mu.Unlock()
	if rotated {
		a.cfg.Logf("%d неверных кода подряд — код заменён", fails)
		a.emit(st)
	}
	return time.Duration(fails) * 2 * time.Second
}

func (a *Agent) rotateOneTime() {
	if a.cfg.Password != "" {
		a.mu.Lock()
		a.fails = 0
		a.mu.Unlock()
		return
	}
	a.RefreshCode()
}

func (a *Agent) newCodeLocked() {
	a.fails = 0
	if a.cfg.Password != "" {
		a.code = a.cfg.Password
		a.st.Code = ""
		return
	}
	a.code = relaylink.NewCode(6)
	a.st.Code = a.code
}

// ConfirmText — текст окна «Разрешить?» для подключения без кода.
func ConfirmText(sas string) string {
	return "К этому компьютеру хотят подключиться без кода.\n\n" +
		"Проверочный номер: " + sas2(sas) + "\n" +
		"Он должен совпадать с номером на экране того, кто подключается.\n\n" +
		"Разрешить управление этим компьютером?"
}

// sas2 оформляет проверочный номер: 4821 → «48 21».
func sas2(s string) string {
	if len(s) == 4 {
		return s[:2] + " " + s[2:]
	}
	return s
}

func (a *Agent) setConn(c net.Conn) {
	a.mu.Lock()
	a.conn = c
	a.mu.Unlock()
}

func (a *Agent) set(state State, note string) {
	a.mu.Lock()
	changed := a.st.State != state || a.st.Note != note
	a.st.State, a.st.Note = state, note
	st := a.st
	a.mu.Unlock()
	if changed {
		a.emit(st)
	}
}

func (a *Agent) emit(st Status) {
	if a.cfg.OnStatus != nil {
		a.cfg.OnStatus(st)
	}
}
