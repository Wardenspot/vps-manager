// Package app — служба NordKey на ПК: держит VPN, помнит вход и отдаёт окну приложения локальный API.
//
// Окно (HTML в Edge) ходит только сюда, на 127.0.0.1. Токен входа и ключи VPN окну не отдаются:
// запросы к серверу NordKey идут через /api/… и служба сама подставляет токен.
package app

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"nordkey/win/internal/api"
	"nordkey/win/internal/engine"
	"nordkey/win/internal/rules"
	"nordkey/win/internal/store"
)

const Addr = "127.0.0.1:47811"

type Options struct {
	Addr    string // 127.0.0.1:47811
	API     *api.Client
	Store   *store.Store
	Engine  engine.Engine
	UI      fs.FS
	Version string
	Device  string // как ПК называется в «Где выполнен вход»
	KeyFile func(key string) error
	Logf    func(string, ...any)
}

type login struct {
	Code, Secret string
	Pick         int
	BotURL       string
	Until        time.Time
}

type App struct {
	o       Options
	op      sync.Mutex // по очереди: подключение, режим, списки, выход
	mu      sync.Mutex // поля ниже (коротко, чтобы окно не ждало подключения)
	short   *api.Client
	busy    bool
	rules   *rules.Rules
	login   *login
	pc      json.RawMessage                             // последний /v1/pc: подписка, сотрудник
	last    struct{ connectTry, rulesTry, pcTry stamp } // когда последний раз пробовали (из разных потоков)
	lastErr string
	pcNow   atomic.Bool // спросить /v1/pc при ближайшей проверке (после входа)
	logs    *ring
	stop    chan struct{}
}

func New(o Options) *App {
	if o.Addr == "" {
		o.Addr = Addr
	}
	a := &App{o: o, logs: newRing(400), stop: make(chan struct{}), short: o.API.WithTimeout(25 * time.Second)}
	inner := o.Logf
	a.o.Logf = func(f string, args ...any) {
		a.logs.add(time.Now().Format("02.01 15:04:05 ") + fmt.Sprintf(f, args...))
		if inner != nil {
			inner(f, args...)
		}
	}
	return a
}

func (a *App) logf(f string, args ...any) { a.o.Logf(f, args...) }

func (a *App) setErr(err error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.lastErr = ""
	if err != nil {
		a.lastErr = err.Error()
	}
}

// Start — подготовка: ключ окна, восстановление VPN после перезагрузки.
func (a *App) Start() {
	st := a.o.Store.Load()
	if st.UIKey == "" {
		b := make([]byte, 24)
		_, _ = rand.Read(b)
		st, _ = a.o.Store.Update(func(s *store.State) { s.UIKey = hex.EncodeToString(b) })
	}
	if a.o.KeyFile != nil {
		if err := a.o.KeyFile(st.UIKey); err != nil {
			a.logf("ключ окна: %v", err)
		}
	}
	if len(st.Rules) > 0 {
		if r, err := rules.Parse(st.Rules); err == nil {
			a.rules = r
		}
	}
	go func() {
		a.op.Lock()
		a.o.Engine.Cleanup() // следы прошлого запуска (если служба упала) — до первого подключения
		a.op.Unlock()
		a.loop()
	}()
}

func (a *App) Stop() {
	close(a.stop)
	a.mu.Lock()
	defer a.mu.Unlock()
	_ = a.o.Engine.Down()
}

// mode — у клиентов всегда «как на роутере» (игры и всё остальное напрямую, через VPN — только нужное);
// «всё, кроме российского» и «всё через VPN» — только сотрудникам.
func (a *App) mode(st store.State) string {
	if st.Staff && rules.ValidMode(st.Mode) {
		return st.Mode
	}
	return rules.ModeSmart
}

// ───────── VPN ─────────

// fetchConfig — конфиг ПК с сервера (заодно сервер проверит, что ПК добавлен у него). Нет сети — берём сохранённый.
func (a *App) fetchConfig(st *store.State) error {
	if st.Token == "" {
		if st.Config != nil {
			return nil
		}
		return errors.New("войдите в аккаунт NordKey")
	}
	var r struct {
		Config engine.Config `json:"config"`
	}
	err := a.short.Call("POST", "/v1/pc/connect", st.Token, map[string]string{"device": a.o.Device}, &r)
	if err != nil {
		var ae *api.Error
		if errors.As(err, &ae) || st.Config == nil { // сервер отказал (нет подписки) — без вариантов
			return err
		}
		a.logf("сервер недоступен (%v) — подключаюсь с сохранённым конфигом", err)
		return nil
	}
	st.Config = &r.Config
	return nil
}

