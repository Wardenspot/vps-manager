//go:build !windows

package main

// На не-Windows перехватчика нет: клавиши передаёт само окно (кроме системных).
type keyHook struct{}

func newKeyHook(onKey func(keyEvent)) *keyHook { return &keyHook{} }
func (h *keyHook) setActive(on bool)           {}
func (h *keyHook) stop()                       {}
