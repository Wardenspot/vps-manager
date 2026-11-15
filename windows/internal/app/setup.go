package app

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"nordkey/win/internal/keenetic"
)

// Мастер подключения роутера: ПК в сети клиента → вход в Keenetic → туннель, SSH, заявка на сервер → ссылка клиенту.

// staffServer — заявки на сервер NordKey с токеном этого ПК (права проверяет сервер: «newclient»).
// public — клиент подключает свой роутер сам, без входа (как батник).
type staffServer struct {
	a      *App
	public bool
}

func (s staffServer) call(method, path string, in map[string]any) (map[string]any, error) {
	tok := s.a.o.Store.Load().Token
	if s.public {
		tok = ""
	} else if tok == "" {
		return nil, errors.New("войдите в аккаунт NordKey")
	}
	var out map[string]any
	var body any
	if in != nil {
		body = in
	}
	err := s.a.short.Call(method, path, tok, body, &out)
	return out, err
}

func (s staffServer) Post(path string, in map[string]any) (map[string]any, error) {
	return s.call(http.MethodPost, path, in)
}
func (s staffServer) Get(path string) (map[string]any, error) {
	return s.call(http.MethodGet, path, nil)
}

func (a *App) setupHandlers(mux *http.ServeMux) {
	// найти роутер в сети ПК (или проверить адрес, который ввели вручную)
	mux.HandleFunc("POST /local/setup/find", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Host string `json:"host"`
		}
		_ = readJSON(r, &b)
		hosts := keenetic.Candidates()
		if h := strings.TrimSpace(b.Host); h != "" {
			hosts = []string{strings.TrimSuffix(strings.TrimPrefix(strings.TrimPrefix(h, "http://"), "https://"), "/")}
		}
		ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
		defer cancel()
		jsonOut(w, 200, map[string]any{"host": keenetic.Find(ctx, hosts), "tried": hosts})
	})
	mux.HandleFunc("POST /local/setup/start", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Host, Login, Pass, Name string
			Self                    bool
		}
		if err := readJSON(r, &b); err != nil {
			jsonErr(w, err)
			return
		}
		if b.Host == "" || b.Pass == "" {
			jsonErr(w, errors.New("нужны адрес и пароль роутера"))
			return
		}
		if b.Login == "" {
			b.Login = "admin"
		}
		a.mu.Lock()
		if a.setup != nil && a.setup.Snapshot().Phase == "running" {
			a.mu.Unlock()
			jsonErr(w, errors.New("мастер уже работает"))
			return
		}
		s := &keenetic.Setup{K: keenetic.New(b.Host), S: staffServer{a, b.Self}, Name: strings.TrimSpace(b.Name)}
		if b.Self {
			s.Base = "/v1/public/onboard"
		}
		a.setup = s
		a.mu.Unlock()
		a.logf("мастер: подключаю роутер %s", b.Host)
		go func() {
			s.Run(b.Login, b.Pass)
			p := s.Snapshot()
			if p.Phase == "done" {
				a.logf("мастер: готово, адрес в VPN %s", p.Result.IP)
			} else {
				a.logf("мастер: не получилось — %s", p.Error)
			}
		}()
		jsonOut(w, 200, map[string]any{"ok": true})
	})
	mux.HandleFunc("GET /local/setup/state", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		s := a.setup
		a.mu.Unlock()
		if s == nil {
			jsonOut(w, 200, map[string]any{"phase": "idle", "steps": map[string]any{}})
			return
		}
		jsonOut(w, 200, s.Snapshot())
	})
	mux.HandleFunc("GET /local/setup/steps", func(w http.ResponseWriter, r *http.Request) { jsonOut(w, 200, keenetic.Steps) })
}