func (a *App) fetchRules(st *store.State, force bool) (bool, error) {
	if st.Token == "" {
		if a.rules != nil {
			return false, nil
		}
		return false, errors.New("нет списков сайтов — войдите в аккаунт")
	}
	path := "/v1/pc/routes"
	if a.rules != nil && !force {
		path += "?v=" + a.rules.Version()
	}
	code, b, _, err := a.short.Raw("GET", path, st.Token, nil)
	if err != nil || code != 200 {
		if a.rules != nil {
			return false, nil // старые списки лучше, чем ничего
		}
		if err == nil {
			err = fmt.Errorf("сервер не отдал списки сайтов (%d)", code)
		}
		return false, err
	}
	var same struct {
		Same bool `json:"same"`
	}
	if json.Unmarshal(b, &same) == nil && same.Same {
		return false, nil
	}
	r, err := rules.Parse(b)
	if err != nil {
		return false, err
	}
	a.mu.Lock()
	a.rules = r
	a.mu.Unlock()
	st.Rules = b
	v, d, n := r.Counts()
	a.logf("списки сайтов %s: через VPN %d, напрямую %d, сетей %d", r.Version(), v, d, n)
	return true, nil
}

// Connect — включить VPN.
func (a *App) Connect() error {
	a.op.Lock()
	defer a.op.Unlock()
	return a.connectLocked()
}

func (a *App) connectLocked() error {
	st := a.o.Store.Load()
	a.last.connectTry.set()
	a.mu.Lock()
	a.busy = true
	a.mu.Unlock()
	defer func() { a.mu.Lock(); a.busy = false; a.mu.Unlock() }()
	err := a.fetchConfig(&st)
	if err == nil {
		_, err = a.fetchRules(&st, false)
	}
	if err == nil {
		err = a.o.Engine.Up(*st.Config, a.rules, a.mode(st))
	}
	if err != nil {
		a.setErr(err)
		a.logf("не подключилось: %v", err)
		return err
	}
	a.setErr(nil)
	st.WantUp = true
	_ = a.o.Store.Save(st)
	a.logf("VPN включён (%s, режим %s)", st.Config.IP, a.mode(st))
	return nil
}

func (a *App) Disconnect() error {
	a.op.Lock()
	defer a.op.Unlock()
	_, _ = a.o.Store.Update(func(s *store.State) { s.WantUp = false })
	a.setErr(nil)
	a.logf("VPN выключен")
	return a.o.Engine.Down()
}

func (a *App) SetMode(m string) error {
	if !rules.ValidMode(m) {
		return errors.New("неизвестный режим")
	}
	if m != rules.ModeSmart && !a.o.Store.Load().Staff {
		a.fetchPC() // роль могли выдать только что
	}
	if m != rules.ModeSmart && !a.o.Store.Load().Staff {
		return errors.New("этот режим — только для сотрудников; нужный сайт или игру пустит через VPN поддержка")
	}
	a.op.Lock()
	defer a.op.Unlock()
	st, err := a.o.Store.Update(func(s *store.State) { s.Mode = m })
	if err != nil {
		return err
	}
	a.logf("режим: %s", m)
	if a.rules != nil {
		return a.o.Engine.SetRules(a.rules, a.mode(st))
	}
	return nil
}

// RefreshRules — новые списки сайтов (после своего сайта — сразу).
func (a *App) RefreshRules(force bool) error {
	a.op.Lock()
	defer a.op.Unlock()
	st := a.o.Store.Load()
	a.last.rulesTry.set()
	changed, err := a.fetchRules(&st, force)
	if err != nil || !changed {
		return err
	}
	_ = a.o.Store.Save(st)
	return a.o.Engine.SetRules(a.rules, a.mode(st))
}

// loop — фоновые дела: включить VPN после перезагрузки, обновлять списки, переподключать, если сервер сменил ключ.
func (a *App) loop() {
	t := time.NewTicker(15 * time.Second)
	defer t.Stop()
	for {
		a.tick()
		select {
		case <-a.stop:
			return
		case <-t.C:
		}
	}
}

