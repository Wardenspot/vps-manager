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

func startAgent(t *testing.T, cfg Config) *Agent {
	t.Helper()
	if cfg.FilesDir == "" {
		cfg.FilesDir = t.TempDir()
	}
	a := New(cfg)
	stop := make(chan struct{})
	go a.Run(stop)
	t.Cleanup(func() { close(stop) })
	waitFor(t, "регистрация", func() bool { return a.Status().State == StateReady })
	return a
}

// dial подключается к хосту в нужном режиме (дожидаясь, пока он появится в сети)
// и выполняет рукопожатие.
func dial(t *testing.T, relay, id string, mode byte, code string) (*secure.Conn, net.Conn, error) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		c, err := relaylink.DialJoin(relay, id, 3*time.Second)
		if errors.Is(err, relaylink.ErrNoHost) {
			time.Sleep(50 * time.Millisecond)
			continue
		}
		if err != nil {
			t.Fatal(err)
		}
		if err := relaylink.SendMode(c, mode); err != nil {
			t.Fatal(err)
		}
		sc, err := secure.Handshake(c, secure.RoleViewer, id, code)
		return sc, c, err
	}
	t.Fatal("хост так и не появился в сети")
	return nil, nil, nil
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

func skipOnWindows(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("на Windows сеанс реально захватывает экран")
	}
}

// С верным кодом — доступ сразу, без вопроса человеку за ПК. После сеанса
// одноразовый код меняется.
func TestPasswordModeNoConsent(t *testing.T) {
	skipOnWindows(t)
	relay, id := startRelay(t), "200300400"
	a := startAgent(t, Config{Relay: relay, ID: id, Confirm: func(string) bool {
		t.Error("с кодом согласие спрашивать не должны")
		return false
	}})
	code := a.Status().Code

	sc, conn, err := dial(t, relay, id, relaylink.ModePassword, code)
	if err != nil {
		t.Fatalf("рукопожатие с верным кодом: %v", err)
	}
	defer conn.Close()
	// Сеанс начался сразу (на Linux он сообщит, что захват экрана недоступен).
	if why := readEnd(t, sc); !strings.Contains(why, "захватить экран") {
		t.Fatalf("причина: %q", why)
	}
	waitFor(t, "смена кода после сеанса", func() bool { return a.Status().Code != code })
}

// Без кода — человека за ПК спрашивают, показывая проверочный номер, который
// совпадает с номером у подключающегося.
func TestRequestModeAsksWithSAS(t *testing.T) {
	relay, id := startRelay(t), "300400500"
	shown := make(chan string, 1)
	startAgent(t, Config{Relay: relay, ID: id, Confirm: func(sas string) bool {
		shown <- sas
		return false
	}})

	sc, conn, err := dial(t, relay, id, relaylink.ModeRequest, "")
	if err != nil {
		t.Fatalf("рукопожатие без кода: %v", err)
	}
	defer conn.Close()
	if why := readEnd(t, sc); !strings.Contains(why, "отклонено") {
		t.Fatalf("причина: %q", why)
	}
	select {
	case sas := <-shown:
		if sas != sc.SAS() || len(sas) != 4 {
			t.Fatalf("проверочные номера: у хоста %q, у клиента %q", sas, sc.SAS())
		}
	default:
		t.Fatal("согласие не спрашивали")
	}
}

func TestRequestModeAccepted(t *testing.T) {
	skipOnWindows(t)
	relay, id := startRelay(t), "310410510"
	startAgent(t, Config{Relay: relay, ID: id, Confirm: func(string) bool { return true }})
	sc, conn, err := dial(t, relay, id, relaylink.ModeRequest, "")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if why := readEnd(t, sc); !strings.Contains(why, "захватить экран") {
		t.Fatalf("после «Да» должен начаться сеанс, причина: %q", why)
	}
}

// Консольный хост без окна согласия принимает только по коду.
func TestRequestModeWithoutConfirmRejected(t *testing.T) {
	relay, id := startRelay(t), "320420520"
	startAgent(t, Config{Relay: relay, ID: id})
	sc, conn, err := dial(t, relay, id, relaylink.ModeRequest, "")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if why := readEnd(t, sc); !strings.Contains(why, "только по коду") {
		t.Fatalf("причина: %q", why)
	}
}

