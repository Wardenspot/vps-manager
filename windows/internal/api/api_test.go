package api

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPinnedCert(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/bad" {
			w.WriteHeader(400)
			io.WriteString(w, `{"error":"нет подписки"}`)
			return
		}
		io.WriteString(w, `{"ok":true}`)
	}))
	defer srv.Close()
	sum := sha256.Sum256(srv.Certificate().Raw)
	good := New(srv.URL, hex.EncodeToString(sum[:]))
	var out struct{ OK bool }
	if err := good.Call("GET", "/x", "", nil, &out); err != nil || !out.OK {
		t.Fatalf("свой сертификат: %v", err)
	}
	var ae *Error
	if err := good.Call("POST", "/bad", "", nil, nil); !errors.As(err, &ae) || ae.Msg != "нет подписки" || ae.Code != 400 {
		t.Errorf("текст ошибки сервера: %v", err)
	}
	bad := New(srv.URL, CertSHA256)
	if err := bad.Call("GET", "/x", "", nil, nil); !errors.Is(err, ErrPin) {
		t.Errorf("чужой сертификат должен отклоняться: %v", err)
	}
	if New(Base, CertSHA256).Pin != CertSHA256 {
		t.Error("отпечаток")
	}
}
