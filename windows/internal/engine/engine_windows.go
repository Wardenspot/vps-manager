//go:build windows

package engine

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net"
	"net/netip"
	"os/exec"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"

	"nordkey/win/internal/dnsproxy"
	"nordkey/win/internal/rules"
)

const ifName = "NordKey"

// Постоянный GUID адаптера: после сбоя Windows узнаёт «тот же» адаптер, а не плодит новые.
var ifGUID = windows.GUID{Data1: 0x4e4b5650, Data2: 0x4e4b, Data3: 0x4b45, Data4: [8]byte{'N', 'o', 'r', 'd', 'K', 'e', 'y', '1'}}

// Правило NRPT «все имена → наш DNS» (так делают Tailscale и др.): Windows спрашивает адреса только у нас.
const nrptKey = `SYSTEM\CurrentControlSet\Services\Dnscache\Parameters\DnsPolicyConfig\{4E4B5650-4E4B-4B45-4E6F-72644B657931}`

type gateway struct {
	luid winipcfg.LUID
	hop  netip.Addr
	ok   bool
}

type win struct {
	mu     sync.Mutex
	logf   func(string, ...any)
	dev    *device.Device
	tdev   tun.Device
	luid   winipcfg.LUID
	cfg    Config
	rules  atomic.Pointer[rules.Rules]
	mode   atomic.Value
	gw     atomic.Pointer[gateway]
	lanDNS atomic.Pointer[netip.Addr] // DNS роутера (для домашних имён)
	upping atomic.Bool                // идёт подключение — Status не ждёт
	loop   bool                       // запасной DNS на 127.0.0.1 слушается
	dyn    *Dyn
	dns    *dnsproxy.Server
	vpnS   []netip.Prefix
	dirS   []netip.Prefix
	st     Status
	stop   chan struct{}
}

func New(logf func(string, ...any)) Engine {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	w := &win{logf: logf, st: Status{State: "off"}}
	w.mode.Store(rules.ModeSmart)
	w.gw.Store(&gateway{})
	return w
}

func (w *win) curRules() *rules.Rules { return w.rules.Load() }
func (w *win) curMode() string        { return w.mode.Load().(string) }

// RouteOps для маршрутов от DNS.
func (w *win) Add(p netip.Prefix, vpn bool) error {
	if vpn {
		return w.luid.AddRoute(p, netip.IPv4Unspecified(), 0)
	}
	g := w.gw.Load()
	if !g.ok {
		return errors.New("нет шлюза провайдера")
	}
	return g.luid.AddRoute(p, g.hop, 0)
}

func (w *win) Del(p netip.Prefix, vpn bool) error {
	if vpn {
		return w.luid.DeleteRoute(p, netip.IPv4Unspecified())
	}
	g := w.gw.Load()
	if !g.ok {
		return nil
	}
	return g.luid.DeleteRoute(p, g.hop)
}

// defaultGateway — основной выход в интернет (не наш туннель): интерфейс и шлюз с наименьшей метрикой.
func defaultGateway(exclude winipcfg.LUID) gateway {
	rows, err := winipcfg.GetIPForwardTable2(windows.AF_INET)
	if err != nil {
		return gateway{}
	}
	best, g := uint32(math.MaxUint32), gateway{}
	for i := range rows {
		r := &rows[i]
		if r.DestinationPrefix.PrefixLength != 0 || r.InterfaceLUID == exclude {
			continue
		}
		hop := r.NextHop.Addr()
		if !hop.Is4() || hop.IsUnspecified() {
			continue
		}
		ifr, err := r.InterfaceLUID.IPInterface(windows.AF_INET)
		if err != nil {
			continue
		}
		if m := r.Metric + ifr.Metric; m < best {
			best, g = m, gateway{luid: r.InterfaceLUID, hop: hop, ok: true}
		}
	}
	return g
}

