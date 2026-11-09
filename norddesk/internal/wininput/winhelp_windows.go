//go:build windows

package wininput

import "syscall"

type dll struct{ d *syscall.LazyDLL }

func lazyDLL(name string) dll { return dll{syscall.NewLazyDLL(name)} }

func (d dll) proc(name string) proc { return proc{d.d.NewProc(name)} }

type proc struct{ p *syscall.LazyProc }

func (p proc) call(a ...uintptr) (uintptr, uintptr, error) { return p.p.Call(a...) }