func (a *App) tick() {
	st := a.o.Store.Load()
	s := a.o.Engine.Status()
	switch {
	case st.WantUp && s.State != "on" && s.State != "connecting" && a.last.connectTry.since() > 30*time.Second:
		// после перезагрузки сеть появляется не сразу — пробуем раз в 30 секунд
		_ = a.Connect()
	case s.State == "on" && a.last.rulesTry.since() > 2*time.Minute:
		// сайты, которые пустила поддержка, доходят за пару минут (запрос с версией — ответ крошечный, если ничего не менялось)
		if err := a.RefreshRules(false); err != nil {
			a.logf("обновление списков: %v", err)
		}
	case s.State == "on" && st.Token != "" && time.Since(time.Unix(s.Since, 0)) > 3*time.Minute &&
		(s.Handshake == 0 || s.Handshake > 180) && a.last.connectTry.since() > 5*time.Minute:
		// сервер давно не отвечает: возможно, подписку продлили и выдан новый ключ — спросим сервер
		a.op.Lock()
		a.last.connectTry.set()
		old := st.Config
		err := a.fetchConfig(&st)
		if err == nil && old != nil && st.Config != nil && (st.Config.PrivateKey != old.PrivateKey || st.Config.IP != old.IP) {
			a.logf("сервер выдал новый ключ ПК — переподключаюсь")
			_ = a.o.Store.Save(st)
			_ = a.connectLocked()
		} else if err != nil {
			a.setErr(err)
		}
		a.op.Unlock()
	}
	pcDue := a.last.pcTry.since() > 30*time.Minute
	if st.Token != "" && (a.pcNow.Swap(false) || pcDue) {
		a.fetchPC()
	}
}

// fetchPC — подписка и роль (сотрудник ли) для этого ПК.
func (a *App) fetchPC() {
	a.last.pcTry.set()
	st := a.o.Store.Load()
	var raw json.RawMessage
	if a.short.Call("GET", "/v1/pc", st.Token, nil, &raw) != nil {
		return
	}
	var pc struct {
		Staff bool `json:"staff"`
	}
	_ = json.Unmarshal(raw, &pc)
	a.mu.Lock()
	a.pc = raw
	a.mu.Unlock()
	if pc.Staff != st.Staff {
		a.op.Lock()
		st, _ = a.o.Store.Update(func(s *store.State) { s.Staff = pc.Staff })
		if a.rules != nil && a.o.Engine.Status().State == "on" {
			_ = a.o.Engine.SetRules(a.rules, a.mode(st)) // сняли роль сотрудника — режим вернётся к «как на роутере»
		}
		a.op.Unlock()
	}
}

// ───────── вход ─────────

func (a *App) LoginStart() (map[string]any, error) {
	var r struct {
		Code      string `json:"code"`
		Secret    string `json:"secret"`
		Pick      int    `json:"pick"`
		BotURL    string `json:"bot_url"`
		ExpiresIn int    `json:"expires_in"`
	}
	if err := a.short.Call("POST", "/v1/auth/start", "", map[string]string{"device": a.o.Device}, &r); err != nil {
		return nil, err
	}
	if r.ExpiresIn <= 0 {
		r.ExpiresIn = 300
	}
	a.mu.Lock()
	a.login = &login{Code: r.Code, Secret: r.Secret, Pick: r.Pick, BotURL: r.BotURL, Until: time.Now().Add(time.Duration(r.ExpiresIn) * time.Second)}
	a.mu.Unlock()
	return map[string]any{"pick": r.Pick, "bot_url": r.BotURL, "tg_url": tgURL(r.BotURL), "expires_in": r.ExpiresIn}, nil
}

// tgURL — https://t.me/bot?start=x → tg://resolve?domain=bot&start=x (сразу открывает Telegram Desktop).
func tgURL(u string) string {
	rest, ok := strings.CutPrefix(u, "https://t.me/")
	if !ok {
		return ""
	}
	bot, q, _ := strings.Cut(rest, "?")
	if bot == "" || strings.ContainsAny(bot, "/&#") {
		return ""
	}
	out := "tg://resolve?domain=" + bot
	if q != "" {
		out += "&" + q
	}
	return out
}