func setNRPT(server string) error {
	k, _, err := registry.CreateKey(registry.LOCAL_MACHINE, nrptKey, registry.ALL_ACCESS)
	if err != nil {
		return err
	}
	defer k.Close()
	for _, e := range []error{
		k.SetDWordValue("Version", 1),
		k.SetStringsValue("Name", []string{"."}), // «.» — все имена (так же делает Tailscale)
		k.SetStringValue("GenericDNSServers", server),
		k.SetDWordValue("ConfigOptions", 8), // свои DNS-серверы для этих имён
		k.SetStringValue("Comment", "NordKey"),
	} {
		if e != nil {
			return e
		}
	}
	return nil
}

func delNRPT() { _ = registry.DeleteKey(registry.LOCAL_MACHINE, nrptKey) }

// notifyDNS — служба DNS Windows держит правила в памяти: просим перечитать (PARAMCHANGE)
// и, как Tailscale, «ipconfig /registerdns» — после него Windows точно замечает изменения.
func notifyDNS() {
	if m, err := mgr.Connect(); err == nil {
		if s, err := m.OpenService("Dnscache"); err == nil {
			_, _ = s.Control(svc.ParamChange)
			s.Close()
		}
		m.Disconnect()
	}
	c := exec.Command("ipconfig", "/registerdns")
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
	_ = c.Run()
	flushDNS()
}

func flushDNS() {
	p := windows.NewLazySystemDLL("dnsapi.dll").NewProc("DnsFlushResolverCache")
	if p.Find() == nil {
		_, _, _ = p.Call()
	}
}

func (w *win) Cleanup() {
	delNRPT()
	notifyDNS()
}

func (w *win) setErr(err error) error {
	w.st = Status{State: "error", Mode: w.curMode(), Error: err.Error()}
	return err
}

