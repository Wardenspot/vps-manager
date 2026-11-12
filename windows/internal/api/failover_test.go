package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// Первый адрес недоступен — запрос уходит на следующий, и клиент запоминает рабочий.
func TestFailoverToNextHost(t *testing.T) {
	hits := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		w.Write([]byte(`{"ok":true}`))
	}))
	defer srv.Close()

	c := New("http://unused", "")
	c.alt = []string{"http://127.0.0.1:1", srv.URL}
	for _, m := range []string{http.MethodGet, http.MethodPost} {
		code, _, _, err := c.Raw(m, "/x", "", []byte("{}"))
		if err != nil || code != 200 {
			t.Fatalf("%s: code=%d err=%v", m, code, err)
		}
	}
	if c.cur.Load() != 1 || hits != 2 {
		t.Fatalf("cur=%d hits=%d", c.cur.Load(), hits)
	}
}
