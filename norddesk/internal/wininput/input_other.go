//go:build !windows

// Заглушка для сборки на не-Windows (ввод внедряется только на хосте-Windows).
package wininput

import "errors"

var errUnsupported = errors.New("wininput: доступно только на Windows")

func MouseMove(x, y int)                {}
func MouseButton(button int, down bool) {}
func MouseWheel(delta int)              {}
func Key(vk uint16, down bool)          {}
func SendCombo(name string) error       { return errUnsupported }
func SetClipboardText(s string) error   { return errUnsupported }
func GetClipboardText() (string, error) { return "", errUnsupported }
