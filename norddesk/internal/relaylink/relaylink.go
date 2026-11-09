// Package relaylink — клиентская сторона протокола рандеву с релеем.
//
// Релей на VPS сводит хост и клиента по 9-значному ID и дальше просто
// переливает байты между ними. Содержимое уже зашифровано сквозным каналом
// (пакет secure), поэтому релею доверять не нужно.
//
// Текстовый управляющий протокол (одна строка, оканчивается \n):
//
//	хост:    NDSK1 HOST <id>\n   → ответ OK\n | TAKEN\n | ERR ...\n
//	клиент:  NDSK1 JOIN <id>\n   → ответ OK\n | NOHOST\n | BUSY\n | ERR ...\n
//
// После OK хост ждёт строку PEER\n — она означает, что клиент подключился.
// Сразу за управляющими строками начинается сквозной handshake, поэтому строки
// читаются по одному байту (без буфера), чтобы не «съесть» байты сессии.
package relaylink

import (
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"strings"
	"time"
)

// Proto — метка версии управляющего протокола.
const Proto = "NDSK1"

var (
	ErrTaken  = errors.New("relaylink: этот ID уже занят на релее")
	ErrNoHost = errors.New("relaylink: хост с таким ID не в сети")
	ErrBusy   = errors.New("relaylink: к хосту уже подключены")
)

// Режим подключения — первый байт клиента после спаривания (до рукопожатия).
//
//	ModePassword — клиент ввёл код: доступ сразу, без подтверждения;
//	ModeRequest  — только ID: на той стороне спросят «Разрешить?».
//
// Байт не защищён, но подмена ничего не даёт: P→R лишь вызовет окно
// подтверждения (с проверочным номером), R→P — провал рукопожатия.
const (
	ModePassword byte = 'P'
	ModeRequest  byte = 'R'
)

// SendMode отправляет режим подключения (клиент).
func SendMode(conn net.Conn, mode byte) error {
	_, err := conn.Write([]byte{mode})
	return err
}

// ReadMode читает режим подключения (хост).
func ReadMode(conn net.Conn, deadline time.Time) (byte, error) {
	conn.SetReadDeadline(deadline)
	defer conn.SetReadDeadline(time.Time{})
	b := make([]byte, 1)
	if _, err := io.ReadFull(conn, b); err != nil {
		return 0, err
	}
	if b[0] != ModePassword && b[0] != ModeRequest {
		return 0, fmt.Errorf("relaylink: неизвестный режим %q", b[0])
	}
	return b[0], nil
}

// NewID генерирует случайный 9-значный ID (как у готовых решений).
func NewID() string {
	var sb strings.Builder
	for i := 0; i < 9; i++ {
		n, _ := rand.Int(rand.Reader, big.NewInt(10))
		sb.WriteByte('0' + byte(n.Int64()))
	}
	return sb.String()
}

// NewCode генерирует n-значный цифровой одноразовый код подключения.
func NewCode(n int) string {
	var sb strings.Builder
	for i := 0; i < n; i++ {
		d, _ := rand.Int(rand.Reader, big.NewInt(10))
		sb.WriteByte('0' + byte(d.Int64()))
	}
	return sb.String()
}

// ReadLine читает строку до '\n' по одному байту, не буферизуя лишнее.
// Это важно: сразу за управляющей строкой идут байты сквозного handshake,
// которые нельзя случайно вычитать в буфер.
func ReadLine(conn net.Conn, limit int) (string, error) {
	return readLine(conn, limit)
}

// readLine читает строку до '\n' по одному байту (не буферизуя лишнее).
func readLine(conn net.Conn, limit int) (string, error) {
	buf := make([]byte, 0, 32)
	one := make([]byte, 1)
	for len(buf) < limit {
		if _, err := io.ReadFull(conn, one); err != nil {
			return "", err
		}
		if one[0] == '\n' {
			return string(buf), nil
		}
		if one[0] != '\r' {
			buf = append(buf, one[0])
		}
	}
	return "", errors.New("relaylink: слишком длинная управляющая строка")
}

// DialHost подключается к релею и регистрирует хост с данным ID.
// Возвращает «сырое» соединение; вызывающий далее ждёт WaitPeer.
func DialHost(relayAddr, id string, timeout time.Duration) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", relayAddr, timeout)
	if err != nil {
		return nil, err
	}
	if _, err := fmt.Fprintf(conn, "%s HOST %s\n", Proto, id); err != nil {
		conn.Close()
		return nil, err
	}
	conn.SetReadDeadline(time.Now().Add(timeout))
	line, err := readLine(conn, 64)
	conn.SetReadDeadline(time.Time{})
	if err != nil {
		conn.Close()
		return nil, err
	}
	switch {
	case line == "OK":
		return conn, nil
	case line == "TAKEN":
		conn.Close()
		return nil, ErrTaken
	default:
		conn.Close()
		return nil, fmt.Errorf("relaylink: релей ответил %q", line)
	}
}

// WaitPeer блокируется, пока к хосту не подключится клиент (строка PEER).
// deadline <= 0 — ждать бесконечно.
func WaitPeer(conn net.Conn, deadline time.Time) error {
	conn.SetReadDeadline(deadline)
	defer conn.SetReadDeadline(time.Time{})
	line, err := readLine(conn, 64)
	if err != nil {
		return err
	}
	if line != "PEER" {
		return fmt.Errorf("relaylink: ожидали PEER, получили %q", line)
	}
	return nil
}

// DialJoin подключается к релею как клиент и присоединяется к хосту по ID.
func DialJoin(relayAddr, id string, timeout time.Duration) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", relayAddr, timeout)
	if err != nil {
		return nil, err
	}
	if _, err := fmt.Fprintf(conn, "%s JOIN %s\n", Proto, id); err != nil {
		conn.Close()
		return nil, err
	}
	conn.SetReadDeadline(time.Now().Add(timeout))
	line, err := readLine(conn, 64)
	conn.SetReadDeadline(time.Time{})
	if err != nil {
		conn.Close()
		return nil, err
	}
	switch line {
	case "OK":
		return conn, nil
	case "NOHOST":
		conn.Close()
		return nil, ErrNoHost
	case "BUSY":
		conn.Close()
		return nil, ErrBusy
	default:
		conn.Close()
		return nil, fmt.Errorf("relaylink: релей ответил %q", line)
	}
}
