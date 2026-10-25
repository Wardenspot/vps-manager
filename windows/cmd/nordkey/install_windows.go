//go:build windows

package main

import (
	_ "embed"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"

	"nordkey/win/internal/app"
	"nordkey/win/internal/engine"
)

// Драйвер-адаптер WireGuard для Windows (wintun.dll, подписан WireGuard LLC). Кладётся рядом с NordKey.exe.
//
//go:embed wintun.dll
var wintunDLL []byte

const uninstallKey = `SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\NordKey`

func isAdmin() bool { return windows.GetCurrentProcessToken().IsElevated() }

// ───────── запуск с правами администратора и ожиданием ─────────

type shellExecuteInfo struct {
	cbSize       uint32
	fMask        uint32
	hwnd         uintptr
	lpVerb       *uint16
	lpFile       *uint16
	lpParameters *uint16
	lpDirectory  *uint16
	nShow        int32
	hInstApp     uintptr
	lpIDList     uintptr
	lpClass      *uint16
	hkeyClass    uintptr
	dwHotKey     uint32
	hIcon        uintptr
	hProcess     windows.Handle
}

var procShellExecuteEx = windows.NewLazySystemDLL("shell32.dll").NewProc("ShellExecuteExW")

// runElevated — запустить себя с аргументом от имени администратора (окно UAC) и дождаться. Код выхода.
func runElevated(arg string) (uint32, error) {
	exe, _ := os.Executable()
	verb, _ := windows.UTF16PtrFromString("runas")
	file, _ := windows.UTF16PtrFromString(exe)
	params, _ := windows.UTF16PtrFromString(arg)
	dir, _ := windows.UTF16PtrFromString(filepath.Dir(exe))
	info := shellExecuteInfo{fMask: 0x40 | 0x100, lpVerb: verb, lpFile: file, lpParameters: params, lpDirectory: dir, nShow: windows.SW_HIDE}
	info.cbSize = uint32(unsafe.Sizeof(info))
	if r, _, err := procShellExecuteEx.Call(uintptr(unsafe.Pointer(&info))); r == 0 {
		if errors.Is(err, windows.ERROR_CANCELLED) {
			return 0, errors.New("установка отменена — без прав администратора VPN не поставить")
		}
		return 0, err
	}
	if info.hProcess == 0 {
		return 0, nil
	}
	defer windows.CloseHandle(info.hProcess)
	_, _ = windows.WaitForSingleObject(info.hProcess, windows.INFINITE)
	var code uint32
	_ = windows.GetExitCodeProcess(info.hProcess, &code)
	return code, nil
}

func hidden(name string, args ...string) error {
	c := exec.Command(name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
	out, err := c.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s: %v %s", name, err, strings.TrimSpace(string(out)))
	}
	return nil
}

// ───────── установка ─────────

func stopService(s *mgr.Service) {
	st, err := s.Query()
	if err != nil || st.State == svc.Stopped {
		return
	}
	_, _ = s.Control(svc.Stop)
	for i := 0; i < 40; i++ {
		if st, err := s.Query(); err != nil || st.State == svc.Stopped {
			return
		}
		time.Sleep(250 * time.Millisecond)
	}
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".new"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	_ = os.Remove(dst + ".old")
	_ = os.Rename(dst, dst+".old") // работающий файл переименовать можно, удалить — нет
	return os.Rename(tmp, dst)
}

