// Package engine — VPN-ядро NordKey на ПК: туннель AmneziaWG, свои маршруты (как на роутерах), локальный DNS.
//
// Общая часть (здесь) — конфиг, план маршрутов для режима и таблица маршрутов от DNS — проверяется тестами на любой ОС.
// Работа с Windows (wintun, маршруты, NRPT) — в engine_windows.go; на других ОС — имитация для разработки (engine_fake.go).
package engine

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"sort"
	"strings"
	"sync"
	"time"

	"nordkey/win/internal/rules"
)

// Config — конфиг ПК с сервера (/v1/pc/connect).
type Config struct {
	IP              string         `json:"ip"`
	Address         string         `json:"address"`
	PrivateKey      string         `json:"private_key"`
	PresharedKey    string         `json:"preshared_key"`
	ServerPublicKey string         `json:"server_public_key"`
	Endpoint        string         `json:"endpoint"`
	MTU             int            `json:"mtu"`
	DNSVPN          string         `json:"dns_vpn"`
	DNSDirect       string         `json:"dns_direct"`
	AWG             map[string]int `json:"awg"`
}

func (c Config) Validate() error {
	if _, err := netip.ParsePrefix(c.Address); err != nil {
		return fmt.Errorf("адрес: %w", err)
	}
	if _, err := netip.ParseAddrPort(c.Endpoint); err != nil {
		return fmt.Errorf("сервер: %w", err)
	}
	for _, k := range []string{c.PrivateKey, c.ServerPublicKey} {
		if b, err := base64.StdEncoding.DecodeString(k); err != nil || len(b) != 32 {
			return errors.New("неверный ключ в конфиге")
		}
	}
	if c.PresharedKey != "" {
		if b, err := base64.StdEncoding.DecodeString(c.PresharedKey); err != nil || len(b) != 32 {
			return errors.New("неверный общий ключ в конфиге")
		}
	}
	return nil
}

func (c Config) Prefix() netip.Prefix       { p, _ := netip.ParsePrefix(c.Address); return p }
func (c Config) EndpointAP() netip.AddrPort { a, _ := netip.ParseAddrPort(c.Endpoint); return a }
func (c Config) Mtu() int {
	if c.MTU >= 1200 && c.MTU <= 1500 {
		return c.MTU
	}
	return 1380
}

func b64hex(s string) string {
	b, _ := base64.StdEncoding.DecodeString(s)
	return hex.EncodeToString(b)
}

// UAPI — настройка устройства amneziawg-go. Пир принимает всё (0.0.0.0/0): что именно пускать в туннель,
// решают маршруты Windows, а не AllowedIPs.
func (c Config) UAPI() string {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", b64hex(c.PrivateKey))
	keys := make([]string, 0, len(c.AWG))
	for k := range c.AWG {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		switch k {
		case "jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4":
			fmt.Fprintf(&b, "%s=%d\n", k, c.AWG[k])
		}
	}
	b.WriteString("replace_peers=true\n")
	fmt.Fprintf(&b, "public_key=%s\n", b64hex(c.ServerPublicKey))
	if c.PresharedKey != "" {
		fmt.Fprintf(&b, "preshared_key=%s\n", b64hex(c.PresharedKey))
	}
	fmt.Fprintf(&b, "endpoint=%s\n", c.Endpoint)
	b.WriteString("persistent_keepalive_interval=25\nreplace_allowed_ips=true\nallowed_ip=0.0.0.0/0\n")
	return b.String()
}

// Plan — постоянные маршруты режима: через туннель и напрямую (через шлюз провайдера).
func Plan(mode string, r *rules.Rules, c Config) (vpn, direct []netip.Prefix) {
	ep := c.EndpointAP().Addr()
	if ep.IsValid() {
		direct = append(direct, netip.PrefixFrom(ep, 32)) // сам сервер — всегда напрямую, иначе туннель замкнётся на себя
	}
	dnsVPN, dnsDirect := r.DNSVPN(), r.DNSDirect()
	switch mode {
	case rules.ModeExceptRU, rules.ModeAll:
		vpn = append(vpn, netip.MustParsePrefix("0.0.0.0/1"), netip.MustParsePrefix("128.0.0.0/1"))
		direct = append(direct, netip.PrefixFrom(dnsDirect, 32))
	default:
		vpn = append(vpn, netip.PrefixFrom(dnsVPN, 32))
		vpn = append(vpn, r.Nets()...)
	}
	return vpn, direct
}

// Status — что показывать в приложении.
type Status struct {
	State      string `json:"state"` // off | connecting | on | error
	Mode       string `json:"mode"`
	IP         string `json:"ip,omitempty"`
	Handshake  int64  `json:"handshake_ago,omitempty"` // секунд назад, 0 — ещё не было
	RX         int64  `json:"rx"`
	TX         int64  `json:"tx"`
	Error      string `json:"error,omitempty"`
	Since      int64  `json:"since,omitempty"`
	Routes     int    `json:"routes"`
	DynRoutes  int    `json:"dyn_routes"`
	DNSQueries uint64 `json:"dns_queries"`
	DNSOK      *bool  `json:"dns_ok,omitempty"` // перехватываются ли запросы Windows (проверка после подключения)
	Rules      string `json:"rules_version,omitempty"`
}