func TestWrongCodesRotate(t *testing.T) {
	relay, id := startRelay(t), "400500600"
	a := startAgent(t, Config{Relay: relay, ID: id})
	code := a.Status().Code
	wrong := string('0'+(code[0]-'0'+1)%10) + code[1:]

	for i := 0; i < maxFails; i++ {
		_, conn, err := dial(t, relay, id, relaylink.ModePassword, wrong)
		conn.Close()
		if !errors.Is(err, secure.ErrConfirm) {
			t.Fatalf("попытка %d: ждали ErrConfirm, получили %v", i+1, err)
		}
	}
	waitFor(t, "смена кода после 3 неверных", func() bool { return a.Status().Code != code })
}

// Переподключение после повышения прав: код переподключения действует один раз.
func TestResumeCodeOnce(t *testing.T) {
	skipOnWindows(t)
	relay, id := startRelay(t), "500600700"
	startAgent(t, Config{
		Relay: relay, ID: id,
		ResumeCode:  "resume-0123456789abcdef",
		ResumeUntil: time.Now().Add(time.Minute),
	})
	sc, conn, err := dial(t, relay, id, relaylink.ModePassword, "resume-0123456789abcdef")
	if err != nil {
		t.Fatalf("переподключение по коду: %v", err)
	}
	if why := readEnd(t, sc); !strings.Contains(why, "захватить экран") {
		t.Fatalf("причина: %q", why)
	}
	conn.Close()

	_, conn, err = dial(t, relay, id, relaylink.ModePassword, "resume-0123456789abcdef")
	conn.Close()
	if !errors.Is(err, secure.ErrConfirm) {
		t.Fatalf("повторное использование кода переподключения: %v", err)
	}
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
}

func TestSafeRelStaysInside(t *testing.T) {
	cases := map[string]string{
		"папка/файл.txt":         filepath.Join("папка", "файл.txt"),
		"../../Windows/System32": filepath.Join("Windows", "System32"),
		`..\..\a\b.dll`:          filepath.Join("a", "b.dll"),
		"/abs/path/x":            filepath.Join("abs", "path", "x"),
	}
	for in, want := range cases {
		if got := safeRel(in); got != want {
			t.Errorf("safeRel(%q) = %q, ждали %q", in, got, want)
		}
	}
}

func TestUniquePathNoOverwrite(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "отчёт.docx"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(dir, "отчёт (1).docx"), []byte("x"), 0o644)
	if got := filepath.Base(uniquePath(dir, "отчёт.docx")); got != "отчёт (2).docx" {
		t.Fatalf("получили %q", got)
	}
}

// Перетаскивание папки: пакет собирается во временной папке и переносится на
// рабочий стол целиком; существующая папка не перезаписывается.
func TestBatchToDesktop(t *testing.T) {
	desk := t.TempDir()
	os.Mkdir(filepath.Join(desk, "Отчёты"), 0o755) // уже есть на рабочем столе
	rx := newFileReceiver(desk, func(string, ...any) {})
	rx.start(fileMeta{Batch: "b1", Rel: "Отчёты", Dir: true})
	rx.start(fileMeta{Batch: "b1", Rel: "Отчёты/январь.txt", Size: 5})
	rx.write([]byte("12345"))
	rx.finish()
	rx.start(fileMeta{Batch: "b1", Name: "a.txt", Size: 3})
	rx.write([]byte("abc"))
	rx.finish()
	rx.start(fileMeta{Batch: "b1", Name: "broken.bin", Size: 10}) // недокачанный
	rx.write([]byte("xx"))
	rx.finish()

	paths, err := rx.batchItems("b1", []string{"Отчёты", "a.txt", "broken.bin"})
	if err != nil {
		t.Fatal(err)
	}
	if len(paths) != 2 {
		t.Fatalf("ждали 2 элемента (недокачанный удалён), получили %v", paths)
	}
	for _, p := range paths {
		if err := moveTree(p, uniquePath(desk, filepath.Base(p))); err != nil {
			t.Fatal(err)
		}
	}
	if b, _ := os.ReadFile(filepath.Join(desk, "Отчёты (1)", "январь.txt")); string(b) != "12345" {
		t.Fatalf("папка должна лечь рядом как «Отчёты (1)», содержимое: %q", b)
	}
	if b, _ := os.ReadFile(filepath.Join(desk, "a.txt")); string(b) != "abc" {
		t.Fatalf("a.txt: %q", b)
	}
}