func (w *win) Up(c Config, r *rules.Rules, mode string) error {
	w.upping.Store(true)
	defer w.upping.Store(false)
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.dev != nil {
		w.downLocked()
	}
	if err := c.Validate(); err != nil {
		return w.setErr(err)
	}
	w.cfg = c
	w.rules.Store(r)
	w.mode.Store(mode)
	w.st = Status{State: "connecting", Mode: mode, IP: c.IP}
	delNRPT() // правило ниже поставим заново

	var tdev tun.Device
	var err error
	for i := 0; i < 3; i++ { // после сбоя старый адаптер может ещё удаляться
		if tdev, err = tun.CreateTUNWithRequestedGUID(ifName, &ifGUID, c.Mtu()); err == nil {
			break
		}
		time.Sleep(time.Second)
	}
	if err != nil {
		return w.setErr(fmt.Errorf("не удалось создать сетевой адаптер (wintun): %w", err))
	}
	w.tdev = tdev
	w.luid = winipcfg.LUID(tdev.(*tun.NativeTun).LUID())
	logger := &device.Logger{Verbosef: device.DiscardLogf, Errorf: func(f string, a ...any) { w.logf("awg: "+f, a...) }}
	w.dev = device.NewDevice(tdev, conn.NewDefaultBind(), logger)
	fail := func(e error) error { w.downLocked(); return w.setErr(e) }
	if err := w.dev.IpcSet(c.UAPI()); err != nil {
		return fail(fmt.Errorf("настройка туннеля: %w", err))
	}
	if err := w.dev.Up(); err != nil {
		return fail(fmt.Errorf("запуск туннеля: %w", err))
	}
	if err := w.luid.SetIPAddresses([]netip.Prefix{c.Prefix()}); err != nil {
		return fail(fmt.Errorf("адрес туннеля: %w", err))
	}
	if ifr, err := w.luid.IPInterface(windows.AF_INET); err == nil {
		ifr.UseAutomaticMetric = false
		ifr.Metric = 5
		ifr.NLMTU = uint32(c.Mtu())
		_ = ifr.Set()
	}
	g := defaultGateway(w.luid)
	w.gw.Store(&g)
	if !g.ok {
		return fail(errors.New("нет подключения к интернету (не найден основной шлюз)"))
	}
	w.setLanDNS(g)
	if err := w.applyStatic(); err != nil {
		return fail(err)
	}
	w.dyn = NewDyn(w)
	w.dns = dnsproxy.New(dnsproxy.Config{Rules: w.curRules, Mode: w.curMode, Up: w.upstreams,
		OnAnswer: Hook(w.dyn, w.curMode), Log: w.logf})
	tunIP := c.Prefix().Addr()
	var lerr error
	for i := 0; i < 20; i++ { // адрес туннеля становится рабочим не сразу (проверка дубликатов)
		if lerr = w.dns.Listen([]netip.AddrPort{netip.AddrPortFrom(tunIP, 53)}); lerr == nil {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if lerr != nil {
		return fail(fmt.Errorf("локальный DNS: %w", lerr))
	}
	// запасной адрес на случай, если Windows не ходит на адрес туннеля (фаервол); если 53 занят — не страшно
	w.loop = w.dns.Listen([]netip.AddrPort{netip.MustParseAddrPort("127.0.0.1:53")}) == nil
	_ = w.luid.SetDNS(windows.AF_INET, []netip.Addr{tunIP}, nil)
	if err := setNRPT(tunIP.String()); err != nil {
		return fail(fmt.Errorf("перехват DNS (NRPT): %w", err))
	}
	flushDNS()
	go notifyDNS()
	w.st = Status{State: "on", Mode: mode, IP: c.IP, Since: time.Now().Unix(), Rules: r.Version()}
	w.stop = make(chan struct{})
	go w.monitor(w.stop)
	go w.dnsCheck()
	return nil
}

func (w *win) setLanDNS(g gateway) {
	var lan netip.Addr
	if dns, err := g.luid.DNS(); err == nil {
		for _, a := range dns {
			if a.Is4() {
				lan = a
				break
			}
		}
	}
	w.lanDNS.Store(&lan)
}

func (w *win) upstreams() dnsproxy.Upstreams {
	r := w.curRules()
	u := dnsproxy.Upstreams{VPN: netip.AddrPortFrom(r.DNSVPN(), 53), Direct: netip.AddrPortFrom(r.DNSDirect(), 53)}
	if a := w.lanDNS.Load(); a != nil && a.IsValid() {
		u.Local = netip.AddrPortFrom(*a, 53)
	}
	return u
}

// applyStatic — постоянные маршруты режима.
func (w *win) applyStatic() error {
	vpn, direct := Plan(w.curMode(), w.curRules(), w.cfg)
	var data []*winipcfg.RouteData
	for _, p := range vpn {
		data = append(data, &winipcfg.RouteData{Destination: p, NextHop: netip.IPv4Unspecified(), Metric: 0})
	}
	if err := w.luid.AddRoutes(data); err != nil {
		w.logf("маршруты через туннель: %v (часть могла не добавиться)", err)
	}
	g := w.gw.Load()
	for _, p := range direct {
		if err := g.luid.AddRoute(p, g.hop, 0); err != nil && !errors.Is(err, windows.ERROR_OBJECT_ALREADY_EXISTS) {
			w.logf("маршрут напрямую %s: %v", p, err)
		}
	}
	w.vpnS, w.dirS = vpn, direct
	return nil
}

func (w *win) removeStatic() {
	for _, p := range w.vpnS {
		_ = w.luid.DeleteRoute(p, netip.IPv4Unspecified())
	}
	g := w.gw.Load()
	if g.ok {
		for _, p := range w.dirS {
			_ = g.luid.DeleteRoute(p, g.hop)
		}
	}
	w.vpnS, w.dirS = nil, nil
}

// dnsCheck — правда ли запросы Windows идут к нам (если нет — сайты «через VPN» по именам работать не будут).
func (w *win) dnsCheck() {
	time.Sleep(2 * time.Second)
	w.mu.Lock()
	d := w.dns
	w.mu.Unlock()
	if d == nil {
		return
	}
	probe := func() bool {
		before := d.Stats().Queries
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
		defer cancel()
		_, _ = net.DefaultResolver.LookupHost(ctx, fmt.Sprintf("nk%d.instagram.com", time.Now().UnixNano()%1e9))
		return d.Stats().Queries > before
	}
	ok := false
	for i := 0; i < 3 && !ok; i++ { // Windows подхватывает правило за несколько секунд
		if i > 0 {
			time.Sleep(3 * time.Second)
		}
		ok = probe()
	}
	if !ok && w.loop {
		w.logf("DNS через адрес туннеля не дошёл — пробую 127.0.0.1")
		w.mu.Lock()
		set := w.dns == d && setNRPT("127.0.0.1") == nil // только если за это время не отключились
		w.mu.Unlock()
		if set {
			notifyDNS()
			time.Sleep(2 * time.Second)
			ok = probe()
		}
	}
	w.mu.Lock()
	if w.st.State == "on" && w.dns == d {
		w.st.DNSOK = &ok
	}
	w.mu.Unlock()
	if !ok {
		w.logf("ВНИМАНИЕ: запросы DNS Windows не приходят в NordKey (NRPT не сработал)")
	}
}

func (w *win) monitor(stop chan struct{}) {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	n := 0
	for {
		select {
		case <-stop:
			return
		case <-t.C:
		}
		n++
		w.mu.Lock()
		if w.dev == nil {
			w.mu.Unlock()
			return
		}
		if s, err := w.dev.IpcGet(); err == nil {
			w.st.Handshake, w.st.RX, w.st.TX = ParseIpc(s, time.Now())
		}
		if n%6 == 0 { // раз в 30 с: не сменилась ли сеть (Wi-Fi → кабель и т. п.)
			g := defaultGateway(w.luid)
			old := w.gw.Load()
			if g.ok && (g.luid != old.luid || g.hop != old.hop) {
				w.logf("сменилась сеть: шлюз %s → %s", old.hop, g.hop)
				w.dyn.Clear()
				w.removeStatic()
				w.gw.Store(&g)
				w.setLanDNS(g)
				_ = w.applyStatic()
				flushDNS()
			}
		}
		if n%24 == 0 { // раз в 2 минуты
			w.dyn.Expire()
		}
		w.mu.Unlock()
	}
}

func (w *win) SetRules(r *rules.Rules, mode string) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	oldMode := w.curMode()
	w.rules.Store(r)
	w.mode.Store(mode)
	if w.dev == nil {
		return nil
	}
	w.removeStatic()
	if mode != oldMode {
		w.dyn.Clear()
	}
	err := w.applyStatic()
	flushDNS() // браузер заново спросит адреса — и пойдёт по новым правилам
	w.st.Mode, w.st.Rules = mode, r.Version()
	return err
}

func (w *win) downLocked() {
	if w.stop != nil {
		close(w.stop)
		w.stop = nil
	}
	delNRPT()
	if w.dns != nil {
		w.dns.Close()
		w.dns = nil
	}
	if w.dyn != nil {
		w.dyn.Clear()
	}
	if w.dev != nil {
		w.removeStatic()
		w.dev.Close() // закрывает и адаптер: его маршруты уходят вместе с ним
		w.dev = nil
	}
	w.tdev = nil
	go notifyDNS()
}

func (w *win) Down() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.downLocked()
	w.st = Status{State: "off", Mode: w.curMode()}
	return nil
}

func (w *win) Status() Status {
	if w.upping.Load() {
		return Status{State: "connecting", Mode: w.curMode()}
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	s := w.st
	if w.dyn != nil {
		s.DynRoutes = w.dyn.Len()
	}
	s.Routes = len(w.vpnS) + len(w.dirS)
	if w.dns != nil {
		s.DNSQueries = w.dns.Stats().Queries
	}
	return s
}
