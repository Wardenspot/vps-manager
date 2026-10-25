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
	"time"
)

const (
	Host       = "143.246.193.103"
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
}

func New(base, pin string) *Client {
	c := &Client{Base: strings.TrimRight(base, "/"), Pin: strings.ToUpper(pin)}
	tr := &http.Transport{
		Proxy:               nil, // системный прокси не нужен: сервер известен
		DialContext:         (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
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
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, c.Base+path, rd)
	if err != nil {
		return 0, nil, "", err
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
			return 0, nil, "", ErrPin
		}
		return 0, nil, "", errors.New("нет связи с сервером NordKey — проверьте интернет")
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	return resp.StatusCode, b, resp.Header.Get("Content-Type"), err
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
