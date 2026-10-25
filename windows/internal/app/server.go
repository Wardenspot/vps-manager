package app

import (
	"crypto/subtle"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"nordkey/win/internal/store"
)

// Handler — локальный API для окна приложения.
//
// Защита: слушаем только 127.0.0.1; Host — только наш (от подмены DNS); всё, кроме файлов окна,
// требует ключ X-NK-Key (окно получает его при открытии, сайты в браузере его не знают, а свой
// заголовок с чужой страницы без нашего разрешения CORS не отправить).
func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /local/state", a.hState)
	mux.HandleFunc("POST /local/connect", a.hAction(func(r *http.Request) error { return a.Connect() }))
	mux.HandleFunc("POST /local/disconnect", a.hAction(func(r *http.Request) error { return a.Disconnect() }))
	mux.HandleFunc("POST /local/mode", a.hAction(func(r *http.Request) error {
		var b struct {
			Mode string `json:"mode"`
		}
		if err := readJSON(r, &b); err != nil {
			return err
		}
		return a.SetMode(b.Mode)
	}))
	mux.HandleFunc("POST /local/rules/refresh", a.hAction(func(r *http.Request) error { return a.RefreshRules(true) }))
	mux.HandleFunc("POST /local/forget", a.hAction(func(r *http.Request) error { return a.Forget() }))
	mux.HandleFunc("POST /local/logout", a.hAction(func(r *http.Request) error { a.Logout(); return nil }))
	mux.HandleFunc("POST /local/login/start", func(w http.ResponseWriter, r *http.Request) {
		out, err := a.LoginStart()
		if err != nil {
			jsonErr(w, err)
			return
		}
		jsonOut(w, 200, out)
	})
	mux.HandleFunc("POST /local/login/poll", func(w http.ResponseWriter, r *http.Request) {
		st, err := a.LoginPoll()
		if err != nil {
			jsonErr(w, err)
			return
		}
		jsonOut(w, 200, map[string]any{"status": st})
	})
	mux.HandleFunc("POST /local/login/cancel", a.hAction(func(r *http.Request) error {
		a.mu.Lock()
		a.login = nil
		a.mu.Unlock()
		return nil
	}))
	mux.HandleFunc("GET /local/log", func(w http.ResponseWriter, r *http.Request) {
		jsonOut(w, 200, map[string]any{"lines": a.logs.all()})
	})
	mux.HandleFunc("GET /api/", a.hProxy)
	mux.HandleFunc("POST /api/", a.hProxy)
	mux.Handle("GET /", a.static())

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		addr := a.o.Addr
		host := r.Host
		if host != addr && host != strings.Replace(addr, "127.0.0.1", "localhost", 1) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		if strings.HasPrefix(r.URL.Path, "/local/") || strings.HasPrefix(r.URL.Path, "/api/") {
			key := a.o.Store.Load().UIKey
			if key == "" || subtle.ConstantTimeCompare([]byte(r.Header.Get("X-NK-Key")), []byte(key)) != 1 {
				jsonOut(w, http.StatusUnauthorized, map[string]string{"error": "нет доступа: откройте NordKey с ярлыка"})
				return
			}
			if o := r.Header.Get("Origin"); o != "" && o != "http://"+addr && o != "http://"+strings.Replace(addr, "127.0.0.1", "localhost", 1) {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			w.Header().Set("Cache-Control", "no-store")
		}
		mux.ServeHTTP(w, r)
	})
}

func (a *App) static() http.Handler {
	fsrv := http.FileServerFS(a.o.UI)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Security-Policy",
			"default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Cache-Control", "no-cache")
		fsrv.ServeHTTP(w, r)
	})
}

func (a *App) hState(w http.ResponseWriter, r *http.Request) { jsonOut(w, 200, a.State()) }

func (a *App) hAction(f func(*http.Request) error) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := f(r); err != nil {
			jsonErr(w, err)
			return
		}
		jsonOut(w, 200, a.State())
	}
}

// Пути сервера, которые окну не нужны: вход идёт через службу, а конфиг ПК (ключи) окну не показываем.
var blocked = []string{"/v1/auth/", "/v1/pc/connect"}

// hProxy — /api/v1/… → сервер NordKey с токеном этого ПК.
func (a *App) hProxy(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api")
	if !strings.HasPrefix(path, "/v1/") || strings.Contains(path, "..") {
		jsonOut(w, 404, map[string]string{"error": "нет такого адреса"})
		return
	}
	for _, b := range blocked {
		if strings.HasPrefix(path, b) {
			jsonOut(w, 403, map[string]string{"error": "недоступно из окна"})
			return
		}
	}
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		jsonOut(w, 405, map[string]string{"error": "метод"})
		return
	}
	st := a.o.Store.Load()
	if st.Token == "" {
		jsonOut(w, 401, map[string]string{"error": "войдите в аккаунт"})
		return
	}
	var body []byte
	if r.Method == http.MethodPost {
		var err error
		if body, err = io.ReadAll(io.LimitReader(r.Body, 4<<20)); err != nil {
			jsonErr(w, err)
			return
		}
		if len(body) == 0 {
			body = []byte("{}")
		}
	}
	if r.URL.RawQuery != "" {
		path += "?" + r.URL.RawQuery
	}
	code, b, ct, err := a.o.API.Raw(r.Method, path, st.Token, body)
	if err != nil {
		jsonOut(w, 502, map[string]string{"error": err.Error()})
		return
	}
	if code == http.StatusUnauthorized {
		// вход отозван (например, «выйти на всех устройствах») — окно покажет вход; VPN не трогаем
		_, _ = a.o.Store.Update(func(s *store.State) { s.Token = "" })
		a.logf("сервер отклонил вход — нужно войти заново")
	}
	if strings.HasPrefix(path, "/v1/me") && code == 200 && r.Method == http.MethodGet && !strings.HasPrefix(path, "/v1/me/") {
		_, _ = a.o.Store.Update(func(s *store.State) { s.Account = json.RawMessage(b) })
	}
	if ct == "" {
		ct = "application/json"
	}
	w.Header().Set("Content-Type", ct)
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func jsonOut(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func jsonErr(w http.ResponseWriter, err error) {
	jsonOut(w, 400, map[string]string{"error": err.Error()})
}

// Serve — запустить локальный API.
func (a *App) Serve() (*http.Server, error) {
	srv := &http.Server{Addr: a.o.Addr, Handler: a.Handler(), ReadHeaderTimeout: 10 * time.Second}
	ln, err := net.Listen("tcp", a.o.Addr)
	if err != nil {
		return nil, err
	}
	go func() { _ = srv.Serve(ln) }()
	return srv, nil
}