// Engine — что умеет ядро.
type Engine interface {
	Up(c Config, r *rules.Rules, mode string) error
	Down() error
	SetRules(r *rules.Rules, mode string) error // новые списки или режим без переподключения
	Status() Status
	Cleanup() // убрать следы прошлого запуска (после сбоя)
}

// ───────── маршруты от DNS ─────────

// RouteOps — добавить/убрать маршрут /32 через туннель (vpn=true) или напрямую.
type RouteOps interface {
	Add(p netip.Prefix, vpn bool) error
	Del(p netip.Prefix, vpn bool) error
}

type dynEntry struct {
	vpn   bool
	until time.Time
}

// Dyn — маршруты /32, которые добавил DNS: живут TTL записи, но не меньше 30 минут и не больше суток.
type Dyn struct {
	mu    sync.Mutex
	ops   RouteOps
	items map[netip.Prefix]dynEntry
	Max   int
	Now   func() time.Time
}

func NewDyn(ops RouteOps) *Dyn {
	return &Dyn{ops: ops, items: map[netip.Prefix]dynEntry{}, Max: 20000, Now: time.Now}
}

func (d *Dyn) Len() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.items)
}

// Ensure — адрес должен идти так (vpn или напрямую). Возвращает true, если маршрут добавлен/изменён.
func (d *Dyn) Ensure(ip netip.Addr, vpn bool, ttl uint32) bool {
	if !ip.Is4() || ip.IsPrivate() || ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || ip.IsMulticast() {
		return false
	}
	life := time.Duration(ttl) * time.Second
	if life < 30*time.Minute {
		life = 30 * time.Minute
	}
	if life > 24*time.Hour {
		life = 24 * time.Hour
	}
	p := netip.PrefixFrom(ip, 32)
	d.mu.Lock()
	defer d.mu.Unlock()
	until := d.Now().Add(life)
	if e, ok := d.items[p]; ok {
		if e.vpn == vpn {
			if until.After(e.until) {
				e.until = until
				d.items[p] = e
			}
			return false
		}
		_ = d.ops.Del(p, e.vpn)
		delete(d.items, p)
	}
	if len(d.items) >= d.Max {
		d.expireLocked(d.Now().Add(time.Hour)) // освобождаем место: всё, что скоро истечёт
		if len(d.items) >= d.Max {
			return false
		}
	}
	if err := d.ops.Add(p, vpn); err != nil {
		return false
	}
	d.items[p] = dynEntry{vpn: vpn, until: until}
	return true
}

func (d *Dyn) expireLocked(now time.Time) int {
	n := 0
	for p, e := range d.items {
		if now.After(e.until) {
			_ = d.ops.Del(p, e.vpn)
			delete(d.items, p)
			n++
		}
	}
	return n
}

// Expire — убрать истёкшие.
func (d *Dyn) Expire() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.expireLocked(d.Now())
}

// Clear — убрать все (отключение, смена режима).
func (d *Dyn) Clear() {
	d.mu.Lock()
	defer d.mu.Unlock()
	for p, e := range d.items {
		_ = d.ops.Del(p, e.vpn)
	}
	d.items = map[netip.Prefix]dynEntry{}
}

// Hook — что делать с ответом DNS в этом режиме: в «умном» — адреса сайтов через VPN в туннель;
// в «всё, кроме российского» — российские адреса напрямую; в «всё через VPN» — ничего (и так всё в туннеле).
func Hook(d *Dyn, mode func() string) func(name string, ips []netip.Addr, ttl uint32, route rules.Decision) {
	return func(name string, ips []netip.Addr, ttl uint32, route rules.Decision) {
		m := mode()
		for _, ip := range ips {
			switch {
			case m == rules.ModeSmart && route == rules.VPN:
				d.Ensure(ip, true, ttl)
			case m == rules.ModeExceptRU && route == rules.Direct:
				d.Ensure(ip, false, ttl)
			}
		}
	}
}

// ParseIpc — рукопожатие и трафик из ответа IpcGet.
func ParseIpc(s string, now time.Time) (handshakeAgo, rx, tx int64) {
	var hs int64
	for _, l := range strings.Split(s, "\n") {
		k, v, ok := strings.Cut(l, "=")
		if !ok {
			continue
		}
		var n int64
		fmt.Sscan(v, &n)
		switch k {
		case "last_handshake_time_sec":
			if n > hs {
				hs = n
			}
		case "rx_bytes":
			rx += n
		case "tx_bytes":
			tx += n
		}
	}
	if hs > 0 {
		handshakeAgo = now.Unix() - hs
		if handshakeAgo < 1 {
			handshakeAgo = 1
		}
	}
	return
}
