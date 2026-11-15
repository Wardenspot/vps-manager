package keenetic

import (
	"crypto/rand"
	"errors"
	"fmt"
	"math/big"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	SrvKey = "jwn8oUjJ2IUJv9a0i/JVGXPgYZMIjvlYLjiHenuzh3w="
	ASC    = "4 40 70 107 53 1068678846 1558046116 2145091299 1103781949"
)

var Known = []string{"143.246.193.43", "143.246.193.103"}

// Шаги мастера — как их видит пользователь (те же, что в приложении для Android).
var Steps = []struct {
	ID    string `json:"id"`
	Title string `json:"title"`
}{
	{"login", "Вход в роутер"},
	{"ssh", "Сервер SSH на роутере"},
	{"tunnel", "Туннель NordKey"},
	{"server", "Регистрация на сервере"},
	{"access", "Доступ для сервера"},
	{"finish", "Настройка сайтов и проверка"},
}

// Server — заявки на сервер NordKey (/v1/staff/onboard) от имени сотрудника.
type Server interface {
	Post(path string, in map[string]any) (map[string]any, error)
	Get(path string) (map[string]any, error)
}

type StepState struct {
	Status string `json:"status"` // wait | run | done | fail
	Detail string `json:"detail,omitempty"`
}

type Result struct {
	IP        string `json:"ip"`
	Link      string `json:"link,omitempty"`
	NewClient bool   `json:"new_client"`
	Note      string `json:"note,omitempty"`
}

// Progress — состояние мастера для окна.
type Progress struct {
	Phase      string               `json:"phase"` // running | done | failed
	Steps      map[string]StepState `json:"steps"`
	ServerLine string               `json:"server_line,omitempty"`
	Result     *Result              `json:"result,omitempty"`
	Error      string               `json:"error,omitempty"`
}

// Setup — один запуск мастера.
type Setup struct {
	K      *Client
	S      Server
	Name   string        // имя нового клиента
	Poll   time.Duration // как часто спрашивать сервер (тесты ставят меньше)
	Server string        // адрес сервера для туннеля

	mu     sync.Mutex
	p      Progress
	ngPass string
}

func (s *Setup) Snapshot() Progress {
	s.mu.Lock()
	defer s.mu.Unlock()
	cp := s.p
	cp.Steps = map[string]StepState{}
	for k, v := range s.p.Steps {
		cp.Steps[k] = v
	}
	return cp
}

func (s *Setup) step(id, st, detail string) {
	s.mu.Lock()
	if s.p.Steps == nil {
		s.p.Steps = map[string]StepState{}
	}
	s.p.Steps[id] = StepState{st, detail}
	s.mu.Unlock()
}

func (s *Setup) line(m string) { s.mu.Lock(); s.p.ServerLine = m; s.mu.Unlock() }

const abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

func Password(n int) string {
	b := make([]byte, n)
	for i := range b {
		x, _ := rand.Int(rand.Reader, big.NewInt(int64(len(abc))))
		b[i] = abc[x.Int64()]
	}
	return string(b)
}

var (
	reIf    = regexp.MustCompile(`interface (Wireguard\d+)`)
	reIfN   = regexp.MustCompile(`interface Wireguard(\d+)`)
	reVPNIP = regexp.MustCompile(`ip address (10\.66\.66\.\d+)`)
	reModel = regexp.MustCompile(`"model"\s*:\s*"([^"]+)"`)
	reSSH   = regexp.MustCompile(`\bssh\b`)
	rePub   = regexp.MustCompile(`"public-key"\s*:\s*"([A-Za-z0-9+/]{43}=)"`)
	reSer   = regexp.MustCompile(`"serial"\s*:\s*"([A-Za-z0-9]+)"`)
	reMAC   = regexp.MustCompile(`"mac"\s*:\s*"([0-9A-Fa-f:]{17})"`)
)

