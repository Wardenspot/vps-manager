package keenetic

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestAuthHash(t *testing.T) {
	// то же значение проверяет тест приложения для Android (посчитано отдельно, Python hashlib)
	if h := AuthHash("admin", "Secret123", "Keenetic Giga", "ABCDEF0123"); h != "fd856a31f7519c3bc9dc8957877a61c41c26e0a2487c0e4b302264c8ef11f55d" {
		t.Fatal(h)
	}
}

const cfgOther = "interface Wireguard0\n    description home\n    wireguard peer AAAA=\n!\ninterface Wireguard1\n    wireguard peer qUd0UkUPGKHpnrNPfZM193Bv84R/cM8w0GJ9x23qfgI=\n!\n"

func TestFindTunnelAndFree(t *testing.T) {
	cfg := cfgOther + "interface Wireguard2\n    description NordKey\n    ip address 10.66.66.77 255.255.255.255\n    wireguard peer " + SrvKey + "\n!\n"
	w, ip, _, ok := FindTunnel(cfg)
	if !ok || w != "Wireguard2" || ip != "10.66.66.77" {
		t.Fatal(w, ip, ok)
	}
	if _, _, _, ok := FindTunnel(cfgOther); ok {
		t.Fatal("нашёл чужой туннель")
	}
	if f := FreeWireguard(cfg); f != "Wireguard3" {
		t.Fatal(f)
	}
}

// fakeRouter — Keenetic: вход по хешу, RCI с записью команд.
type fakeRouter struct {
	mu     sync.Mutex
	pass   string
	ssh    bool
	cfg    string
	cmds   []string
	logged bool
}

func (f *fakeRouter) handler() http.Handler {
	m := http.NewServeMux()
	m.HandleFunc("/auth", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if r.Method == http.MethodGet {
			if c, _ := r.Cookie("s"); c != nil && f.logged {
				return
			}
			w.Header().Set("X-NDM-Realm", "Keenetic Giga")
			w.Header().Set("X-NDM-Challenge", "CH1")
			w.WriteHeader(401)
			return
		}
		var b map[string]string
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["password"] != AuthHash(b["login"], f.pass, "Keenetic Giga", "CH1") {
			w.WriteHeader(401)
			return
		}
		f.logged = true
		http.SetCookie(w, &http.Cookie{Name: "s", Value: "1", Path: "/"})
	})
	m.HandleFunc("/rci/", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if c, _ := r.Cookie("s"); c == nil {
			w.WriteHeader(401)
			return
		}
		var arr []map[string]string
		_ = json.NewDecoder(r.Body).Decode(&arr)
		var out []string
		for _, x := range arr {
			c := x["parse"]
			f.cmds = append(f.cmds, c)
			switch {
			case c == "show version":
				comp := "base,wireguard"
				if f.ssh {
					comp += ",ssh"
				}
				out = append(out, `{"model":"Keenetic Giga","ndw":{"components":"`+comp+`"}}`)
			case c == "show running-config":
				out = append(out, `{"message":`+mustJSON(f.cfg)+`}`)
			case c == "show identification":
				out = append(out, `{"serial":"S2412AB000123","mac":"50:FF:20:AA:BB:CC"}`)
			case strings.HasPrefix(c, "show interface Wireguard"):
				out = append(out, `{"wireguard":{"public-key":"RtrKeyRtrKeyRtrKeyRtrKeyRtrKeyRtrKeyRtrKeyA=","peer":[{"public-key":"`+SrvKey+`"}]}}`)
			default:
				out = append(out, `{}`)
			}
		}
		_, _ = w.Write([]byte("[" + strings.Join(out, ",") + "]"))
	})
	return m
}

func mustJSON(s string) string { b, _ := json.Marshal(s); return string(b) }

// fakeServer — nk-api: заявки и их состояние.
type fakeServer struct {
	mu    sync.Mutex
	posts []map[string]any
	state map[string]any
	move  bool
}

func (s *fakeServer) Post(path string, in map[string]any) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.posts = append(s.posts, in)
	switch in["step"] {
	case "new":
		s.state = map[string]any{"run": "R1", "state": "ready", "ip": "10.66.66.171", "msg": "выдан адрес 10.66.66.171"}
	case "reg":
		s.state = map[string]any{"run": "R1", "state": "ready", "msg": "роутер найден"}
	case "done":
		if s.move && in["ip"] != "10.66.66.40" {
			s.state = map[string]any{"run": "R1", "state": "move", "ip": "10.66.66.40", "msg": "это старый клиент"}
		} else {
			s.state = map[string]any{"run": "R1", "state": "ok", "msg": "готово", "link": "https://t.me/NordKeyVPN_bot?start=r_x"}
		}
	}
	return map[string]any{"ok": true, "run": "R1"}, nil
}

func (s *fakeServer) Get(path string) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state, nil
}

func start(t *testing.T, f *fakeRouter, srv *fakeServer) *Setup {
	ts := httptest.NewServer(f.handler())
	t.Cleanup(ts.Close)
	return &Setup{K: New(strings.TrimPrefix(ts.URL, "http://")), S: srv, Name: "Иван дача", Poll: 10 * time.Millisecond}
}

func has(cmds []string, sub string) bool {
	for _, c := range cmds {
		if strings.Contains(c, sub) {
			return true
		}
	}
	return false
}

