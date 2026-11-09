//go:build !windows

// Заглушка для сборки на не-Windows платформах (хост работает только на Windows).
package screen

import (
	"errors"

	"norddesk/internal/wire"
)

var errUnsupported = errors.New("screen: захват экрана доступен только на Windows")

type Capturer struct{}

func EnsureDPIAware() {}

func New() (*Capturer, error) { return nil, errUnsupported }

func (c *Capturer) Size() (int, int)              { return 0, 0 }
func (c *Capturer) Reset()                        {}
func (c *Capturer) Grab(int) ([]wire.Tile, error) { return nil, errUnsupported }
func (c *Capturer) Close() error                  { return nil }
