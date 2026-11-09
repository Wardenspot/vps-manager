package secure

import (
	"bytes"
	"io"
	"net"
	"sync"
	"testing"
)

// pipePair даёт два соединённых io.ReadWriter поверх net.Pipe.
func pipePair() (io.ReadWriter, io.ReadWriter) {
	a, b := net.Pipe()
	return a, b
}

func handshakeBoth(t *testing.T, hostPw, viewerPw string) (*Conn, *Conn, error) {
	t.Helper()
	a, b := pipePair()
	var wg sync.WaitGroup
	var hc, vc *Conn
	var he, ve error
	wg.Add(2)
	go func() { defer wg.Done(); hc, he = Handshake(a, RoleHost, "123456789", hostPw) }()
	go func() { defer wg.Done(); vc, ve = Handshake(b, RoleViewer, "123456789", viewerPw) }()
	wg.Wait()
	if he != nil {
		return nil, nil, he
	}
	if ve != nil {
		return nil, nil, ve
	}
	return hc, vc, nil
}

func TestHandshakeAndRoundtrip(t *testing.T) {
	hc, vc, err := handshakeBoth(t, "4827", "4827")
	if err != nil {
		t.Fatalf("хендшейк: %v", err)
	}

	// хост → клиент
	want := []byte("кадр экрана \x00\x01\x02")
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := hc.WriteFrame(want); err != nil {
			t.Errorf("WriteFrame: %v", err)
		}
	}()
	got, err := vc.ReadFrame()
	if err != nil {
		t.Fatalf("ReadFrame: %v", err)
	}
	wg.Wait()
	if !bytes.Equal(got, want) {
		t.Fatalf("данные не совпали: %q != %q", got, want)
	}

	// обратное направление клиент → хост, второй кадр (проверяем счётчик nonce)
	want2 := []byte("ввод мыши")
	wg.Add(1)
	go func() { defer wg.Done(); vc.WriteFrame(want2) }()
	got2, err := hc.ReadFrame()
	if err != nil {
		t.Fatalf("ReadFrame2: %v", err)
	}
	wg.Wait()
	if !bytes.Equal(got2, want2) {
		t.Fatalf("обратное направление: %q != %q", got2, want2)
	}
}

func TestWrongPassword(t *testing.T) {
	_, _, err := handshakeBoth(t, "4827", "0000")
	if err != ErrConfirm {
		t.Fatalf("ожидали ErrConfirm при разных паролях, получили: %v", err)
	}
}

func TestNonceProgresses(t *testing.T) {
	hc, vc, err := handshakeBoth(t, "x", "x")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 50; i++ {
		msg := bytes.Repeat([]byte{byte(i)}, i+1)
		var wg sync.WaitGroup
		wg.Add(1)
		go func() { defer wg.Done(); hc.WriteFrame(msg) }()
		got, err := vc.ReadFrame()
		wg.Wait()
		if err != nil {
			t.Fatalf("кадр %d: %v", i, err)
		}
		if !bytes.Equal(got, msg) {
			t.Fatalf("кадр %d не совпал", i)
		}
	}
	if hc.encCtr != 50 {
		t.Fatalf("ожидали счётчик 50, получили %d", hc.encCtr)
	}
}
