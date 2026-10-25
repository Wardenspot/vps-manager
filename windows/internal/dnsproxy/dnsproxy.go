// Package dnsproxy — локальный DNS NordKey на ПК (как dns-proxy в Keenetic).
//
// Все запросы Windows идут сюда (правило NRPT). Для каждого имени решаем по правилам, куда пойдёт сайт:
//   - через VPN → спрашиваем Google DNS через туннель и ДО ответа программе добавляем маршрут /32 в туннель
//     (браузер подключится уже по нужному пути);
//   - напрямую → спрашиваем Яндекс DNS напрямую (российские сайты получают ближние российские серверы);
//   - имена домашней сети (роутер, принтер) → DNS провайдера/роутера.
//
// Для сайтов через VPN IPv6-адреса не отдаём: туннель только IPv4, иначе браузер ушёл бы мимо.
package dnsproxy

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"

	"nordkey/win/internal/rules"
)

// Типы записей SVCB/HTTPS (в них бывают подсказки адресов) — в dnsmessage их нет.
const (
	typeSVCB  dnsmessage.Type = 64
	typeHTTPS dnsmessage.Type = 65
)

// Upstreams — куда спрашивать.
type Upstreams struct {
	VPN    netip.AddrPort // Google DNS через туннель
	Direct netip.AddrPort // Яндекс DNS напрямую
	Local  netip.AddrPort // DNS роутера/провайдера (для имён домашней сети); пусто — Direct
}

// Hook вызывается до ответа программе: какие адреса у имени и куда оно идёт.
type Hook func(name string, ips []netip.Addr, ttl uint32, route rules.Decision)

type Config struct {
	Rules    func() *rules.Rules
	Mode     func() string
	Up       func() Upstreams
	OnAnswer Hook
	Exchange func(ctx context.Context, server netip.AddrPort, q []byte) ([]byte, error) // для тестов
	Log      func(format string, a ...any)
	Timeout  time.Duration
}

type Server struct {
	cfg  Config
	mu   sync.Mutex
	udp  []*net.UDPConn
	tcp  []net.Listener
	done chan struct{}
	stat Stats
}

type Stats struct {
	Queries, ToVPN, ToDirect, ToLocal, Failed uint64
}

func New(cfg Config) *Server {
	if cfg.Exchange == nil {
		cfg.Exchange = Exchange
	}
	if cfg.Timeout == 0 {
		cfg.Timeout = 2500 * time.Millisecond
	}
	if cfg.Log == nil {
		cfg.Log = func(string, ...any) {}
	}
	return &Server{cfg: cfg, done: make(chan struct{})}
}

func (s *Server) Stats() Stats {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stat
}

func (s *Server) count(f func(*Stats)) {
	s.mu.Lock()
	f(&s.stat)
	s.mu.Unlock()
}

// Handle — один запрос → ответ (nil — не отвечать).
func (s *Server) Handle(q []byte) []byte {
	var p dnsmessage.Parser
	hdr, err := p.Start(q)
	if err != nil || hdr.Response {
		return nil
	}
	qs, err := p.Question()
	if err != nil {
		return nil
	}
	s.count(func(st *Stats) { st.Queries++ })
	name := qs.Name.String()
	up := s.cfg.Up()
	r := s.cfg.Rules()
	mode := s.cfg.Mode()

	local := rules.Local(name)
	route := rules.Direct
	if !local && r != nil {
		route = r.Route(name, mode)
	}
	// IPv6 и HTTPS-записи с подсказками адресов для сайтов через VPN не отдаём: туннель только IPv4
	if route == rules.VPN && (qs.Type == dnsmessage.TypeAAAA || qs.Type == typeSVCB || qs.Type == typeHTTPS) {
		return empty(hdr, qs)
	}
	first, second := up.Direct, up.VPN
	switch {
	case local:
		first, second = up.Local, up.Direct
		if !first.IsValid() {
			first = up.Direct
		}
		s.count(func(st *Stats) { st.ToLocal++ })
	case route == rules.VPN:
		first, second = up.VPN, up.Direct
		s.count(func(st *Stats) { st.ToVPN++ })
	default:
		s.count(func(st *Stats) { st.ToDirect++ })
	}
	resp, err := s.ask(first, q)
	if err != nil && second.IsValid() && second != first {
		s.cfg.Log("dns: %s через %s не ответил (%v) — спрашиваю %s", name, first, err, second)
		resp, err = s.ask(second, q)
	}
	if err != nil {
		s.count(func(st *Stats) { st.Failed++ })
		return servfail(hdr, qs)
	}
	if !local && s.cfg.OnAnswer != nil {
		if ips, ttl := answers(resp); len(ips) > 0 {
			s.cfg.OnAnswer(name, ips, ttl, route)
		}
	}
	return resp
}

func (s *Server) ask(server netip.AddrPort, q []byte) ([]byte, error) {
	if !server.IsValid() {
		return nil, errors.New("нет DNS-сервера")
	}
	ctx, cancel := context.WithTimeout(context.Background(), s.cfg.Timeout)
	defer cancel()
	return s.cfg.Exchange(ctx, server, q)
}