func install() error {
	if !isAdmin() {
		return errors.New("нужны права администратора")
	}
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("службы Windows: %w", err)
	}
	defer m.Disconnect()
	s, err := m.OpenService(svcName)
	if err == nil {
		stopService(s)
	}
	if err := os.MkdirAll(installDir(), 0o755); err != nil {
		return err
	}
	self, _ := os.Executable()
	exe := installedExe()
	if !strings.EqualFold(filepath.Clean(self), filepath.Clean(exe)) {
		if err := copyFile(self, exe); err != nil {
			return fmt.Errorf("копирование в %s: %w", installDir(), err)
		}
	}
	dll := filepath.Join(installDir(), "wintun.dll")
	if err := os.WriteFile(dll+".new", wintunDLL, 0o644); err != nil {
		return err
	}
	_ = os.Remove(dll + ".old")
	_ = os.Rename(dll, dll+".old")
	if err := os.Rename(dll+".new", dll); err != nil {
		return err
	}
	cfg := mgr.Config{
		DisplayName:  "NordKey VPN",
		Description:  "VPN NordKey для этого компьютера: маршрутизация сайтов как на роутерах NordKey.",
		StartType:    mgr.StartAutomatic,
		ServiceType:  windows.SERVICE_WIN32_OWN_PROCESS,
		ErrorControl: mgr.ErrorNormal,
		Dependencies: []string{"Nsi", "TcpIp"},
	}
	if s == nil {
		if s, err = m.CreateService(svcName, exe, cfg); err != nil {
			return fmt.Errorf("создание службы: %w", err)
		}
	} else {
		cur, _ := s.Config()
		cur.BinaryPathName = `"` + exe + `"`
		cur.DisplayName, cur.Description, cur.StartType = cfg.DisplayName, cfg.Description, cfg.StartType
		if err := s.UpdateConfig(cur); err != nil {
			return fmt.Errorf("обновление службы: %w", err)
		}
	}
	defer s.Close()
	_ = s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: 3 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 10 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 60 * time.Second},
	}, 86400)
	if err := s.Start(); err != nil && !errors.Is(err, windows.ERROR_SERVICE_ALREADY_RUNNING) {
		return fmt.Errorf("запуск службы: %w", err)
	}
	// Разрешить службе принимать DNS-запросы Windows на адресе туннеля
	_ = hidden("netsh", "advfirewall", "firewall", "delete", "rule", "name=NordKey")
	_ = hidden("netsh", "advfirewall", "firewall", "add", "rule", "name=NordKey", "dir=in", "action=allow",
		"program="+exe, "enable=yes", "profile=any")
	shortcuts(exe)
	if k, _, err := registry.CreateKey(registry.LOCAL_MACHINE, uninstallKey, registry.ALL_ACCESS); err == nil {
		_ = k.SetStringValue("DisplayName", "NordKey VPN")
		_ = k.SetStringValue("DisplayVersion", version)
		_ = k.SetStringValue("Publisher", "NordKey")
		_ = k.SetStringValue("DisplayIcon", exe)
		_ = k.SetStringValue("InstallLocation", installDir())
		_ = k.SetStringValue("UninstallString", `"`+exe+`" uninstall`)
		_ = k.SetDWordValue("NoModify", 1)
		_ = k.SetDWordValue("NoRepair", 1)
		_ = k.SetDWordValue("EstimatedSize", uint32(fileSize(exe)/1024+200))
		k.Close()
	}
	return nil
}

func fileSize(p string) int64 {
	if st, err := os.Stat(p); err == nil {
		return st.Size()
	}
	return 0
}

func shortcutPaths() []string {
	pub := envOr("PUBLIC", `C:\Users\Public`)
	return []string{
		filepath.Join(programData(), `Microsoft\Windows\Start Menu\Programs\NordKey.lnk`),
		filepath.Join(pub, `Desktop\NordKey.lnk`),
	}
}

func shortcuts(exe string) {
	for _, p := range shortcutPaths() {
		ps := fmt.Sprintf("$s=(New-Object -ComObject WScript.Shell).CreateShortcut('%s');$s.TargetPath='%s';"+
			"$s.WorkingDirectory='%s';$s.IconLocation='%s,0';$s.Description='NordKey VPN';$s.Save()",
			psq(p), psq(exe), psq(filepath.Dir(exe)), psq(exe))
		_ = hidden("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", ps)
	}
}

func psq(s string) string { return strings.ReplaceAll(s, "'", "''") }

// ───────── удаление ─────────

func uninstallCmd() {
	if !isAdmin() {
		if _, err := runElevated("uninstall"); err != nil {
			msgBox(err.Error(), true)
		}
		return
	}
	if err := uninstall(); err != nil {
		msgBox("Удаление NordKey: "+err.Error(), true)
		return
	}
	msgBox("NordKey удалён с этого компьютера.", false)
}

