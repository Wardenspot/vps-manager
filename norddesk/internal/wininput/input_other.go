//go:build !windows

// Заглушка для сборки на не-Windows (ввод внедряется только на Windows).
package wininput

import "errors"

var errUnsupported = errors.New("wininput: доступно только на Windows")

func MouseMove(x, y int)                     {}
func MouseButton(button int, down bool)      {}
func MouseWheel(delta int)                   {}
func Key(vk uint16, down, ext bool)          {}
func SendCombo(name string) error            { return errUnsupported }
func ClipboardSeq() uint32                   { return 0 }
func SetClipboardText(s string) error        { return errUnsupported }
func GetClipboardText() (string, error)      { return "", errUnsupported }
func GetClipboardFiles() ([]string, error)   { return nil, errUnsupported }
func SetClipboardFiles(paths []string) error { return errUnsupported }