// answers — IPv4-адреса из ответа (с учётом CNAME-цепочек) и минимальный TTL.
func answers(resp []byte) ([]netip.Addr, uint32) {
	var p dnsmessage.Parser
	h, err := p.Start(resp)
	if err != nil || h.RCode != dnsmessage.RCodeSuccess {
		return nil, 0
	}
	if err := p.SkipAllQuestions(); err != nil {
		return nil, 0
	}
	var ips []netip.Addr
	ttl := uint32(0)
	for {
		ah, err := p.AnswerHeader()
		if err != nil {
			break
		}
		if ah.Type == dnsmessage.TypeA {
			a, err := p.AResource()
			if err != nil {
				break
			}
			ips = append(ips, netip.AddrFrom4(a.A))
			if ttl == 0 || ah.TTL < ttl {
				ttl = ah.TTL
			}
		} else if err := p.SkipAnswer(); err != nil {
			break
		}
	}
	return ips, ttl
}

func reply(hdr dnsmessage.Header, q dnsmessage.Question, rc dnsmessage.RCode) []byte {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: hdr.ID, Response: true, RecursionDesired: hdr.RecursionDesired,
		RecursionAvailable: true, RCode: rc})
	b.EnableCompression()
	_ = b.StartQuestions()
	_ = b.Question(q)
	out, _ := b.Finish()
	return out
}

func empty(hdr dnsmessage.Header, q dnsmessage.Question) []byte {
	return reply(hdr, q, dnsmessage.RCodeSuccess)
}
func servfail(hdr dnsmessage.Header, q dnsmessage.Question) []byte {
	return reply(hdr, q, dnsmessage.RCodeServerFailure)
}

// Exchange — запрос к DNS-серверу: UDP, а если ответ обрезан — TCP.
func Exchange(ctx context.Context, server netip.AddrPort, q []byte) ([]byte, error) {
	var d net.Dialer
	c, err := d.DialContext(ctx, "udp", server.String())
	if err != nil {
		return nil, err
	}
	defer c.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	if _, err := c.Write(q); err != nil {
		return nil, err
	}
	buf := make([]byte, 4096)
	for {
		n, err := c.Read(buf)
		if err != nil {
			return nil, err
		}
		if n >= 2 && binary.BigEndian.Uint16(buf) == binary.BigEndian.Uint16(q) {
			if n >= 3 && buf[2]&0x02 != 0 { // TC — ответ обрезан
				return exchangeTCP(ctx, server, q)
			}
			return append([]byte(nil), buf[:n]...), nil
		}
	}
}

func exchangeTCP(ctx context.Context, server netip.AddrPort, q []byte) ([]byte, error) {
	var d net.Dialer
	c, err := d.DialContext(ctx, "tcp", server.String())
	if err != nil {
		return nil, err
	}
	defer c.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	msg := make([]byte, 2+len(q))
	binary.BigEndian.PutUint16(msg, uint16(len(q)))
	copy(msg[2:], q)
	if _, err := c.Write(msg); err != nil {
		return nil, err
	}
	var l [2]byte
	if _, err := ioReadFull(c, l[:]); err != nil {
		return nil, err
	}
	resp := make([]byte, binary.BigEndian.Uint16(l[:]))
	if _, err := ioReadFull(c, resp); err != nil {
		return nil, err
	}
	return resp, nil
}

func ioReadFull(c net.Conn, b []byte) (int, error) {
	n := 0
	for n < len(b) {
		m, err := c.Read(b[n:])
		n += m
		if err != nil {
			return n, err
		}
	}
	return n, nil
}

// Listen — слушать UDP и TCP на адресах (порт 53).
func (s *Server) Listen(addrs []netip.AddrPort) error {
	var errs []error
	ok := 0
	for _, a := range addrs {
		u, err := net.ListenUDP("udp", net.UDPAddrFromAddrPort(a))
		if err != nil {
			errs = append(errs, err)
			continue
		}
		t, err := net.Listen("tcp", a.String())
		if err != nil {
			u.Close()
			errs = append(errs, err)
			continue
		}
		s.mu.Lock()
		s.udp = append(s.udp, u)
		s.tcp = append(s.tcp, t)
		s.mu.Unlock()
		go s.serveUDP(u)
		go s.serveTCP(t)
		ok++
	}
	if ok == 0 {
		return errors.Join(errs...)
	}
	return nil
}

func (s *Server) serveUDP(c *net.UDPConn) {
	buf := make([]byte, 4096)
	for {
		n, from, err := c.ReadFromUDPAddrPort(buf)
		if err != nil {
			select {
			case <-s.done:
				return
			default:
			}
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		q := append([]byte(nil), buf[:n]...)
		go func() {
			if r := s.Handle(q); r != nil {
				_, _ = c.WriteToUDPAddrPort(r, from)
			}
		}()
	}
}

func (s *Server) serveTCP(l net.Listener) {
	for {
		c, err := l.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			for {
				_ = c.SetDeadline(time.Now().Add(10 * time.Second))
				var lb [2]byte
				if _, err := ioReadFull(c, lb[:]); err != nil {
					return
				}
				q := make([]byte, binary.BigEndian.Uint16(lb[:]))
				if _, err := ioReadFull(c, q); err != nil {
					return
				}
				r := s.Handle(q)
				if r == nil {
					return
				}
				out := make([]byte, 2+len(r))
				binary.BigEndian.PutUint16(out, uint16(len(r)))
				copy(out[2:], r)
				if _, err := c.Write(out); err != nil {
					return
				}
			}
		}()
	}
}

func (s *Server) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	select {
	case <-s.done:
	default:
		close(s.done)
	}
	for _, u := range s.udp {
		u.Close()
	}
	for _, t := range s.tcp {
		t.Close()
	}
	s.udp, s.tcp = nil, nil
}
