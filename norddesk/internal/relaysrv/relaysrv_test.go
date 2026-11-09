package relaysrv

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

// Start поднимает релей на случайном порту (для тестов этого и других пакетов).
func start(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go New().Serve(ln)
	t.Cleanup(func() { ln.Close() })
	return ln.Addr().String()
}

// TestEndToEnd: хост и клиент находят друг друга через релей, поднимают
// сквозной канал и обмениваются сообщениями в обе стороны.
func TestEndToEnd(t *testing.T) {
	addr := start(t)
	id, pw := "123456789", "482913"
	hostMsg, viewerMsg := []byte("тайл экрана"), []byte("клик мыши")

	var wg sync.WaitGroup
	var hostErr, viewerErr error
	wg.Add(2)
	go func() {
		defer wg.Done()
		conn, err := relaylink.DialHost(addr, id, 5*time.Second)
		if err != nil {
			hostErr = err
			return
		}
		defer conn.Close()
		if err = relaylink.WaitPeer(conn, time.Now().Add(5*time.Second)); err != nil {
			hostErr = err
			return
		}
		sc, err := secure.Handshake(conn, secure.RoleHost, id, pw)
		if err != nil {
			hostErr = err
			return
		}
		if err = sc.WriteFrame(wire.Encode(wire.TypeScreenInfo, hostMsg)); err != nil {
			hostErr = err
			return
		}
		raw, err := sc.ReadFrame()
		if err != nil {
			hostErr = err
			return
		}
		if m, _ := wire.Decode(raw); !bytes.Equal(m.Data, viewerMsg) {
			t.Errorf("хост получил %q", m.Data)
		}
	}()
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
		raw, err := sc.ReadFrame()
		if err != nil {
			viewerErr = err
			return
		}
		if m, _ := wire.Decode(raw); !bytes.Equal(m.Data, hostMsg) {
			t.Errorf("клиент получил %q", m.Data)
		}
		viewerErr = sc.WriteFrame(wire.Encode(wire.TypeMouseBtn, viewerMsg))
	}()

	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(15 * time.Second):
		t.Fatal("тайм-аут")
	}
	if hostErr != nil || viewerErr != nil {
		t.Fatalf("хост: %v, клиент: %v", hostErr, viewerErr)
	}
}

func TestNoHost(t *testing.T) {
	addr := start(t)
	if _, err := relaylink.DialJoin(addr, "999999999", 3*time.Second); err != relaylink.ErrNoHost {
		t.Fatalf("ждали ErrNoHost, получили %v", err)
	}
}

// Живая регистрация не даёт занять ID.
func TestIDTakenWhileAlive(t *testing.T) {
	addr := start(t)
	c1, err := relaylink.DialHost(addr, "111222333", 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c1.Close()
	if _, err := relaylink.DialHost(addr, "111222333", 3*time.Second); err != relaylink.ErrTaken {
		t.Fatalf("ждали ErrTaken, получили %v", err)
	}
}

// После перезапуска приложения (старое соединение закрыто) тот же ID снова
// доступен сразу, без ожидания HostTTL.
func TestReRegisterAfterClose(t *testing.T) {
	addr := start(t)
	id := "444555666"
	c1, err := relaylink.DialHost(addr, id, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	c1.Close()
	time.Sleep(50 * time.Millisecond)

	c2, err := relaylink.DialHost(addr, id, 3*time.Second)
	if err != nil {
		t.Fatalf("повторная регистрация после закрытия: %v", err)
	}
	defer c2.Close()

	// и клиент находит именно новую регистрацию
	go relaylink.DialJoin(addr, id, 3*time.Second)
	if err := relaylink.WaitPeer(c2, time.Now().Add(3*time.Second)); err != nil {
		t.Fatalf("новая регистрация не получила клиента: %v", err)
	}
}

// Перебор ID упирается в лимит: после всплеска запросы с того же IP отклоняются.
func TestJoinRateLimit(t *testing.T) {
	addr := start(t)
	slow := false
	for i := 0; i < 15; i++ {
		_, err := relaylink.DialJoin(addr, "999999999", 3*time.Second)
		if err == relaylink.ErrSlow {
			slow = true
			if i < 10 {
				t.Fatalf("лимит сработал слишком рано: на попытке %d", i+1)
			}
			break
		}
		if err != relaylink.ErrNoHost {
			t.Fatalf("попытка %d: %v", i+1, err)
		}
	}
	if !slow {
		t.Fatal("15 попыток подряд должны упереться в лимит")
	}
}
