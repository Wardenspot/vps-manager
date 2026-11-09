// Package relaysrv — серверная часть релея NordDesk.
//
// Релей сводит хост и клиента по ID и дальше прозрачно переливает байты между
// ними. Трафик зашифрован сквозным каналом (пакет secure), поэтому релей не
// видит ни экрана, ни ввода, ни файлов — он лишь «телефонистка».
//
// Протокол управляющих строк описан в пакете relaylink.
package relaysrv

import (
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	"norddesk/internal/relaylink"
)

// HostTTL — сколько держать регистрацию хоста без клиента. По истечении релей
// закрывает соединение, а хост сам регистрируется заново.
const HostTTL = 10 * time.Minute

type waiting struct {
	conn   net.Conn
	paired chan net.Conn // буфер 1: сюда клиент передаёт своё соединение
	gone   chan struct{} // закрывается, если регистрацию вытеснили
	at     time.Time
}

// Hub хранит ожидающие хосты по ID.
type Hub struct {
	mu    sync.Mutex
	hosts map[string]*waiting
	// Logf — необязательный лог (по умолчанию молчит).
	Logf func(format string, a ...any)
	// NoLimits отключает ограничение частоты (только для тестов).
	NoLimits bool

	joins *limiter // попытки подключиться к хосту с одного IP
	regs  *limiter // регистрации хостов с одного IP
}

// New создаёт пустой хаб с ограничением частоты запросов.
func New() *Hub {
	return &Hub{
		hosts: make(map[string]*waiting),
		Logf:  func(string, ...any) {},
		// 20 подключений в минуту (всплеск до 10) — живому человеку хватает с
		// запасом, а перебор ID и «заваливание» запросами упираются в лимит.
		joins: newLimiter(20, 10),
		regs:  newLimiter(30, 15),
	}
}

// Serve принимает соединения, пока слушатель не закрыт.
func (h *Hub) Serve(ln net.Listener) error {
	for {
		conn, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return err
			}
			h.Logf("accept: %v", err)
			time.Sleep(50 * time.Millisecond)
			continue
		}
		if tc, ok := conn.(*net.TCPConn); ok {
			tc.SetKeepAlive(true)
			tc.SetKeepAlivePeriod(30 * time.Second)
		}
		go h.Handle(conn)
	}
}

// Handle обслуживает одно входящее соединение.
func (h *Hub) Handle(conn net.Conn) {
	conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	line, err := relaylink.ReadLine(conn, 64)
	conn.SetReadDeadline(time.Time{})
	if err != nil {
		conn.Close()
		return
	}
	fields := strings.Fields(line)
	if len(fields) != 3 || fields[0] != relaylink.Proto {
		io.WriteString(conn, "ERR неизвестный протокол\n")
		conn.Close()
		return
	}
	cmd, id := fields[1], fields[2]
	if !h.NoLimits {
		lim := h.joins
		if cmd == "HOST" {
			lim = h.regs
		}
		if !lim.allow(remoteIP(conn)) {
			io.WriteString(conn, "SLOW\n")
			conn.Close()
			return
		}
	}
	if !ValidID(id) {
		io.WriteString(conn, "ERR неверный ID\n")
		conn.Close()
		return
	}
	switch cmd {
	case "HOST":
		h.serveHost(conn, id)
	case "JOIN":
		h.serveJoin(conn, id)
	default:
		io.WriteString(conn, "ERR неизвестная команда\n")
		conn.Close()
	}
}

// register занимает ID за хостом. false — ID занят живой регистрацией.
//
// Если ID уже зарегистрирован, проверяем, живо ли старое соединение: после
// перезапуска приложения старый сокет закрыт, и ID освобождается сразу, а не
// через HostTTL.
func (h *Hub) register(id string, w *waiting) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if old, ok := h.hosts[id]; ok {
		if time.Since(old.at) < HostTTL && alive(old.conn) {
			return false
		}
		old.conn.Close()
		close(old.gone)
		h.Logf("регистрация %s вытеснена (старое соединение мертво)", id)
	}
	h.hosts[id] = w
	return true
}

