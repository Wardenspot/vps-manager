package localws

import (
	"bufio"
	"bytes"
	"net"
	"testing"
)

// maskedTextFrame собирает клиентский (маскированный) текстовый кадр, как его
// прислал бы браузер.
func maskedTextFrame(payload []byte) []byte {
	mask := []byte{0x01, 0x02, 0x03, 0x04}
	masked := make([]byte, len(payload))
	for i := range payload {
		masked[i] = payload[i] ^ mask[i&3]
	}
	var b bytes.Buffer
	b.WriteByte(0x80 | OpText) // FIN + text
	b.WriteByte(0x80 | byte(len(payload)))
	b.Write(mask)
	b.Write(masked)
	return b.Bytes()
}

func TestReadMaskedMessage(t *testing.T) {
	a, b := net.Pipe()
	c := &Conn{conn: a, br: bufio.NewReader(a)}
	want := []byte(`{"cmd":"connect"}`)

	go func() {
		b.Write(maskedTextFrame(want))
	}()

	op, got, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("ReadMessage: %v", err)
	}
	if op != OpText {
		t.Fatalf("опкод %d, ждали %d", op, OpText)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("данные %q, ждали %q", got, want)
	}
}

func TestWriteFrameUnmasked(t *testing.T) {
	a, b := net.Pipe()
	c := &Conn{conn: a, br: bufio.NewReader(a)}
	payload := []byte("привет") // многобайтовый UTF-8

	go func() { c.WriteBinary(payload) }()

	hdr := make([]byte, 2)
	br := bufio.NewReader(b)
	if _, err := br.Read(hdr); err != nil {
		t.Fatal(err)
	}
	if hdr[0] != (0x80 | OpBinary) {
		t.Fatalf("первый байт %#x", hdr[0])
	}
	if hdr[1]&0x80 != 0 {
		t.Fatal("сервер не должен маскировать кадры")
	}
	n := int(hdr[1] & 0x7f)
	if n != len(payload) {
		t.Fatalf("длина %d, ждали %d", n, len(payload))
	}
	got := make([]byte, n)
	if _, err := br.Read(got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("тело %q, ждали %q", got, payload)
	}
}
