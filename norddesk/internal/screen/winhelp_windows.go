//go:build windows

package screen

import "syscall"

// Тонкие обёртки над syscall.LazyDLL/LazyProc, чтобы вызовы WinAPI читались
// компактно. Внешних зависимостей не требуется.

type dll struct{ d *syscall.LazyDLL }

func lazyDLL(name string) dll { return dll{syscall.NewLazyDLL(name)} }

func (d dll) proc(name string) proc { return proc{d.d.NewProc(name)} }

type proc struct{ p *syscall.LazyProc }

func (p proc) call(a ...uintptr) (uintptr, uintptr, error) { return p.p.Call(a...) }