// alive проверяет, не закрыл ли хост соединение. Пока хост ждёт клиента, он
// ничего не присылает, поэтому чтение с коротким дедлайном безопасно: тайм-аут
// значит «жив», EOF/ошибка/лишние байты — «мёртв».
func alive(c net.Conn) bool {
	c.SetReadDeadline(time.Now().Add(30 * time.Millisecond))
	var b [1]byte
	_, err := c.Read(b[:])
	c.SetReadDeadline(time.Time{})
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// take снимает регистрацию, только если она всё ещё принадлежит self.
func (h *Hub) take(id string, self *waiting) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if cur, ok := h.hosts[id]; ok && cur == self {
		delete(h.hosts, id)
	}
}

func (h *Hub) pop(id string) (*waiting, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	w, ok := h.hosts[id]
	if ok {
		delete(h.hosts, id)
	}
	return w, ok
}

func (h *Hub) serveHost(conn net.Conn, id string) {
	w := &waiting{conn: conn, paired: make(chan net.Conn, 1), gone: make(chan struct{}), at: time.Now()}
	if !h.register(id, w) {
		io.WriteString(conn, "TAKEN\n")
		conn.Close()
		return
	}
	if _, err := io.WriteString(conn, "OK\n"); err != nil {
		h.take(id, w)
		conn.Close()
		return
	}
	h.Logf("хост в сети: %s", id)

	timer := time.NewTimer(HostTTL)
	defer timer.Stop()
	select {
	case viewer := <-w.paired:
		if _, err := io.WriteString(conn, "PEER\n"); err != nil {
			conn.Close()
			viewer.Close()
			return
		}
		h.Logf("сессия начата: %s", id)
		bridge(conn, viewer)
		h.Logf("сессия завершена: %s", id)
	case <-w.gone:
		// соединение уже закрыто тем, кто вытеснил регистрацию
	case <-timer.C:
		h.take(id, w)
		conn.Close()
	}
}

func (h *Hub) serveJoin(conn net.Conn, id string) {
	w, ok := h.pop(id)
	if !ok {
		io.WriteString(conn, "NOHOST\n")
		conn.Close()
		return
	}
	io.WriteString(conn, "OK\n")
	// Канал буферизован, отправка не блокирует. Если клиент уже отвалился,
	// хост это увидит на рукопожатии и перерегистрируется.
	w.paired <- conn
}

// bridge переливает байты в обе стороны, пока одна из сторон не закроется.
func bridge(a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		io.Copy(dst, src)
		// разбудить встречное копирование
		dst.SetReadDeadline(time.Now())
		src.SetReadDeadline(time.Now())
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
}

// ValidID — от 4 до 12 цифр.
func ValidID(id string) bool {
	if len(id) < 4 || len(id) > 12 {
		return false
	}
	for _, c := range id {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

// --- ограничение частоты (токен-бакет на IP) ---

type bucket struct {
	tokens float64
	last   time.Time
}

type limiter struct {
	mu      sync.Mutex
	perSec  float64
	burst   float64
	buckets map[string]*bucket
	calls   int
}

func newLimiter(perMinute, burst float64) *limiter {
	return &limiter{perSec: perMinute / 60, burst: burst, buckets: map[string]*bucket{}}
}

func (l *limiter) allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	l.calls++
	if l.calls%1000 == 0 { // изредка выбрасываем давно молчащих
		for k, b := range l.buckets {
			if now.Sub(b.last) > 10*time.Minute {
				delete(l.buckets, k)
			}
		}
	}
	b, ok := l.buckets[ip]
	if !ok {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[ip] = b
	}
	b.tokens += now.Sub(b.last).Seconds() * l.perSec
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func remoteIP(c net.Conn) string {
	host, _, err := net.SplitHostPort(c.RemoteAddr().String())
	if err != nil {
		return c.RemoteAddr().String()
	}
	return host
}
