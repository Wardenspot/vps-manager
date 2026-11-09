package hostagent

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"norddesk/internal/relaylink"
	"norddesk/internal/relaysrv"
	"norddesk/internal/secure"
	"norddesk/internal/wire"
)

func startRelay(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go relaysrv.New().Serve(ln)
	t.Cleanup(func() { ln.Close() })
	return ln.Addr().String()
}

func startAgent(t *testing.T, relay, id string, confirm func() bool) *Agent {
	t.Helper()
	a := New(Config{Relay: relay, ID: id, Confirm: confirm, FilesDir: t.TempDir()})
	stop := make(chan struct{})
	go a.Run(stop)
	t.Cleanup(func() { close(stop) })
	return a
}

// join подключается к хосту, дожидаясь, пока он (пере)зарегистрируется.
func join(t *testing.T, relay, id string) net.Conn {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		c, err := relaylink.DialJoin(relay, id, 3*time.Second)
		if err == nil {
			return c
		}
		if !errors.Is(err, relaylink.ErrNoHost) {
			t.Fatal(err)
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("хост так и не появился в сети")
	return nil
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("не дождались: %s", what)
}

// readEnd читает сообщения хоста до TypeSessionEnd и возвращает причину.
func readEnd(t *testing.T, sc *secure.Conn) string {
	t.Helper()
	for {
		raw, err := sc.ReadFrame()
		if err != nil {
			t.Fatalf("ждали SessionEnd, а соединение закрылось: %v", err)
		}
		m, err := wire.Decode(raw)
		if err != nil {
			t.Fatal(err)
		}
		if m.Type == wire.TypeSessionEnd {
			return string(m.Data)
		}
	}
}

func TestRejectEndsSessionAndRotatesCode(t *testing.T) {
	relay := startRelay(t)
	id := "200300400"
	a := startAgent(t, relay, id, func() bool { return false })
	waitFor(t, "регистрация", func() bool { return a.Status().State == StateReady })
	code := a.Status().Code

	conn := join(t, relay, id)
	defer conn.Close()
	sc, err := secure.Handshake(conn, secure.RoleViewer, id, code)
	if err != nil {
		t.Fatalf("рукопожатие с верным кодом: %v", err)
	}
	if why := readEnd(t, sc); !strings.Contains(why, "отклонено") {
		t.Fatalf("причина: %q", why)
	}
	waitFor(t, "смена кода после отказа", func() bool { return a.Status().Code != code })
}

func TestAcceptStartsSession(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("на Windows сеанс реально захватывает экран")
	}
	relay := startRelay(t)
	id := "300400500"
	asked := make(chan struct{}, 1)
	a := startAgent(t, relay, id, func() bool { asked <- struct{}{}; return true })
	waitFor(t, "регистрация", func() bool { return a.Status().State == StateReady })

	conn := join(t, relay, id)
	defer conn.Close()
	sc, err := secure.Handshake(conn, secure.RoleViewer, id, a.Status().Code)
	if err != nil {
		t.Fatal(err)
	}
	// Без Windows захват экрана недоступен — хост обязан сообщить об этом
	// клиенту, а не молча оборвать связь.
	if why := readEnd(t, sc); !strings.Contains(why, "захватить экран") {
		t.Fatalf("причина: %q", why)
	}
	select {
	case <-asked:
	default:
		t.Fatal("согласие не спрашивали")
	}
}

func TestWrongCodesRotate(t *testing.T) {
	relay := startRelay(t)
	id := "400500600"
	a := startAgent(t, relay, id, func() bool { return true })
	waitFor(t, "регистрация", func() bool { return a.Status().State == StateReady })
	code := a.Status().Code
	wrong := string('0'+(code[0]-'0'+1)%10) + code[1:]

	for i := 0; i < maxFails; i++ {
		conn := join(t, relay, id)
		_, err := secure.Handshake(conn, secure.RoleViewer, id, wrong)
		conn.Close()
		if !errors.Is(err, secure.ErrConfirm) {
			t.Fatalf("попытка %d: ждали ErrConfirm, получили %v", i+1, err)
		}
	}
	waitFor(t, "смена кода после 3 неверных", func() bool { return a.Status().Code != code })
}

func TestSafeName(t *testing.T) {
	cases := map[string]string{
		"отчёт.docx":          "отчёт.docx",
		`..\..\Windows\x.dll`: "x.dll",
		"../../etc/passwd":    "passwd",
		`a<b>c:d"e|f?g*.txt`:  "a_b_c_d_e_f_g_.txt",
		"CON.txt":             "_CON.txt",
		"name. ":              "name",
	}
	for in, want := range cases {
		if got := safeName(in); got != want {
			t.Errorf("safeName(%q) = %q, ждали %q", in, got, want)
		}
	}
	if got := safeName(".."); got == ".." || got == "" {
		t.Errorf("safeName(\"..\") = %q", got)
	}
}

func TestUniquePathNoOverwrite(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "отчёт.docx"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(dir, "отчёт (1).docx"), []byte("x"), 0o644)
	if got := filepath.Base(uniquePath(dir, "отчёт.docx")); got != "отчёт (2).docx" {
		t.Fatalf("получили %q", got)
	}
	if got := filepath.Base(uniquePath(dir, "новый.txt")); got != "новый.txt" {
		t.Fatalf("получили %q", got)
	}
}