func uninstall() error {
	if m, err := mgr.Connect(); err == nil {
		if s, err := m.OpenService(svcName); err == nil {
			stopService(s)
			_ = s.Delete()
			s.Close()
		}
		m.Disconnect()
	}
	engine.New(nil).Cleanup() // на всякий случай: перехват DNS точно убран
	_ = hidden("netsh", "advfirewall", "firewall", "delete", "rule", "name=NordKey")
	for _, p := range shortcutPaths() {
		_ = os.Remove(p)
	}
	_ = registry.DeleteKey(registry.LOCAL_MACHINE, uninstallKey)
	_ = os.RemoveAll(dataDir())
	// Папку с программой удалим через пару секунд, когда этот процесс завершится
	c := exec.Command("cmd", "/c", "ping -n 3 127.0.0.1 >nul & rmdir /s /q \""+installDir()+"\"")
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW | windows.DETACHED_PROCESS}
	return c.Start()
}

// ───────── обычный запуск: установка при необходимости и окно ─────────

func installedVersion() string {
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, uninstallKey, registry.QUERY_VALUE)
	if err != nil {
		return ""
	}
	defer k.Close()
	v, _, _ := k.GetStringValue("DisplayVersion")
	return v
}

func serviceUp() bool {
	c, err := net.DialTimeout("tcp", app.Addr, 500*time.Millisecond)
	if err != nil {
		return false
	}
	c.Close()
	return true
}

func launcher() {
	self, _ := os.Executable()
	isInstalled := fileSize(installedExe()) > 0 && installedVersion() != ""
	fromInstall := strings.EqualFold(filepath.Clean(self), filepath.Clean(installedExe()))
	if !isInstalled || (!fromInstall && installedVersion() != version) {
		code, err := runElevated("install")
		if err != nil {
			fatalf("%v", err)
		}
		if code != 0 {
			os.Exit(1) // ошибку уже показал установщик
		}
	} else if !serviceUp() {
		// служба остановлена (кто-то выключил) — запустить её может только администратор
		if m, err := mgr.Connect(); err == nil {
			if s, err := m.OpenService(svcName); err == nil {
				_ = s.Start()
				s.Close()
			}
			m.Disconnect()
		}
		if !waitUp(3 * time.Second) {
			if code, err := runElevated("install"); err != nil || code != 0 {
				fatalf("Служба NordKey не запущена. %v", err)
			}
		}
	}
	if !waitUp(20 * time.Second) {
		fatalf("Служба NordKey не отвечает. Перезагрузите компьютер или переустановите NordKey.")
	}
	var key []byte
	for i := 0; i < 20; i++ {
		if key, _ = os.ReadFile(keyPath()); len(key) > 0 {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if len(key) == 0 {
		fatalf("Нет доступа к NordKey: не найден ключ окна.")
	}
	openWindow("http://" + app.Addr + "/#k=" + strings.TrimSpace(string(key)))
}

func waitUp(d time.Duration) bool {
	for end := time.Now().Add(d); time.Now().Before(end); time.Sleep(300 * time.Millisecond) {
		if serviceUp() {
			return true
		}
	}
	return false
}

// openWindow — окно приложения: Edge в режиме приложения (без адресной строки), со своим профилем.
func openWindow(url string) {
	for _, base := range []string{envOr("ProgramFiles(x86)", `C:\Program Files (x86)`), programFiles(), os.Getenv("LOCALAPPDATA")} {
		edge := filepath.Join(base, `Microsoft\Edge\Application\msedge.exe`)
		if fileSize(edge) == 0 {
			continue
		}
		prof := filepath.Join(envOr("LOCALAPPDATA", os.TempDir()), "NordKey", "Window")
		c := exec.Command(edge, "--app="+url, "--user-data-dir="+prof, "--window-size=460,820",
			"--no-first-run", "--no-default-browser-check", "--disable-features=Translate,msEdgeSidebarV2", "--lang=ru")
		if c.Start() == nil {
			return
		}
	}
	// Edge нет — откроем в браузере по умолчанию
	u, _ := windows.UTF16PtrFromString(url)
	op, _ := windows.UTF16PtrFromString("open")
	_ = windows.ShellExecute(0, op, u, nil, nil, windows.SW_SHOWNORMAL)
}
