package main

import (
	"bytes"
	"net"
	"sync"
	"testing"
	"time"

	"norddesk/internal/relaylink"
	"norddesk/internal/secure"
	"norddesk/internal/wire"
)

// startRelay поднимает релей на случайном порту и возвращает его адрес.
func startRelay(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	h := newHub()
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go h.handle(conn)
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return ln.Addr().String()
}

// TestEndToEnd: хост и клиент находят друг друга через релей, поднимают
// сквозной шифрованный канал и обмениваются кадром в обе стороны.
func TestEndToEnd(t *testing.T) {
	addr := startRelay(t)
	id := "123456789"
	pw := "4827"

	var wg sync.WaitGroup
	wg.Add(2)

	hostFrame := []byte("тайл экрана")
	viewerFrame := []byte("клик мыши")
	var hostErr, viewerErr error

	// хост
	go func() {
		defer wg.Done()
		conn, err := relaylink.DialHost(addr, id, 5*time.Second)
		if err != nil {
			hostErr = err
			return
		}
		defer conn.Close()
		if err := relaylink.WaitPeer(conn, time.Now().Add(5*time.Second)); err != nil {
			hostErr = err
			return
		}
		sc, err := secure.Handshake(conn, secure.RoleHost, id, pw)
		if err != nil {
			hostErr = err
			return
		}
		if err := sc.WriteFrame(frame(wire.TypeScreenInfo, hostFrame)); err != nil {
			hostErr = err
			return
		}
		got, err := sc.ReadFrame()
		if err != nil {
			hostErr = err
			return
		}
		if !bytes.Equal(payload(got), viewerFrame) {
			t.Errorf("хост получил %q, ждал %q", payload(got), viewerFrame)
		}
	}()

	// клиент (чуть позже, чтобы хост успел зарегистрироваться)
	go func() {
		defer wg.Done()
		time.Sleep(100 * time.Millisecond)
		conn, err := relaylink.DialJoin(addr, id, 5*time.Second)
		if err != nil {
			viewerErr = err
			return
		}
		defer conn.Close()
		sc, err := secure.Handshake(conn, secure.RoleViewer, id, pw)
		if err != nil {
			viewerErr = err
			return
		}
		got, err := sc.ReadFrame()
		if err != nil {
			viewerErr = err
			return
		}
		if !bytes.Equal(payload(got), hostFrame) {
			t.Errorf("клиент получил %q, ждал %q", payload(got), hostFrame)
		}
		if err := sc.WriteFrame(frame(wire.TypeMouseBtn, viewerFrame)); err != nil {
			viewerErr = err
		}
	}()

	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(15 * time.Second):
		t.Fatal("тайм-аут сквозного теста")
	}
	if hostErr != nil {
		t.Fatalf("хост: %v", hostErr)
	}
	if viewerErr != nil {
		t.Fatalf("клиент: %v", viewerErr)
	}
}

func TestNoHost(t *testing.T) {
	addr := startRelay(t)
	_, err := relaylink.DialJoin(addr, "999999999", 3*time.Second)
	if err != relaylink.ErrNoHost {
		t.Fatalf("ждали ErrNoHost, получили %v", err)
	}
}

func TestIDTaken(t *testing.T) {
	addr := startRelay(t)
	id := "111222333"
	c1, err := relaylink.DialHost(addr, id, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c1.Close()
	_, err = relaylink.DialHost(addr, id, 3*time.Second)
	if err != relaylink.ErrTaken {
		t.Fatalf("ждали ErrTaken, получили %v", err)
	}
}

// frame/payload — крошечные помощники: упаковать wire-сообщение в байты и обратно.
func frame(t wire.Type, data []byte) []byte {
	var b bytes.Buffer
	wire.Write(&b, t, data)
	return b.Bytes()
}

func payload(raw []byte) []byte {
	m, err := wire.Read(bytes.NewReader(raw))
	if err != nil {
		return nil
	}
	return m.Data
}
