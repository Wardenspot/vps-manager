// Package localws — крошечный WebSocket-сервер (RFC 6455) на стандартной
// библиотеке, без внешних зависимостей. Нужен только для связи нативного окна
// клиента (HTML в Edge) с его же Go-бэкендом на 127.0.0.1. Поэтому реализован
// лишь серверный минимум: рукопожатие, чтение кадров от браузера (с маской),
// отправка текстовых и бинарных кадров, ping/pong/close.
package localws

import (
	"bufio"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
)

const magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// Опкоды WebSocket.
const (
	OpContinuation = 0x0
	OpText         = 0x1
	OpBinary       = 0x2
	OpClose        = 0x8
	OpPing         = 0x9
	OpPong         = 0xA
)

// Conn — установленное WebSocket-соединение.
type Conn struct {
	conn net.Conn
	br   *bufio.Reader
	wmu  sync.Mutex
}

// Accept завершает WebSocket-рукопожатие поверх обычного HTTP-запроса.
func Accept(w http.ResponseWriter, r *http.Request) (*Conn, error) {
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		return nil, errors.New("localws: не websocket-запрос")
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	if key == "" {
		return nil, errors.New("localws: нет Sec-WebSocket-Key")
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		return nil, errors.New("localws: ResponseWriter не поддерживает Hijack")
	}
	conn, brw, err := hj.Hijack()
	if err != nil {
		return nil, err
	}
	sum := sha1.Sum([]byte(key + magic))
	accept := base64.StdEncoding.EncodeToString(sum[:])
	resp := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
	if _, err := io.WriteString(conn, resp); err != nil {
		conn.Close()
		return nil, err
	}
	return &Conn{conn: conn, br: brw.Reader}, nil
}

// ReadMessage возвращает одно полное сообщение (склеивая фрагменты). Управляющие
// кадры обрабатываются прозрачно: ping → pong, close → ошибка.
func (c *Conn) ReadMessage() (opcode int, data []byte, err error) {
	var msg []byte
	var msgOp int
	for {
		fin, op, payload, rerr := c.readFrame()
		if rerr != nil {
			return 0, nil, rerr
		}
		switch op {
		case OpPing:
			c.writeFrame(OpPong, payload)
			continue
		case OpPong:
			continue
		case OpClose:
			c.writeFrame(OpClose, nil)
			return 0, nil, io.EOF
		case OpContinuation:
			msg = append(msg, payload...)
		case OpText, OpBinary:
			msgOp = op
			msg = append(msg, payload...)
		default:
			return 0, nil, fmt.Errorf("localws: неизвестный опкод %d", op)
		}
		if fin {
			return msgOp, msg, nil
		}
	}
}

func (c *Conn) readFrame() (fin bool, opcode int, payload []byte, err error) {
	var hdr [2]byte
	if _, err = io.ReadFull(c.br, hdr[:]); err != nil {
		return
	}
	fin = hdr[0]&0x80 != 0
	opcode = int(hdr[0] & 0x0f)
	masked := hdr[1]&0x80 != 0
	length := int(hdr[1] & 0x7f)

	switch length {
	case 126:
		var ext [2]byte
		if _, err = io.ReadFull(c.br, ext[:]); err != nil {
			return
		}
		length = int(binary.BigEndian.Uint16(ext[:]))
	case 127:
		var ext [8]byte
		if _, err = io.ReadFull(c.br, ext[:]); err != nil {
			return
		}
		length = int(binary.BigEndian.Uint64(ext[:]))
	}
	if length < 0 || length > 64<<20 {
		return false, 0, nil, errors.New("localws: слишком большой кадр")
	}

	var mask [4]byte
	if masked {
		if _, err = io.ReadFull(c.br, mask[:]); err != nil {
			return
		}
	}
	payload = make([]byte, length)
	if _, err = io.ReadFull(c.br, payload); err != nil {
		return
	}
	if masked {
		for i := range payload {
			payload[i] ^= mask[i&3]
		}
	}
	return fin, opcode, payload, nil
}

// WriteText отправляет текстовый кадр (UTF-8).
func (c *Conn) WriteText(b []byte) error { return c.writeFrame(OpText, b) }

// WriteBinary отправляет бинарный кадр.
func (c *Conn) WriteBinary(b []byte) error { return c.writeFrame(OpBinary, b) }

func (c *Conn) writeFrame(opcode int, payload []byte) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()

	var hdr []byte
	b0 := byte(0x80 | opcode) // FIN + opcode
	n := len(payload)
	switch {
	case n < 126:
		hdr = []byte{b0, byte(n)}
	case n < 65536:
		hdr = []byte{b0, 126, byte(n >> 8), byte(n)}
	default:
		hdr = make([]byte, 10)
		hdr[0] = b0
		hdr[1] = 127
		binary.BigEndian.PutUint64(hdr[2:], uint64(n))
	}
	if _, err := c.conn.Write(hdr); err != nil {
		return err
	}
	if n > 0 {
		if _, err := c.conn.Write(payload); err != nil {
			return err
		}
	}
	return nil
}

// Close закрывает соединение.
func (c *Conn) Close() error { return c.conn.Close() }