func TestNewClient(t *testing.T) {
	f := &fakeRouter{pass: "pw", ssh: true, cfg: cfgOther}
	srv := &fakeServer{}
	s := start(t, f, srv)
	s.Run("admin", "pw")
	p := s.Snapshot()
	if p.Phase != "done" || p.Result == nil || p.Result.Link == "" || !p.Result.NewClient || p.Result.IP != "10.66.66.171" {
		t.Fatalf("%+v %+v", p, p.Result)
	}
	for _, want := range []string{"service ssh", "interface Wireguard2", "interface Wireguard2 ip address 10.66.66.171 255.255.255.255",
		"peer " + SrvKey + " endpoint 143.246.193.43:35180", "user netguard password plain ", "access-list _WEBADMIN_Wireguard2 permit tcp"} {
		if !has(f.cmds, want) {
			t.Errorf("нет команды %q", want)
		}
	}
	if srv.posts[0]["serial"] != "S2412AB000123" || srv.posts[0]["mac"] != "50:ff:20:aa:bb:cc" || srv.posts[0]["key"] != "RtrKeyRtrKeyRtrKeyRtrKeyRtrKeyRtrKeyRtrKeyA=" {
		t.Errorf("заявка: %v", srv.posts[0])
	}
	if pw, _ := srv.posts[0]["pass"].(string); len(pw) != 24 || !has(f.cmds, "password plain "+pw) {
		t.Error("пароль netguard на сервере и на роутере разный")
	}
}

func TestExistingClientMoves(t *testing.T) {
	cfg := cfgOther + "interface Wireguard2\n    ip address 10.66.66.90 255.255.255.255\n    wireguard peer " + SrvKey + "\n    endpoint 143.246.193.103:35180\n!\n"
	f := &fakeRouter{pass: "pw", ssh: true, cfg: cfg}
	srv := &fakeServer{move: true}
	s := start(t, f, srv)
	s.Run("admin", "pw")
	p := s.Snapshot()
	if p.Phase != "done" || p.Result.NewClient || p.Result.IP != "10.66.66.40" {
		t.Fatalf("%+v %+v", p, p.Result)
	}
	if srv.posts[0]["step"] != "reg" || srv.posts[0]["ip"] != "10.66.66.90" {
		t.Errorf("заявка: %v", srv.posts[0])
	}
	if !has(f.cmds, "interface Wireguard2 wireguard asc "+ASC) || !has(f.cmds, "interface Wireguard2 ip address 10.66.66.40") {
		t.Error("не переключил туннель / не вернул прежний адрес")
	}
	if has(f.cmds, "interface Wireguard3") {
		t.Error("создал лишний туннель")
	}
}

func TestNoSSHStopsBeforeChanges(t *testing.T) {
	f := &fakeRouter{pass: "pw", ssh: false, cfg: cfgOther}
	s := start(t, f, &fakeServer{})
	s.Run("admin", "pw")
	p := s.Snapshot()
	if p.Phase != "failed" || p.Steps["ssh"].Status != "fail" || !strings.Contains(p.Error, "Сервер SSH") {
		t.Fatalf("%+v", p)
	}
	if len(f.cmds) != 1 {
		t.Errorf("до проверки SSH что-то менял: %v", f.cmds)
	}
}

func TestWrongPassword(t *testing.T) {
	s := start(t, &fakeRouter{pass: "pw", ssh: true}, &fakeServer{})
	s.Run("admin", "bad")
	if p := s.Snapshot(); p.Phase != "failed" || p.Steps["login"].Status != "fail" || p.Error != "неверный логин или пароль роутера" {
		t.Fatalf("%+v", p)
	}
}

func TestProbeAndFind(t *testing.T) {
	ts := httptest.NewServer((&fakeRouter{}).handler())
	defer ts.Close()
	h := strings.TrimPrefix(ts.URL, "http://")
	if got := Find(t.Context(), []string{"127.0.0.1:1", h}); got != h {
		t.Fatal(got)
	}
}

func TestPublicBase(t *testing.T) {
	f := &fakeRouter{pass: "pw", ssh: true, cfg: cfgOther}
	srv := &pathServer{fakeServer: &fakeServer{}}
	s := start(t, f, nil)
	s.S, s.Base = srv, "/v1/public/onboard"
	s.Run("admin", "pw")
	if p := s.Snapshot(); p.Phase != "done" {
		t.Fatalf("%+v", p)
	}
	for _, x := range srv.paths {
		if !strings.HasPrefix(x, "/v1/public/onboard") {
			t.Fatalf("заявка не туда: %s", x)
		}
	}
}

type pathServer struct {
	*fakeServer
	paths []string
}

func (p *pathServer) Post(path string, in map[string]any) (map[string]any, error) {
	p.paths = append(p.paths, path)
	return p.fakeServer.Post(path, in)
}
func (p *pathServer) Get(path string) (map[string]any, error) {
	p.paths = append(p.paths, path)
	return p.fakeServer.Get(path)
}

func TestProbeHTTPSOnly(t *testing.T) {
	ts := httptest.NewTLSServer((&fakeRouter{}).handler())
	defer ts.Close()
	c := New(strings.TrimPrefix(ts.URL, "https://"))
	if !c.Probe(t.Context()) || c.Scheme != "https" {
		t.Fatalf("роутер только по https не найден: %v %s", c.Probe(t.Context()), c.Scheme)
	}
}