// FindTunnel — туннель NordKey в конфиге: имя интерфейса, адрес в VPN, кусок конфига.
func FindTunnel(cfg string) (wif, ip, seg string, ok bool) {
	for _, m := range reIf.FindAllStringSubmatchIndex(cfg, -1) {
		start := m[0]
		end := strings.Index(cfg[start+10:], "interface ")
		if end < 0 {
			end = len(cfg)
		} else {
			end += start + 10
		}
		seg := cfg[start:end]
		if strings.Contains(seg, SrvKey) {
			ip := ""
			if x := reVPNIP.FindStringSubmatch(seg); x != nil {
				ip = x[1]
			}
			return cfg[m[2]:m[3]], ip, seg, true
		}
	}
	return "", "", "", false
}

func FreeWireguard(cfg string) string {
	used := map[string]bool{}
	for _, m := range reIfN.FindAllStringSubmatch(cfg, -1) {
		used[m[1]] = true
	}
	n := 0
	for used[fmt.Sprint(n)] {
		n++
	}
	return fmt.Sprintf("Wireguard%d", n)
}

// ownKey — открытый ключ самого роутера в ответе «show interface» (не ключ сервера-пира).
func ownKey(si string) string {
	for _, m := range rePub.FindAllStringSubmatch(si, -1) {
		if m[1] != SrvKey {
			return m[1]
		}
	}
	return ""
}

func (s *Setup) ok(what string, cmds ...string) error {
	r, err := s.K.RCI(cmds...)
	if err != nil {
		return err
	}
	if e := Errors(r); len(e) > 0 {
		return fmt.Errorf("%s: %s", what, strings.Join(e, "; "))
	}
	return nil
}

func str(m map[string]any, k string) string { v, _ := m[k].(string); return v }

// waitServer — ждём нужное состояние заявки, показывая сообщения сервера.
func (s *Setup) waitServer(run, want string, d time.Duration, allowMove bool) (map[string]any, error) {
	last := ""
	until := time.Now().Add(d)
	for time.Now().Before(until) {
		if st, err := s.S.Get("/v1/staff/onboard/status?run=" + run); err == nil && str(st, "run") == run {
			if m := str(st, "msg"); m != "" && m != last {
				s.line(m)
				last = m
			}
			x := str(st, "state")
			if x == want || x == "err" || (allowMove && x == "move") {
				return st, nil
			}
		}
		time.Sleep(s.Poll)
	}
	return nil, errors.New("сервер не ответил вовремя — итог придёт администратору в Telegram")
}

// Run — весь мастер; итог и ход видны через Snapshot.
func (s *Setup) Run(login, pass string) {
	s.mu.Lock()
	s.p = Progress{Phase: "running", Steps: map[string]StepState{}}
	s.mu.Unlock()
	res, err := s.run(login, pass)
	s.mu.Lock()
	defer s.mu.Unlock()
	if err != nil {
		s.p.Phase, s.p.Error = "failed", err.Error()
		return
	}
	s.p.Phase, s.p.Result = "done", res
}

func (s *Setup) fail(step string, err error) error {
	s.step(step, "fail", err.Error())
	return err
}

