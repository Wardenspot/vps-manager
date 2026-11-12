// Package api — связь с сервером NordKey. Сертификат сервера самоподписанный, поэтому проверяем его
// по отпечатку SHA-256 (как приложение для Android), а не по цепочке.
package api

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"time"
)

// Hosts — адреса сервера по порядку. Если провайдер заблокировал один, клиент пробует следующий
// и запоминает тот, что ответил. 10.66.66.1 — адрес сервера внутри VPN.
var Hosts = []string{"143.246.193.43", "143.246.193.103", "10.66.66.1"}

const (
	Host       = "143.246.193.43"
	Base       = "https://" + Host
	CertSHA256 = "FF73B7792A7168B4ED276B5BF9389B5BCCA02B01F5D757F58D986E0330C9A278"
)

var ErrPin = errors.New("не удалось безопасно подключиться к серверу NordKey (чужой сертификат)")

// Error — ответ сервера с ошибкой.
type Error struct {
	Code int
	Msg  string
}

func (e *Error) Error() string { return e.Msg }

// Client — HTTP к серверу. Pin пустой — без проверки отпечатка (только для разработки с http://).
type Client struct {
	Base string
	Pin  string
	hc   *http.Client
	alt  []string      // запасные адреса (только для сервера по умолчанию)
	cur  *atomic.Int32 // какой из alt ответил последним
}

func New(base, pin string) *Client {
	c := &Client{Base: strings.TrimRight(base, "/"), Pin: strings.ToUpper(pin), cur: new(atomic.Int32)}
	if c.Base == Base {
		for _, h := range Hosts {
			c.alt = append(c.alt, "https://"+h)
		}
	}
	tr := &http.Transport{
		Proxy:               nil, // системный прокси не нужен: сервер известен
		DialContext:         (&net.Dialer{Timeout: 7 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		TLSHandshakeTimeout: 15 * time.Second,
		IdleConnTimeout:     90 * time.Second,
		MaxIdleConnsPerHost: 4,
	}
	if c.Pin != "" {
		tr.TLSClientConfig = &tls.Config{
			InsecureSkipVerify: true, // цепочку не проверяем — проверяем отпечаток ниже
			MinVersion:         tls.VersionTLS12,
			VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
				if len(raw) == 0 {
					return ErrPin
				}
				sum := sha256.Sum256(raw[0])
				if !strings.EqualFold(hex.EncodeToString(sum[:]), c.Pin) {
					return ErrPin
				}
				return nil
			},
		}
	}
	c.hc = &http.Client{Transport: tr, Timeout: 200 * time.Second}
	return c
}

// WithTimeout — тот же клиент (и соединения) с другим временем ожидания ответа.
func (c *Client) WithTimeout(d time.Duration) *Client {
	n := *c
	n.hc = &http.Client{Transport: c.hc.Transport, Timeout: d}
	return &n
}

// Raw — запрос как есть: статус, тело, заголовок Content-Type.
func (c *Client) Raw(method, path, token string, body []byte) (int, []byte, string, error) {
	bases := []string{c.Base}
	start := 0
	if len(c.alt) > 0 {
		bases, start = c.alt, int(c.cur.Load())
	}
	var lastErr error
	for i := range bases {
		idx := (start + i) % len(bases)
		code, b, ct, err, sent := c.raw1(bases[idx], method, path, token, body)
		if err == nil {
			c.cur.Store(int32(idx))
			return code, b, ct, nil
		}
		lastErr = err
		// POST повторяем на другом адресе, только если запрос точно не ушёл на сервер.
		if err == ErrPin || (sent && method != http.MethodGet) {
			break
		}
	}
	return 0, nil, "", lastErr
}

// beforeSend — ошибка случилась до отправки запроса (не удалось подключиться).
func beforeSend(err error) bool {
	var op *net.OpError
	if errors.As(err, &op) && op.Op == "dial" {
		return true
	}
	return strings.Contains(err.Error(), "TLS handshake")
}

func (c *Client) raw1(base, method, path, token string, body []byte) (int, []byte, string, error, bool) {
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, base+path, rd)
	if err != nil {
		return 0, nil, "", err, false
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("User-Agent", "NordKey-Windows")
	resp, err := c.hc.Do(req)
	if err != nil {
		if errors.Is(err, ErrPin) || strings.Contains(err.Error(), ErrPin.Error()) {
			return 0, nil, "", ErrPin, false
		}
		return 0, nil, "", errors.New("нет связи с сервером NordKey — проверьте интернет"), !beforeSend(err)
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return resp.StatusCode, b, resp.Header.Get("Content-Type"), err, true
	}
	return resp.StatusCode, b, resp.Header.Get("Content-Type"), nil, true
}

// Call — JSON-запрос; ошибка сервера превращается в *Error с его текстом.
func (c *Client) Call(method, path, token string, in, out any) error {
	var body []byte
	if in != nil {
		body, _ = json.Marshal(in)
	} else if method == http.MethodPost {
		body = []byte("{}")
	}
	code, b, _, err := c.Raw(method, path, token, body)
	if err != nil {
		return err
	}
	if code < 200 || code > 299 {
		var e struct {
			Error string `json:"error"`
		}
		_ = json.Unmarshal(b, &e)
		if code == 404 && (e.Error == "" || e.Error == "нет такого адреса") {
			e.Error = "на сервере NordKey нет этой функции — напишите в поддержку (сервер нужно обновить)"
		}
		if e.Error == "" {
			e.Error = fmt.Sprintf("ошибка сервера (%d)", code)
		}
		return &Error{Code: code, Msg: e.Error}
	}
	if out != nil {
		return json.Unmarshal(b, out)
	}
	return nil
}