func (a *App) LoginPoll() (string, error) {
	a.mu.Lock()
	l := a.login
	a.mu.Unlock()
	if l == nil {
		return "expired", nil
	}
	if time.Now().After(l.Until) {
		return "expired", nil
	}
	var r struct {
		Status string `json:"status"`
		Token  string `json:"token"`
	}
	if err := a.short.Call("POST", "/v1/auth/poll", "", map[string]string{"code": l.Code, "secret": l.Secret}, &r); err != nil {
		return "waiting", nil // сеть мигнула — спросим ещё
	}
	switch r.Status {
	case "ok":
		if r.Token == "" {
			return "waiting", nil
		}
		a.mu.Lock()
		a.login = nil
		a.pc = nil
		a.pcNow.Store(true)
		a.mu.Unlock()
		_, err := a.o.Store.Update(func(s *store.State) { s.Token = r.Token })
		a.logf("вход выполнен")

		return "ok", err
	case "denied", "expired", "unknown":
		a.mu.Lock()
		a.login = nil
		a.mu.Unlock()
		if r.Status == "unknown" {
			return "expired", nil
		}
		return r.Status, nil
	}
	return "waiting", nil
}

// Logout — выйти: VPN выключаем, вход и конфиг стираем (на этом ПК может войти другой человек).
func (a *App) Logout() {
	st := a.o.Store.Load()
	if st.Token != "" {
		_ = a.short.Call("POST", "/v1/auth/logout", st.Token, nil, nil)
	}
	a.op.Lock()
	defer a.op.Unlock()
	_ = a.o.Engine.Down()
	a.mu.Lock()
	a.rules, a.pc, a.lastErr = nil, nil, ""
	a.mu.Unlock()
	_, _ = a.o.Store.Update(func(s *store.State) {
		*s = store.State{UIKey: s.UIKey, Mode: s.Mode}
	})
	a.logf("выход из аккаунта")
}

// Forget — отключить этот ПК на сервере (ключ перестанет работать).
func (a *App) Forget() error {
	st := a.o.Store.Load()
	if st.Token == "" {
		return errors.New("войдите в аккаунт")
	}
	if err := a.short.Call("POST", "/v1/pc/forget", st.Token, nil, nil); err != nil {
		return err
	}
	a.op.Lock()
	defer a.op.Unlock()
	_ = a.o.Engine.Down()
	_, _ = a.o.Store.Update(func(s *store.State) { s.Config, s.WantUp = nil, false })
	a.logf("ПК отключён от VPN на сервере")
	return nil
}

// ───────── состояние для окна ─────────

func (a *App) State() map[string]any {
	st := a.o.Store.Load()
	s := a.o.Engine.Status()
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.busy && s.State != "on" {
		s.State = "connecting"
	}
	out := map[string]any{
		"version":    a.o.Version,
		"device":     a.o.Device,
		"logged_in":  st.Token != "",
		"vpn":        s,
		"mode":       a.mode(st),
		"want_up":    st.WantUp,
		"has_config": st.Config != nil,
		"error":      a.lastErr,
	}
	if st.Config != nil {
		out["ip"] = st.Config.IP
	}
	if a.rules != nil {
		v, d, n := a.rules.Counts()
		out["rules"] = map[string]any{"version": a.rules.Version(), "vpn": v, "direct": d, "nets": n,
			"custom_vpn": a.rules.Data().CustomVPN, "custom_direct": a.rules.Data().CustomDirect}
	}
	if a.pc != nil {
		out["pc"] = a.pc
	}
	if a.login != nil {
		out["login_pending"] = true
	}
	return out
}

type stamp struct{ ns atomic.Int64 }

func (t *stamp) set()                 { t.ns.Store(time.Now().UnixNano()) }
func (t *stamp) since() time.Duration { return time.Since(time.Unix(0, t.ns.Load())) }

// ───────── журнал ─────────

type ring struct {
	mu    sync.Mutex
	lines []string
	max   int
}

func newRing(n int) *ring { return &ring{max: n} }
func (r *ring) add(s string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lines = append(r.lines, s)
	if len(r.lines) > r.max {
		r.lines = r.lines[len(r.lines)-r.max:]
	}
}
func (r *ring) all() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.lines...)
}

func readJSON(r *http.Request, v any) error {
	b, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		return err
	}
	if len(b) == 0 {
		return nil
	}
	return json.Unmarshal(b, v)
}
