//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"

	"norddesk/internal/winsys"
)

// launchUI открывает окно как HTML-приложение в Edge (режим --app), как у
// NordKey.exe; если Edge нет — Chrome, затем системный обработчик ссылок.
//
// Если NordDesk работает с правами администратора, окно запускается с обычными
// правами (от имени Проводника): в «админское» окно Windows не даёт
// перетаскивать файлы из обычного Проводника (UIPI).
func launchUI(url string) error {
	candidates := []string{
		filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(os.Getenv("ProgramFiles"), "Google", "Chrome", "Application", "chrome.exe"),
		filepath.Join(os.Getenv("ProgramFiles(x86)"), "Google", "Chrome", "Application", "chrome.exe"),
		filepath.Join(os.Getenv("LocalAppData"), "Google", "Chrome", "Application", "chrome.exe"),
	}
	for _, exe := range candidates {
		if _, err := os.Stat(exe); err != nil {
			continue
		}
		args := []string{"--app=" + url, "--window-size=1280,800", "--disable-features=Translate"}
		if winsys.IsElevated() {
			if err := startAsShellChild(exe, args); err == nil {
				return nil
			}
		}
		return exec.Command(exe, args...).Start()
	}
	return exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
}

var (
	opUser32                      = syscall.NewLazyDLL("user32.dll")
	opKernel32                    = syscall.NewLazyDLL("kernel32.dll")
	procGetShellWindow            = opUser32.NewProc("GetShellWindow")
	procGetWindowThreadProcessId  = opUser32.NewProc("GetWindowThreadProcessId")
	procInitProcThreadAttrList    = opKernel32.NewProc("InitializeProcThreadAttributeList")
	procUpdateProcThreadAttribute = opKernel32.NewProc("UpdateProcThreadAttribute")
	procDeleteProcThreadAttrList  = opKernel32.NewProc("DeleteProcThreadAttributeList")
)

const (
	processCreateProcess        = 0x0080
	procThreadAttrParentProcess = 0x00020000
	extendedStartupInfoPresent  = 0x00080000
)

type startupInfoEx struct {
	syscall.StartupInfo
	attrList uintptr
}

// startAsShellChild запускает программу «дочерней» к Проводнику: она получает
// его обычные (не повышенные) права.
func startAsShellChild(exe string, args []string) error {
	shell, _, _ := procGetShellWindow.Call()
	if shell == 0 {
		return syscall.EINVAL
	}
	var pid uint32
	procGetWindowThreadProcessId.Call(shell, uintptr(unsafe.Pointer(&pid)))
	parent, err := syscall.OpenProcess(processCreateProcess, false, pid)
	if err != nil {
		return err
	}
	defer syscall.CloseHandle(parent)

	var size uintptr
	procInitProcThreadAttrList.Call(0, 1, 0, uintptr(unsafe.Pointer(&size)))
	attr := make([]byte, size)
	if r, _, e := procInitProcThreadAttrList.Call(uintptr(unsafe.Pointer(&attr[0])), 1, 0, uintptr(unsafe.Pointer(&size))); r == 0 {
		return e
	}
	defer procDeleteProcThreadAttrList.Call(uintptr(unsafe.Pointer(&attr[0])))
	if r, _, e := procUpdateProcThreadAttribute.Call(uintptr(unsafe.Pointer(&attr[0])), 0,
		procThreadAttrParentProcess, uintptr(unsafe.Pointer(&parent)), unsafe.Sizeof(parent), 0, 0); r == 0 {
		return e
	}

	si := startupInfoEx{attrList: uintptr(unsafe.Pointer(&attr[0]))}
	si.Cb = uint32(unsafe.Sizeof(si))
	cmdline := syscall.EscapeArg(exe)
	for _, a := range args {
		cmdline += " " + syscall.EscapeArg(a)
	}
	cl, _ := syscall.UTF16PtrFromString(cmdline)
	var pi syscall.ProcessInformation
	if err := syscall.CreateProcess(nil, cl, nil, nil, false, extendedStartupInfoPresent, nil, nil, &si.StartupInfo, &pi); err != nil {
		return err
	}
	syscall.CloseHandle(pi.Thread)
	syscall.CloseHandle(pi.Process)
	return nil
}
