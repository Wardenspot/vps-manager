//go:build !windows

package engine

import (
	"sync"
	"time"

	"nordkey/win/internal/rules"
)

// Имитация ядра для разработки и проверки интерфейса не на Windows.
type fake struct {
	mu    sync.Mutex
	st    Status
	r     *rules.Rules
	since time.Time
	Fail  string
}

func New(logf func(string, ...any)) Engine { return &fake{st: Status{State: "off"}} }

func (f *fake) Up(c Config, r *rules.Rules, mode string) error {
	if err := c.Validate(); err != nil {
		return err
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	vpn, direct := Plan(mode, r, c)
	ok := true
	f.r, f.since = r, time.Now()
	f.st = Status{State: "on", Mode: mode, IP: c.IP, Since: f.since.Unix(), Routes: len(vpn) + len(direct), Rules: r.Version(), DNSOK: &ok}
	return nil
}

func (f *fake) Down() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.st = Status{State: "off"}
	return nil
}

func (f *fake) SetRules(r *rules.Rules, mode string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.st.State != "on" {
		return nil
	}
	f.r, f.st.Mode, f.st.Rules = r, mode, r.Version()
	return nil
}

func (f *fake) Status() Status {
	f.mu.Lock()
	defer f.mu.Unlock()
	s := f.st
	if s.State == "on" {
		age := time.Since(f.since)
		s.Handshake = int64(age.Seconds())%120 + 1
		s.RX, s.TX = int64(age.Seconds())*180000, int64(age.Seconds())*25000
	}
	return s
}

func (f *fake) Cleanup() {}
