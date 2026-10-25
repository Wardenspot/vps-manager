package app

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"testing/fstest"

	"nordkey/win/internal/api"
	"nordkey/win/internal/engine"
	"nordkey/win/internal/store"
)

const key32 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXphYmNkZWY="

// поддельный сервер NordKey
var (
	callsMu sync.Mutex
	staffPC atomic.Bool
)

func fakeServer(t *testing.T, calls *[]string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		callsMu.Lock()
		*calls = append(*calls, r.Method+" "+r.URL.RequestURI()+" "+r.Header.Get("Authorization"))
		callsMu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/v1/auth/start":
			io.WriteString(w, `{"code":"C","secret":"S","pick":7,"bot_url":"https://t.me/nk_bot?start=login_C","expires_in":300}`)
		case "/v1/auth/poll":
			io.WriteString(w, `{"status":"ok","token":"T1"}`)
		case "/v1/pc/connect":
			io.WriteString(w, `{"ok":true,"config":{"ip":"10.66.66.21","address":"10.66.66.21/32","private_key":"`+key32+`","server_public_key":"`+key32+
				`","endpoint":"143.246.193.103:35180","awg":{"jc":4}}}`)
		case "/v1/pc/routes":
			if r.URL.Query().Get("v") == "v1" {
				io.WriteString(w, `{"version":"v1","same":true}`)
				return
			}
			io.WriteString(w, `{"version":"v1","vpn_domains":["instagram.com"],"vpn_nets":["31.13.64.0/18"]}`)
		case "/v1/pc":
			io.WriteString(w, fmt.Sprintf(`{"staff":%v}`, staffPC.Load()))
		case "/v1/me":
			io.WriteString(w, `{"account":{"role":"client"}}`)
		default:
			io.WriteString(w, `{"ok":true}`)
		}
	}))
}

func setup(t *testing.T) (*App, http.Handler, string, *[]string) {
	calls := &[]string{}
	srv := fakeServer(t, calls)
	t.Cleanup(srv.Close)
	st, err := store.New(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	a := New(Options{API: api.New(srv.URL, ""), Store: st, Engine: engine.New(nil), Version: "t", Device: "ПК test",
		UI: fstest.MapFS{"index.html": {Data: []byte("<html>")}}})
	a.Start()
	t.Cleanup(func() { a.Stop() })
	return a, a.Handler(), st.Load().UIKey, calls
}

func do(h http.Handler, method, path, key string, hdr ...string) (int, map[string]any) {
	r := httptest.NewRequest(method, "http://"+Addr+path, strings.NewReader("{}"))
	r.Host = Addr
	if key != "" {
		r.Header.Set("X-NK-Key", key)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		if hdr[i] == "Host" {
			r.Host = hdr[i+1]
		} else {
			r.Header.Set(hdr[i], hdr[i+1])
		}
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	var m map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &m)
	return w.Code, m
}

func TestSecurity(t *testing.T) {
	_, h, key, _ := setup(t)
	if len(key) < 32 {
		t.Fatal("ключ окна не создан")
	}
	if c, _ := do(h, "GET", "/", ""); c != 200 {
		t.Errorf("файлы окна — без ключа: %d", c)
	}
	if c, _ := do(h, "GET", "/local/state", ""); c != 401 {
		t.Errorf("без ключа: %d", c)
	}
	if c, _ := do(h, "GET", "/local/state", "bad"); c != 401 {
		t.Errorf("чужой ключ: %d", c)
	}
	if c, _ := do(h, "GET", "/local/state", key, "Host", "evil.example:47811"); c != 403 {
		t.Errorf("подмена DNS (чужой Host): %d", c)
	}
	if c, _ := do(h, "POST", "/local/connect", key, "Origin", "https://evil.example"); c != 403 {
		t.Errorf("чужая страница: %d", c)
	}
	for _, p := range []string{"/api/v1/pc/connect", "/api/v1/auth/start", "/api/v1/auth/poll"} {
		if c, _ := do(h, "POST", p, key); c != 403 {
			t.Errorf("%s окну недоступен: %d", p, c)
		}
	}
	if c, _ := do(h, "GET", "/api/v1/me", key); c != 401 {
		t.Errorf("до входа сервер не трогаем: %d", c)
	}
}

func TestLoginConnectProxy(t *testing.T) {
	a, h, key, calls := setup(t)
	c, m := do(h, "POST", "/local/login/start", key)
	if c != 200 || m["pick"].(float64) != 7 || m["tg_url"] != "tg://resolve?domain=nk_bot&start=login_C" {
		t.Fatalf("вход: %d %v", c, m)
	}
	if _, ok := m["secret"]; ok {
		t.Error("секрет входа окну не отдаём")
	}
	if _, m = do(h, "POST", "/local/login/poll", key); m["status"] != "ok" {
		t.Fatalf("poll: %v", m)
	}
	if _, m = do(h, "GET", "/local/state", key); m["logged_in"] != true {
		t.Fatalf("state: %v", m)
	}
	if c, m = do(h, "POST", "/local/connect", key); c != 200 || m["vpn"].(map[string]any)["state"] != "on" || m["ip"] != "10.66.66.21" {
		t.Fatalf("connect: %d %v", c, m)
	}
	if st := a.o.Store.Load(); !st.WantUp || st.Config == nil || len(st.Rules) == 0 {
		t.Error("после включения: помним конфиг, списки и «включён»")
	}
	if c, _ = do(h, "GET", "/api/v1/me", key); c != 200 {
		t.Errorf("прокси: %d", c)
	}
	joined := strings.Join(*calls, "\n")
	if !strings.Contains(joined, "GET /v1/me Bearer T1") || !strings.Contains(joined, "POST /v1/pc/connect Bearer T1") {
		t.Errorf("служба подставляет токен:\n%s", joined)
	}
	if c, m = do(h, "POST", "/local/mode", key); c != 400 {
		t.Errorf("пустой режим — ошибка: %d %v", c, m)
	}
	if err := a.SetMode("all"); err == nil || a.o.Engine.Status().Mode != "smart" {
		t.Errorf("клиенту — только «как на роутере»: %v %v", err, a.o.Engine.Status())
	}
	staffPC.Store(true)
	if err := a.SetMode("all"); err != nil || a.o.Engine.Status().Mode != "all" {
		t.Errorf("сотруднику — любой режим: %v %v", err, a.o.Engine.Status())
	}
	if err := a.RefreshRules(false); err != nil || !strings.Contains(strings.Join(*calls, "\n"), "/v1/pc/routes?v=v1") {
		t.Errorf("обновление списков по версии: %v", err)
	}
	do(h, "POST", "/local/logout", key)
	st := a.o.Store.Load()
	if st.Token != "" || st.Config != nil || st.WantUp || st.UIKey != key || a.o.Engine.Status().State != "off" {
		t.Errorf("выход: всё стёрто, кроме ключа окна: %+v", st)
	}
}

func TestTgURL(t *testing.T) {
	for in, want := range map[string]string{
		"https://t.me/nk_bot?start=x": "tg://resolve?domain=nk_bot&start=x",
		"https://t.me/nk_bot":         "tg://resolve?domain=nk_bot",
		"https://evil.com/x":          "",
		"https://t.me/a/b?x":          "",
	} {
		if got := tgURL(in); got != want {
			t.Errorf("%s → %q, want %q", in, got, want)
		}
	}
}
