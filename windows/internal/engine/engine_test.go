package engine

import (
	"net/netip"
	"strings"
	"testing"
	"time"

	"nordkey/win/internal/rules"
)

var key = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXphYmNkZWY=" // 32 байта

func cfg() Config {
	return Config{IP: "10.66.66.5", Address: "10.66.66.5/32", PrivateKey: key, PresharedKey: key, ServerPublicKey: key,
		Endpoint: "143.246.193.103:35180", MTU: 1380, AWG: map[string]int{"jc": 4, "jmin": 40, "jmax": 70, "s1": 107, "s2": 53,
			"h1": 1, "h2": 2, "h3": 3, "h4": 4, "evil": 9}}
}

func TestValidateAndUAPI(t *testing.T) {
	c := cfg()
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	u := c.UAPI()
	for _, want := range []string{"private_key=6162636465", "jc=4\n", "h4=4\n", "s2=53\n", "replace_peers=true\npublic_key=",
		"preshared_key=", "endpoint=143.246.193.103:35180\n", "allowed_ip=0.0.0.0/0\n", "persistent_keepalive_interval=25"} {
		if !strings.Contains(u, want) {
			t.Errorf("UAPI без %q:\n%s", want, u)
		}
	}
	if strings.Contains(u, "evil") {
		t.Error("лишние параметры не передаём")
	}
	if strings.Index(u, "jc=") > strings.Index(u, "public_key=") {
		t.Error("параметры AWG — до пира")
	}
	bad := c
	bad.PrivateKey = "xx"
	if bad.Validate() == nil {
		t.Error("плохой ключ должен ловиться")
	}
	if (Config{MTU: 9000}).Mtu() != 1380 {
		t.Error("MTU по умолчанию")
	}
}

func TestPlan(t *testing.T) {
	r := rules.New(rules.Data{VPNNets: []string{"31.13.64.0/18"}})
	vpn, direct := Plan(rules.ModeSmart, r, cfg())
	if len(vpn) != 2 || vpn[0].String() != "8.8.8.8/32" || vpn[1].String() != "31.13.64.0/18" {
		t.Errorf("умный: через VPN Google DNS и сети: %v", vpn)
	}
	if len(direct) != 1 || direct[0].String() != "143.246.193.103/32" {
		t.Errorf("сервер — напрямую: %v", direct)
	}
	vpn, direct = Plan(rules.ModeExceptRU, r, cfg())
	if len(vpn) != 2 || vpn[0].String() != "0.0.0.0/1" || vpn[1].String() != "128.0.0.0/1" {
		t.Errorf("всё через VPN: %v", vpn)
	}
	if len(direct) != 2 || direct[1].String() != "77.88.8.8/32" {
		t.Errorf("Яндекс DNS напрямую: %v", direct)
	}
}

type fakeOps struct{ log []string }

func (f *fakeOps) Add(p netip.Prefix, vpn bool) error {
	f.log = append(f.log, "+"+p.String()+tag(vpn))
	return nil
}
func (f *fakeOps) Del(p netip.Prefix, vpn bool) error {
	f.log = append(f.log, "-"+p.String()+tag(vpn))
	return nil
}
func tag(v bool) string {
	if v {
		return " vpn"
	}
	return " direct"
}

func TestDyn(t *testing.T) {
	ops := &fakeOps{}
	d := NewDyn(ops)
	now := time.Unix(1_700_000_000, 0)
	d.Now = func() time.Time { return now }
	ip := netip.MustParseAddr("1.2.3.4")
	if !d.Ensure(ip, true, 60) || d.Ensure(ip, true, 60) {
		t.Error("второй раз тот же маршрут не добавляем")
	}
	if d.Ensure(netip.MustParseAddr("192.168.1.10"), true, 60) || d.Ensure(netip.MustParseAddr("127.0.0.1"), true, 60) {
		t.Error("домашнюю сеть и localhost не трогаем")
	}
	d.Ensure(ip, false, 60) // сменилось решение — переставить
	if strings.Join(ops.log, ",") != "+1.2.3.4/32 vpn,-1.2.3.4/32 vpn,+1.2.3.4/32 direct" {
		t.Errorf("log: %v", ops.log)
	}
	now = now.Add(29 * time.Minute)
	if d.Expire() != 0 {
		t.Error("меньше 30 минут не живёт короче")
	}
	now = now.Add(2 * time.Minute)
	if d.Expire() != 1 || d.Len() != 0 {
		t.Error("истёк — убран")
	}
	d.Max = 2
	d.Ensure(netip.MustParseAddr("5.5.5.1"), true, 1)
	d.Ensure(netip.MustParseAddr("5.5.5.2"), true, 86400*7)
	if !d.Ensure(netip.MustParseAddr("5.5.5.3"), true, 60) || d.Len() != 2 {
		t.Errorf("место освобождается от скоро истекающих: %d", d.Len())
	}
	d.Clear()
	if d.Len() != 0 {
		t.Error("clear")
	}
}

func TestHook(t *testing.T) {
	ops := &fakeOps{}
	d := NewDyn(ops)
	mode := rules.ModeSmart
	h := Hook(d, func() string { return mode })
	ips := []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("1.1.1.2")}
	h("instagram.com.", ips, 60, rules.VPN)
	h("vk.com.", ips[:1], 60, rules.Direct)
	if d.Len() != 2 {
		t.Errorf("умный режим: только VPN-адреса, %v", ops.log)
	}
	d.Clear()
	mode = rules.ModeExceptRU
	h("vk.com.", ips[:1], 60, rules.Direct)
	h("example.org.", ips[1:], 60, rules.VPN)
	if d.Len() != 1 || !strings.Contains(strings.Join(ops.log, ","), "+1.1.1.1/32 direct") {
		t.Errorf("кроме российского: российское напрямую, %v", ops.log)
	}
	d.Clear()
	mode = rules.ModeAll
	h("vk.com.", ips, 60, rules.Direct)
	if d.Len() != 0 {
		t.Error("всё через VPN: ничего не добавляем")
	}
}

func TestParseIpc(t *testing.T) {
	now := time.Unix(1_700_000_100, 0)
	hs, rx, tx := ParseIpc("public_key=ab\nlast_handshake_time_sec=1700000090\nlast_handshake_time_nsec=5\nrx_bytes=100\ntx_bytes=50\nerrno=0\n", now)
	if hs != 10 || rx != 100 || tx != 50 {
		t.Errorf("%d %d %d", hs, rx, tx)
	}
	if hs, _, _ := ParseIpc("last_handshake_time_sec=0\n", now); hs != 0 {
		t.Error("рукопожатия ещё не было")
	}
}
