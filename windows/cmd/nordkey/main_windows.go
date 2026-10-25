//go:build windows

// NordKey.exe — одно приложение на все роли:
//   - запущен службой Windows → держит VPN и локальный API окна;
//   - «install» / «uninstall» (с правами администратора) → установка и удаление;
//   - обычный запуск (ярлык) → при необходимости установка, затем окно в Edge.
//go:debug httpmuxgo121=0

package main

import (
	_ "embed"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"

	"nordkey/win/internal/api"
	"nordkey/win/internal/app"
	"nordkey/win/internal/engine"
	"nordkey/win/internal/store"
	"nordkey/win/ui"
)

var version = "dev"

const svcName = "NordKey"

func programFiles() string { return envOr("ProgramFiles", `C:\Program Files`) }
func programData() string  { return envOr("ProgramData", `C:\ProgramData`) }
func installDir() string   { return filepath.Join(programFiles(), "NordKey") }
func installedExe() string { return filepath.Join(installDir(), "NordKey.exe") }
func dataDir() string      { return filepath.Join(programData(), "NordKey") }
func keyDir() string       { return filepath.Join(dataDir(), "ui") }
func keyPath() string      { return filepath.Join(keyDir(), "key") }

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	if isSvc, _ := svc.IsWindowsService(); isSvc {
		runService()
		return
	}
	arg := ""
	if len(os.Args) > 1 {
		arg = strings.ToLower(strings.TrimLeft(os.Args[1], "-/"))
	}
	switch arg {
	case "install":
		if err := install(); err != nil {
			msgBox("Не удалось установить NordKey:\n\n"+err.Error(), true)
			os.Exit(1)
		}
	case "uninstall":
		uninstallCmd()
	default:
		launcher()
	}
}

// ───────── служба ─────────

type logFile struct {
	mu   sync.Mutex
	path string
	f    *os.File
	n    int64
}

func (l *logFile) Write(b []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.f == nil || l.n > 2<<20 {
		if l.f != nil {
			l.f.Close()
			_ = os.Rename(l.path, l.path+".old")
		}
		f, err := os.OpenFile(l.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
		if err != nil {
			return len(b), nil
		}
		st, _ := f.Stat()
		l.f, l.n = f, st.Size()
	}
	n, err := l.f.Write(b)
	l.n += int64(n)
	return n, err
}

type service struct{}

func (service) Execute(args []string, req <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	status <- svc.Status{State: svc.StartPending}
	st, err := store.New(dataDir())
	if err != nil {
		log.Printf("папка данных: %v", err)
		return true, 1
	}
	host, _ := os.Hostname()
	eng := engine.New(log.Printf)
	a := app.New(app.Options{
		API: api.New(api.Base, api.CertSHA256), Store: st, Engine: eng, UI: ui.FS, Version: version,
		Device: strings.TrimSpace("ПК " + host), KeyFile: writeKey, Logf: log.Printf,
	})
	a.Start()
	var srv interface{ Close() error }
	for i := 0; ; i++ { // порт мог ещё не освободиться после перезапуска
		s, err := a.Serve()
		if err == nil {
			srv = s
			break
		}
		if i == 20 {
			log.Printf("локальный API: %v", err)
			return true, 2
		}
		time.Sleep(500 * time.Millisecond)
	}
	log.Printf("NordKey %s запущен", version)
	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for c := range req {
		switch c.Cmd {
		case svc.Interrogate:
			status <- c.CurrentStatus
		case svc.Stop, svc.Shutdown:
			status <- svc.Status{State: svc.StopPending}
			_ = srv.Close()
			a.Stop() // VPN выключаем и убираем перехват DNS; «был включён» остаётся — включится после перезагрузки
			log.Printf("NordKey остановлен")
			return false, 0
		}
	}
	return false, 0
}

func runService() {
	_ = os.MkdirAll(dataDir(), 0o700)
	log.SetOutput(io.Writer(&logFile{path: filepath.Join(dataDir(), "nordkey.log")}))
	log.SetFlags(log.LstdFlags)
	if err := svc.Run(svcName, service{}); err != nil {
		log.Printf("служба: %v", err)
	}
}

// writeKey — ключ окна: читать могут пользователи этого ПК (ярлык открывает окно с ним), писать — только система.
func writeKey(key string) error {
	if err := os.MkdirAll(keyDir(), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(keyPath(), []byte(key), 0o644); err != nil {
		return err
	}
	sd, err := windows.SecurityDescriptorFromString("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FR;;;IU)(A;OICI;FR;;;BU)")
	if err != nil {
		return err
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	for _, p := range []string{keyDir(), keyPath()} {
		if err := windows.SetNamedSecurityInfo(p, windows.SE_FILE_OBJECT,
			windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, dacl, nil); err != nil {
			return err
		}
	}
	return nil
}

func msgBox(text string, isErr bool) {
	flags := uint32(windows.MB_OK | windows.MB_SETFOREGROUND)
	if isErr {
		flags |= windows.MB_ICONERROR
	} else {
		flags |= windows.MB_ICONINFORMATION
	}
	t, _ := windows.UTF16PtrFromString(text)
	c, _ := windows.UTF16PtrFromString("NordKey")
	_, _ = windows.MessageBox(0, t, c, flags)
}

func fatalf(f string, a ...any) {
	msgBox(fmt.Sprintf(f, a...), true)
	os.Exit(1)
}
