package dnsproxy

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"

	"nordkey/win/internal/rules"
)

func query(name string, t dnsmessage.Type) []byte {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 4242, RecursionDesired: true})
	_ = b.StartQuestions()
	_ = b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name), Type: t, Class: dnsmessage.ClassINET})
	out, _ := b.Finish()
	return out
}

// ответ «как от настоящего DNS»: CNAME + два A
func answer(q []byte, ips ...string) []byte {
	var p dnsmessage.Parser
	h, _ := p.Start(q)
	qs, _ := p.Question()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: h.ID, Response: true, RecursionAvailable: true})
	_ = b.StartQuestions()
	_ = b.Question(qs)
	_ = b.StartAnswers()
	cn := dnsmessage.MustNewName("edge." + qs.Name.String())
	_ = b.CNAMEResource(dnsmessage.ResourceHeader{Name: qs.Name, Class: dnsmessage.ClassINET, TTL: 300}, dnsmessage.CNAMEResource{CNAME: cn})
	for i, ip := range ips {
		a := netip.MustParseAddr(ip).As4()
		_ = b.AResource(dnsmessage.ResourceHeader{Name: cn, Class: dnsmessage.ClassINET, TTL: uint32(60 + i*100)}, dnsmessage.AResource{A: a})
	}
	out, _ := b.Finish()
	return out
}

type call struct {
	server netip.AddrPort
	name   string
}

func setup(t *testing.T, mode string, fail map[string]bool) (*Server, *[]call, *[]string) {
	r := rules.New(rules.Data{VPNDomains: []string{"instagram.com"}, DirectDomains: []string{"vk.com"}, RUTLDs: []string{"ru"}})
	up := Upstreams{VPN: netip.MustParseAddrPort("8.8.8.8:53"), Direct: netip.MustParseAddrPort("77.88.8.8:53"),
		Local: netip.MustParseAddrPort("192.168.1.1:53")}
	var mu sync.Mutex
	calls, hooks := &[]call{}, &[]string{}
	s := New(Config{
		Rules: func() *rules.Rules { return r }, Mode: func() string { return mode }, Up: func() Upstreams { return up },
		OnAnswer: func(name string, ips []netip.Addr, ttl uint32, route rules.Decision) {
			mu.Lock()
			defer mu.Unlock()
			*hooks = append(*hooks, name+"|"+route.String()+"|"+ips[0].String()+"|"+itoa(int(ttl)))
		},
		Exchange: func(ctx context.Context, server netip.AddrPort, q []byte) ([]byte, error) {
			var p dnsmessage.Parser
			_, _ = p.Start(q)
			qs, _ := p.Question()
			mu.Lock()
			*calls = append(*calls, call{server, qs.Name.String()})
			mu.Unlock()
			if fail[server.Addr().String()] {
				return nil, errors.New("таймаут")
			}
			return answer(q, "1.2.3.4", "1.2.3.5"), nil
		},
	})
	return s, calls, hooks
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	s := ""
	for i > 0 {
		s = string(rune('0'+i%10)) + s
		i /= 10
	}
	return s
}

func rcode(t *testing.T, resp []byte) (dnsmessage.RCode, int) {
	var p dnsmessage.Parser
	h, err := p.Start(resp)
	if err != nil {
		t.Fatal(err)
	}
	_ = p.SkipAllQuestions()
	n := 0
	for {
		if _, err := p.AnswerHeader(); err != nil {
			break
		}
		n++
		_ = p.SkipAnswer()
	}
	return h.RCode, n
}

func TestSmartVPNDomain(t *testing.T) {
	s, calls, hooks := setup(t, rules.ModeSmart, nil)
	resp := s.Handle(query("www.instagram.com.", dnsmessage.TypeA))
	if rc, n := rcode(t, resp); rc != dnsmessage.RCodeSuccess || n != 3 {
		t.Fatalf("ответ: %v %d", rc, n)
	}
	if (*calls)[0].server.String() != "8.8.8.8:53" {
		t.Errorf("через VPN спрашиваем Google: %v", *calls)
	}
	if len(*hooks) != 1 || (*hooks)[0] != "www.instagram.com.|vpn|1.2.3.4|60" {
		t.Errorf("маршрут до ответа: %v", *hooks)
	}
}

func TestSmartDirect(t *testing.T) {
	s, calls, hooks := setup(t, rules.ModeSmart, nil)
	s.Handle(query("gosuslugi.ru.", dnsmessage.TypeA))
	s.Handle(query("example.org.", dnsmessage.TypeA))
	if (*calls)[0].server.String() != "77.88.8.8:53" || (*calls)[1].server.String() != "77.88.8.8:53" {
		t.Errorf("напрямую — Яндекс: %v", *calls)
	}
	if len(*hooks) != 2 || (*hooks)[0] != "gosuslugi.ru.|direct|1.2.3.4|60" {
		t.Errorf("hooks: %v", *hooks)
	}
}

