// Package rules решает, как идёт сайт: через VPN или напрямую — «как на роутерах NordKey».
//
// Списки приходят с сервера (/v1/pc/routes). Совпадение ищется по самому длинному суффиксу домена
// (mail.google.com → google.com → com). На одном уровне главнее свои сайты клиента, потом «напрямую», потом «через VPN».
// Ничего не нашлось — российская зона (.ru, .рф…) идёт напрямую, остальное — как задано режимом.
package rules

import (
	"encoding/json"
	"net/netip"
	"strings"
)

// Решение по домену.
type Decision int

const (
	None   Decision = iota // в списках нет — решает режим
	VPN                    // через VPN
	Direct                 // напрямую
)

func (d Decision) String() string {
	switch d {
	case VPN:
		return "vpn"
	case Direct:
		return "direct"
	}
	return "none"
}

// Режимы маршрутизации.
const (
	ModeSmart    = "smart"     // как на роутерах: через VPN только заблокированное
	ModeExceptRU = "except_ru" // всё через VPN, кроме российского
	ModeAll      = "all"       // всё через VPN
)

func ValidMode(m string) bool { return m == ModeSmart || m == ModeExceptRU || m == ModeAll }

// Data — ответ сервера /v1/pc/routes.
type Data struct {
	Version       string   `json:"version"`
	VPNDomains    []string `json:"vpn_domains"`
	DirectDomains []string `json:"direct_domains"`
	RUTLDs        []string `json:"ru_tlds"`
	VPNNets       []string `json:"vpn_nets"`
	CustomVPN     []string `json:"custom_vpn"`
	CustomDirect  []string `json:"custom_direct"`
	DNSVPN        string   `json:"dns_vpn"`
	DNSDirect     string   `json:"dns_direct"`
}

const (
	kVPN = 1 << iota
	kDirect
	kCustomVPN
	kCustomDirect
)

// Rules — готовые к поиску списки.
type Rules struct {
	data  Data
	names map[string]uint8
	tlds  map[string]bool
	nets  []netip.Prefix
}

func normalize(name string) string {
	n := strings.ToLower(strings.TrimSpace(name))
	n = strings.TrimSuffix(n, ".")
	n = strings.TrimPrefix(n, "*.")
	return n
}

// New строит правила из ответа сервера.
func New(d Data) *Rules {
	r := &Rules{data: d, names: map[string]uint8{}, tlds: map[string]bool{}}
	add := func(list []string, k uint8) {
		for _, n := range list {
			if n = normalize(n); n != "" {
				r.names[n] |= k
			}
		}
	}
	add(d.VPNDomains, kVPN)
	add(d.DirectDomains, kDirect)
	add(d.CustomVPN, kCustomVPN)
	add(d.CustomDirect, kCustomDirect)
	for _, t := range d.RUTLDs {
		if t = normalize(strings.TrimPrefix(t, ".")); t != "" {
			r.tlds[t] = true
		}
	}
	for _, s := range d.VPNNets {
		if p, err := netip.ParsePrefix(strings.TrimSpace(s)); err == nil && p.Addr().Is4() {
			r.nets = append(r.nets, p.Masked())
		}
	}
	return r
}

// Parse — из JSON сервера.
func Parse(b []byte) (*Rules, error) {
	var d Data
	if err := json.Unmarshal(b, &d); err != nil {
		return nil, err
	}
	return New(d), nil
}

func (r *Rules) Data() Data            { return r.data }
func (r *Rules) Version() string       { return r.data.Version }
func (r *Rules) Nets() []netip.Prefix  { return r.nets }
func (r *Rules) DNSVPN() netip.Addr    { return addrOr(r.data.DNSVPN, "8.8.8.8") }
func (r *Rules) DNSDirect() netip.Addr { return addrOr(r.data.DNSDirect, "77.88.8.8") }
func (r *Rules) Counts() (int, int, int) {
	return len(r.data.VPNDomains), len(r.data.DirectDomains), len(r.nets)
}

func addrOr(s, def string) netip.Addr {
	if a, err := netip.ParseAddr(s); err == nil && a.Is4() {
		return a
	}
	return netip.MustParseAddr(def)
}

// Match — решение по спискам (без учёта режима) и запись списка, которая сработала.
func (r *Rules) Match(name string) (Decision, string) {
	n := normalize(name)
	if n == "" {
		return None, ""
	}
	for s := n; ; {
		if k, ok := r.names[s]; ok {
			switch {
			case k&kCustomDirect != 0:
				return Direct, s
			case k&kCustomVPN != 0:
				return VPN, s
			case k&kDirect != 0:
				return Direct, s
			case k&kVPN != 0:
				return VPN, s
			}
		}
		i := strings.IndexByte(s, '.')
		if i < 0 {
			break
		}
		s = s[i+1:]
	}
	if i := strings.LastIndexByte(n, '.'); i >= 0 && r.tlds[n[i+1:]] {
		return Direct, "." + n[i+1:]
	}
	if r.tlds[n] {
		return Direct, "." + n
	}
	return None, ""
}

// Route — куда в итоге идёт домен в этом режиме.
func (r *Rules) Route(name, mode string) Decision {
	d, _ := r.Match(name)
	switch mode {
	case ModeAll:
		return VPN
	case ModeExceptRU:
		if d == Direct {
			return Direct
		}
		return VPN
	default: // smart
		if d == VPN {
			return VPN
		}
		return Direct
	}
}

// Local — имя из домашней сети (роутер, принтер): его спрашиваем у DNS провайдера/роутера, а не у Google.
func Local(name string) bool {
	n := normalize(name)
	if n == "" || !strings.Contains(n, ".") {
		return true
	}
	for _, s := range []string{".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa", ".keenetic.io", ".netcraze.io"} {
		if strings.HasSuffix(n, s) {
			return true
		}
	}
	if strings.HasSuffix(n, ".in-addr.arpa") {
		// обратные запросы для частных сетей
		for _, p := range []string{".10.in-addr.arpa", ".168.192.in-addr.arpa", ".127.in-addr.arpa", ".254.169.in-addr.arpa"} {
			if strings.HasSuffix(n, p) {
				return true
			}
		}
		for i := 16; i <= 31; i++ {
			if strings.HasSuffix(n, "."+itoa(i)+".172.in-addr.arpa") {
				return true
			}
		}
	}
	return false
}

func itoa(i int) string {
	if i < 10 {
		return string(rune('0' + i))
	}
	return itoa(i/10) + string(rune('0'+i%10))
}