func (s *Setup) run(login, pass string) (*Result, error) {
	if s.Poll == 0 {
		s.Poll = 3 * time.Second
	}
	if s.Server == "" {
		s.Server = Known[0]
	}
	s.ngPass = Password(24)
	endpoint := s.Server + ":35180"
	k := s.K

	// 1. вход
	s.step("login", "run", "")
	if err := k.Login(login, pass); err != nil {
		return nil, s.fail("login", err)
	}
	ver, err := k.RCI("show version")
	if err != nil {
		return nil, s.fail("login", err)
	}
	model := ""
	if m := reModel.FindStringSubmatch(ver); m != nil {
		model = m[1]
	}
	s.step("login", "done", model)

	// 2. SSH — без него сервер не сможет управлять роутером; проверяем до любых изменений
	s.step("ssh", "run", "")
	if !reSSH.MatchString(ver) {
		return nil, s.fail("ssh", errors.New("на роутере нет компонента «Сервер SSH». Откройте настройки роутера → Общие настройки → "+
			"Изменить набор компонентов → отметьте «Сервер SSH» и установите (роутер перезагрузится), затем запустите мастер снова"))
	}
	if err := s.ok("включение SSH", "service ssh", "system configuration save"); err != nil {
		return nil, s.fail("ssh", err)
	}
	s.step("ssh", "done", "включён")

	// 3. туннель
	s.step("tunnel", "run", "")
	cfg, err := k.RCI("show running-config")
	if err != nil {
		return nil, s.fail("tunnel", err)
	}
	wif, ip, seg, found := FindTunnel(cfg)
	newClient := !found
	var run string
	if found {
		if ip == "" {
			return nil, s.fail("tunnel", fmt.Errorf("туннель NordKey есть (%s), но без адреса — напишите администратору", wif))
		}
		s.step("tunnel", "done", "уже есть: "+wif+", "+ip)
		s.step("server", "run", "")
		si, _ := k.RCI("show interface " + wif)
		mykey := ownKey(si)
		r, err := s.S.Post("/v1/staff/onboard", map[string]any{"step": "reg", "ip": ip, "key": mykey, "pass": s.ngPass})
		if err != nil {
			return nil, s.fail("server", err)
		}
		run = str(r, "run")
		st, err := s.waitServer(run, "ready", 60*time.Second, false)
		if err != nil {
			return nil, s.fail("server", err)
		}
		if str(st, "state") != "ready" {
			return nil, s.fail("server", errors.New(str(st, "msg")))
		}
		s.step("server", "done", "сервер знает этот роутер")
	} else {
		idn, err := k.RCI("show identification")
		if err != nil {
			return nil, s.fail("tunnel", err)
		}
		ser := reSer.FindStringSubmatch(idn)
		if ser == nil {
			return nil, s.fail("tunnel", errors.New("не удалось прочитать серийный номер роутера"))
		}
		mac := ""
		if m := reMAC.FindStringSubmatch(idn); m != nil {
			mac = strings.ToLower(m[1])
		}
		wif = FreeWireguard(cfg)
		if err := s.ok("создание туннеля", "interface "+wif); err != nil {
			return nil, s.fail("tunnel", err)
		}
		time.Sleep(s.Poll / 3)
		si, _ := k.RCI("show interface " + wif)
		pk := ownKey(si)
		if pk == "" {
			_, _ = k.RCI("no interface " + wif)
			return nil, s.fail("tunnel", errors.New("роутер не создал ключ туннеля — роутер вернул как было"))
		}
		s.step("tunnel", "done", "создан "+wif)
		s.step("server", "run", "")
		undo := func(e error) (*Result, error) {
			_, _ = k.RCI("no interface "+wif, "system configuration save")
			return nil, s.fail("server", fmt.Errorf("%v — роутер вернул как было", e))
		}
		r, err := s.S.Post("/v1/staff/onboard", map[string]any{"step": "new", "key": pk, "pass": s.ngPass, "serial": ser[1], "mac": mac, "name": s.Name})
		if err != nil {
			return undo(err)
		}
		run = str(r, "run")
		st, err := s.waitServer(run, "ready", 60*time.Second, false)
		if err != nil {
			return undo(err)
		}
		if str(st, "state") != "ready" {
			return undo(errors.New(str(st, "msg")))
		}
		ip = str(st, "ip")
		p := "interface " + wif + " wireguard peer " + SrvKey
		if err := s.ok("настройка туннеля", "interface "+wif+" description NordKey", "interface "+wif+" security-level public",
			"interface "+wif+" ip address "+ip+" 255.255.255.255", "interface "+wif+" ip mtu 1420", "interface "+wif+" ip tcp adjust-mss pmtu",
			"interface "+wif+" wireguard asc "+ASC, p, p+" endpoint "+endpoint, p+" keepalive-interval 25", p+" allow-ips 0.0.0.0 0.0.0.0",
			"interface "+wif+" up", "system configuration save"); err != nil {
			return nil, s.fail("server", err)
		}
		_, _ = k.RCI(p+" connect", "system configuration save")
		s.step("server", "done", "клиент создан, адрес "+ip)
	}

	// 4. доступ для сервера: SSH-пользователь netguard, фильтр, маршрут к серверу, адрес сервера
	s.step("access", "run", "")
	acl := "_WEBADMIN_" + wif
	icmp := "access-list " + acl + " permit icmp 10.66.66.1 255.255.255.255 0.0.0.0 0.0.0.0"
	ssh := "access-list " + acl + " permit tcp 10.66.66.1 255.255.255.255 0.0.0.0 0.0.0.0 port eq 22"
	var c []string
	if !strings.Contains(cfg, "access-list "+acl) {
		c = append(c, icmp, ssh)
	} else {
		if !strings.Contains(cfg, "permit icmp 10.66.66.1 255.255.255.255") {
			c = append(c, icmp)
		}
		if !strings.Contains(cfg, "permit tcp 10.66.66.1 255.255.255.255 0.0.0.0 0.0.0.0 port eq 22") {
			c = append(c, ssh)
		}
	}
	c = append(c, "interface "+wif+" ip access-group "+acl+" in", "user netguard", "user netguard password plain "+s.ngPass, "user netguard tag cli", "system configuration save")
	if err := s.ok("доступ для сервера", c...); err != nil {
		return nil, s.fail("access", err)
	}
	if !strings.Contains(cfg, "ip route 10.66.66.1 "+wif) {
		_, _ = k.RCI("ip route 10.66.66.1 255.255.255.255 "+wif+" auto", "system configuration save")
	}
	knownEP := false
	for _, h := range Known {
		knownEP = knownEP || strings.Contains(seg, "endpoint "+h+":35180")
	}
	if !newClient && !(knownEP && strings.Contains(seg, "wireguard asc "+ASC)) {
		if err := s.ok("переключение туннеля", "interface "+wif+" wireguard asc "+ASC, "interface "+wif+" wireguard peer "+SrvKey+" endpoint "+endpoint,
			"system configuration save"); err != nil {
			return nil, s.fail("access", err)
		}
	}
	s.step("access", "done", "")

	// 5. сервер сам заходит по SSH и донастраивает
	s.step("finish", "run", "")
	wait := 600 * time.Second
	if newClient {
		wait = 1500 * time.Second
	}
	if _, err := s.S.Post("/v1/staff/onboard", map[string]any{"step": "done", "ip": ip, "run": run}); err != nil {
		return nil, s.fail("finish", err)
	}
	st, err := s.waitServer(run, "ok", wait, true)
	if err != nil {
		return nil, s.fail("finish", err)
	}
	if str(st, "state") == "move" {
		ip = str(st, "ip")
		if err := s.ok("возврат прежнего адреса", "interface "+wif+" ip address "+ip+" 255.255.255.255", "system configuration save"); err != nil {
			return nil, s.fail("finish", err)
		}
		s.line("роутер уже был клиентом NordKey — вернул ему прежний адрес " + ip)
		if _, err := s.S.Post("/v1/staff/onboard", map[string]any{"step": "done", "ip": ip, "run": run}); err != nil {
			return nil, s.fail("finish", err)
		}
		if st, err = s.waitServer(run, "ok", 1500*time.Second, false); err != nil {
			return nil, s.fail("finish", err)
		}
	}
	link := str(st, "link")
	if str(st, "state") != "ok" {
		msg := str(st, "msg")
		if len([]rune(msg)) > 300 {
			msg = string([]rune(msg)[:300])
		}
		s.step("finish", "fail", msg)
		if link != "" {
			return &Result{IP: ip, Link: link, NewClient: newClient, Note: str(st, "msg")}, nil
		}
		if msg == "" {
			msg = "сервер сообщил об ошибке"
		}
		return nil, errors.New(msg)
	}
	s.step("finish", "done", "")
	return &Result{IP: ip, Link: link, NewClient: newClient}, nil
}