func TestAAAAForVPNSuppressed(t *testing.T) {
	s, calls, _ := setup(t, rules.ModeSmart, nil)
	resp := s.Handle(query("instagram.com.", dnsmessage.TypeAAAA))
	if rc, n := rcode(t, resp); rc != dnsmessage.RCodeSuccess || n != 0 {
		t.Errorf("AAAA для VPN — пустой ответ: %v %d", rc, n)
	}
	s.Handle(query("instagram.com.", typeHTTPS))
	if len(*calls) != 0 {
		t.Errorf("наверх не спрашиваем: %v", *calls)
	}
	s.Handle(query("vk.com.", dnsmessage.TypeAAAA))
	if len(*calls) != 1 {
		t.Errorf("AAAA для прямого — спрашиваем: %v", *calls)
	}
}

func TestExceptRU(t *testing.T) {
	s, calls, hooks := setup(t, rules.ModeExceptRU, nil)
	s.Handle(query("example.org.", dnsmessage.TypeA))
	s.Handle(query("vk.com.", dnsmessage.TypeA))
	if (*calls)[0].server.String() != "8.8.8.8:53" || (*calls)[1].server.String() != "77.88.8.8:53" {
		t.Errorf("кроме российского: %v", *calls)
	}
	if (*hooks)[1] != "vk.com.|direct|1.2.3.4|60" {
		t.Errorf("российское напрямую: %v", *hooks)
	}
	if rc, n := rcode(t, s.Handle(query("example.org.", dnsmessage.TypeAAAA))); rc != 0 || n != 0 {
		t.Error("в режиме «всё через VPN» IPv6 не отдаём")
	}
}

func TestLocalNames(t *testing.T) {
	s, calls, hooks := setup(t, rules.ModeExceptRU, nil)
	s.Handle(query("my.keenetic.io.", dnsmessage.TypeA))
	s.Handle(query("router.", dnsmessage.TypeA))
	if (*calls)[0].server.String() != "192.168.1.1:53" || (*calls)[1].server.String() != "192.168.1.1:53" {
		t.Errorf("домашние имена — DNS роутера: %v", *calls)
	}
	if len(*hooks) != 0 {
		t.Errorf("для домашних имён маршрутов не трогаем: %v", *hooks)
	}
}

func TestFallback(t *testing.T) {
	s, calls, _ := setup(t, rules.ModeSmart, map[string]bool{"8.8.8.8": true})
	resp := s.Handle(query("instagram.com.", dnsmessage.TypeA))
	if rc, _ := rcode(t, resp); rc != dnsmessage.RCodeSuccess || len(*calls) != 2 || (*calls)[1].server.String() != "77.88.8.8:53" {
		t.Errorf("туннель не ответил → Яндекс: %v", *calls)
	}
	s2, _, _ := setup(t, rules.ModeSmart, map[string]bool{"8.8.8.8": true, "77.88.8.8": true})
	if rc, _ := rcode(t, s2.Handle(query("instagram.com.", dnsmessage.TypeA))); rc != dnsmessage.RCodeServerFailure {
		t.Error("никто не ответил → SERVFAIL")
	}
	if s2.Handle([]byte{1, 2, 3}) != nil {
		t.Error("мусор — без ответа")
	}
}

// настоящая сеть: слушаем UDP/TCP на 127.0.0.1, наверху — свой «Google»
func TestListenUDPAndTCP(t *testing.T) {
	upc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Skip(err)
	}
	defer upc.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := upc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			_, _ = upc.WriteToUDP(answer(buf[:n], "9.9.9.9"), from)
		}
	}()
	upAddr := upc.LocalAddr().(*net.UDPAddr).AddrPort()
	var got []string
	var mu sync.Mutex
	r := rules.New(rules.Data{VPNDomains: []string{"instagram.com"}})
	s := New(Config{Rules: func() *rules.Rules { return r }, Mode: func() string { return rules.ModeSmart },
		Up: func() Upstreams { return Upstreams{VPN: upAddr, Direct: upAddr} },
		OnAnswer: func(name string, ips []netip.Addr, ttl uint32, route rules.Decision) {
			mu.Lock()
			got = append(got, ips[0].String())
			mu.Unlock()
		}})
	l, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	port := l.LocalAddr().(*net.UDPAddr).Port
	l.Close()
	addr := netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), uint16(port))
	if err := s.Listen([]netip.AddrPort{addr}); err != nil {
		t.Skip(err)
	}
	defer s.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	resp, err := Exchange(ctx, addr, query("instagram.com.", dnsmessage.TypeA))
	if err != nil {
		t.Fatal(err)
	}
	if _, n := rcode(t, resp); n != 2 {
		t.Errorf("UDP: ответов %d", n)
	}
	resp, err = exchangeTCP(ctx, addr, query("instagram.com.", dnsmessage.TypeA))
	if err != nil {
		t.Fatal(err)
	}
	if _, n := rcode(t, resp); n != 2 {
		t.Errorf("TCP: ответов %d", n)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(got) != 2 || got[0] != "9.9.9.9" {
		t.Errorf("hooks: %v", got)
	}
	if st := s.Stats(); st.Queries != 2 || st.ToVPN != 2 {
		t.Errorf("stats: %+v", st)
	}
}
