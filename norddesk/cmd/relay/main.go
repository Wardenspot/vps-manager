// Команда relay — сервер рандеву NordDesk для VPS.
//
// Сводит хост и клиента по 9-значному ID и дальше прозрачно переливает байты
// между ними. Трафик уже зашифрован сквозным каналом, поэтому релей не видит
// ни экрана, ни ввода, ни файлов — он лишь «телефонистка», соединяющая номера.
//
// Запуск (на VPS):
//
//	norddesk-relay -addr :8765
//
// Рекомендуется держать за TLS (nginx/caddy stream) или на 443, чтобы обойти
// DPI; на конфиденциальность это не влияет — её обеспечивает сквозное шифрование.
package main

import (
	"flag"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"time"

	"norddesk/internal/relaylink"
)

// hostTTL — сколько ждать клиента, прежде чем закрыть «висящую» регистрацию.
const hostTTL = 10 * time.Minute

type waiting struct {
	conn   net.Conn
	paired chan net.Conn // буфер 1: сюда клиент передаёт своё соединение
	at     time.Time
}

type hub struct {
	mu    sync.Mutex
	hosts map[string]*waiting
}

func newHub() *hub { return &hub{hosts: make(map[string]*waiting)} }

// register занимает ID за хостом. false — ID уже занят активной регистрацией.
func (h *hub) register(id string, w *waiting) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if old, ok := h.hosts[id]; ok {
		if time.Since(old.at) < hostTTL {
			return false
		}
		old.conn.Close() // протухшая регистрация — выселяем
	}
	h.hosts[id] = w
	return true
}

// take забирает ожидающий хост по ID (только один клиент получит его).
func (h *hub) take(id string, self *waiting) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if cur, ok := h.hosts[id]; ok && cur == self {
		delete(h.hosts, id)
	}
}

func (h *hub) pop(id string) (*waiting, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	w, ok := h.hosts[id]
	if ok {
		delete(h.hosts, id)
	}
	return w, ok
}

var verbose bool

func logf(f string, a ...any) {
	if verbose {
		log.Printf(f, a...)
	}
}

func (h *hub) handle(conn net.Conn) {
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
	if !validID(id) {
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

func (h *hub) serveHost(conn net.Conn, id string) {
	w := &waiting{conn: conn, paired: make(chan net.Conn, 1), at: time.Now()}
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
	logf("хост зарегистрирован: %s", id)

	select {
	case viewer := <-w.paired:
		if _, err := io.WriteString(conn, "PEER\n"); err != nil {
			conn.Close()
			viewer.Close()
			return
		}
		logf("сессия начата: %s", id)
		bridge(conn, viewer)
		logf("сессия завершена: %s", id)
	case <-time.After(hostTTL):
		h.take(id, w)
		conn.Close()
		logf("хост отвалился по тайм-ауту: %s", id)
	}
}

func (h *hub) serveJoin(conn net.Conn, id string) {
	w, ok := h.pop(id)
	if !ok {
		io.WriteString(conn, "NOHOST\n")
		conn.Close()
		return
	}
	if _, err := io.WriteString(conn, "OK\n"); err != nil {
		conn.Close()
		// хост ещё ждёт — вернём его в оборот не получится (уже вынут);
		// просто закроем хост, пусть переподключится
		w.conn.Close()
		return
	}
	// Передаём соединение клиента хосту (канал буферизован, не заблокируется).
	w.paired <- conn
}

// bridge переливает байты в обе стороны, пока одна из сторон не закроется.
func bridge(a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		io.Copy(dst, src)
		// разбудить вторую сторону
		dst.SetReadDeadline(time.Now())
		src.SetReadDeadline(time.Now())
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
}

func validID(id string) bool {
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

func main() {
	addr := flag.String("addr", ":8765", "адрес прослушивания (host:port)")
	flag.BoolVar(&verbose, "v", false, "подробный лог")
	flag.Parse()

	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("не удалось слушать %s: %v", *addr, err)
	}
	log.Printf("NordDesk relay слушает %s", *addr)

	h := newHub()
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		if tc, ok := conn.(*net.TCPConn); ok {
			tc.SetKeepAlive(true)
			tc.SetKeepAlivePeriod(30 * time.Second)
		}
		go h.handle(conn)
	}
}
