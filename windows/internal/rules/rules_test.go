package rules

import "testing"

func sample() *Rules {
	return New(Data{
		Version:       "v1",
		VPNDomains:    []string{"instagram.com", "tiktokcdn-eu.com", "googlevideo.com", "kimi.com"},
		DirectDomains: []string{"vk.com", "avito.st", "yastatic.net"},
		RUTLDs:        []string{"ru", "su", "xn--p1ai", "рф"},
		VPNNets:       []string{"31.13.64.0/18", "bad", "2001:db8::/32"},
		CustomVPN:     []string{"igg.com", "special.vk.com"},
		CustomDirect:  []string{"cdn.instagram.com"},
	})
}

func TestMatch(t *testing.T) {
	r := sample()
	cases := []struct {
		name string
		want Decision
	}{
		{"instagram.com", VPN},
		{"www.instagram.com.", VPN},
		{"scontent.cdn.instagram.com", Direct}, // свой «напрямую» глубже — главнее
		{"v19-ies-music.tiktokcdn-eu.com", VPN},
		{"vk.com", Direct},
		{"api.vk.com", Direct},
		{"special.vk.com", VPN}, // свой сайт клиента глубже
		{"avito.ru", Direct},    // российская зона
		{"сайт.xn--p1ai", Direct},
		{"castleclash-eu.igg.com", VPN},
		{"example.org", None},
		{"", None},
	}
	for _, c := range cases {
		if got, _ := r.Match(c.name); got != c.want {
			t.Errorf("Match(%q) = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestSameLevelPriority(t *testing.T) {
	r := New(Data{VPNDomains: []string{"x.com"}, DirectDomains: []string{"x.com"}, CustomVPN: []string{"y.com"}})
	if d, _ := r.Match("a.x.com"); d != Direct {
		t.Errorf("на одном уровне «напрямую» главнее «через VPN», got %v", d)
	}
}

func TestRouteModes(t *testing.T) {
	r := sample()
	tests := []struct {
		name, mode string
		want       Decision
	}{
		{"instagram.com", ModeSmart, VPN},
		{"example.org", ModeSmart, Direct},
		{"vk.com", ModeSmart, Direct},
		{"example.org", ModeExceptRU, VPN},
		{"vk.com", ModeExceptRU, Direct},
		{"gosuslugi.ru", ModeExceptRU, Direct},
		{"gosuslugi.ru", ModeAll, VPN},
	}
	for _, c := range tests {
		if got := r.Route(c.name, c.mode); got != c.want {
			t.Errorf("Route(%q, %s) = %v, want %v", c.name, c.mode, got, c.want)
		}
	}
}

func TestNetsAndDNS(t *testing.T) {
	r := sample()
	if len(r.Nets()) != 1 || r.Nets()[0].String() != "31.13.64.0/18" {
		t.Errorf("nets = %v", r.Nets())
	}
	if r.DNSVPN().String() != "8.8.8.8" || r.DNSDirect().String() != "77.88.8.8" {
		t.Errorf("dns defaults: %v %v", r.DNSVPN(), r.DNSDirect())
	}
}

func TestLocal(t *testing.T) {
	for _, n := range []string{"router", "my.keenetic.io", "printer.local", "1.168.192.in-addr.arpa", "5.20.172.in-addr.arpa", "x.lan"} {
		if !Local(n) {
			t.Errorf("Local(%q) должно быть true", n)
		}
	}
	for _, n := range []string{"google.com", "8.8.8.8.in-addr.arpa", "5.40.172.in-addr.arpa"} {
		if Local(n) {
			t.Errorf("Local(%q) должно быть false", n)
		}
	}
}

func TestParse(t *testing.T) {
	r, err := Parse([]byte(`{"version":"ab","vpn_domains":["a.com"],"ru_tlds":["ru"],"vpn_nets":["1.2.3.0/24"],"dns_vpn":"1.1.1.1"}`))
	if err != nil || r.Version() != "ab" || r.DNSVPN().String() != "1.1.1.1" {
		t.Fatalf("parse: %v %v", r, err)
	}
	if d, _ := r.Match("x.a.com"); d != VPN {
		t.Error("a.com")
	}
}
