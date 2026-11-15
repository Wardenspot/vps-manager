// Package keenetic — роутер Keenetic в локальной сети: вход как в веб-интерфейсе, команды CLI через RCI
// и мастер подключения роутера к NordKey (то же, что батник setup.ps1 и мастер в приложении для Android).
package keenetic

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Client — один роутер по адресу Host (без схемы, например 192.168.1.1).
type Client struct {
	Host   string
	Scheme string // http или https — что ответило при проверке
	hc     *http.Client
}

func New(host string) *Client {
	jar, _ := cookiejar.New(nil)
	return &Client{Host: host, Scheme: "http", hc: &http.Client{
		Jar:     jar,
		Timeout: 90 * time.Second,
		Transport: &http.Transport{
			Proxy:       nil, // роутер — в локальной сети, системный прокси ни к чему
			DialContext: (&net.Dialer{Timeout: 5 * time.Second}).DialContext,
			// роутер может отвечать только по HTTPS со своим самоподписанным сертификатом
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, //nolint:gosec // только роутер в локальной сети
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
}

func (c *Client) url(p string) string { return c.Scheme + "://" + c.Host + p }

// Probe — отвечает ли по адресу роутер Keenetic/Netcraze (по http, затем по https).
func (c *Client) Probe(ctx context.Context) bool {
	for _, sc := range []string{"http", "https"} {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, sc+"://"+c.Host+"/auth", nil)
		r, err := c.hc.Do(req)
		if err != nil {
			continue
		}
		r.Body.Close()
		ndm := false
		for k := range r.Header {
			ndm = ndm || strings.HasPrefix(strings.ToUpper(k), "X-NDM")
		}
		if r.StatusCode == 200 || ndm {
			c.Scheme = sc
			return true
		}
		// ответ не от Keenetic: по http это может быть переадресация на https — пробуем дальше
	}
	return false
}

// AuthHash — пароль для входа: SHA256(challenge + MD5(login:realm:password)).
func AuthHash(user, pass, realm, challenge string) string {
	m := md5.Sum([]byte(user + ":" + realm + ":" + pass))
	s := sha256.Sum256([]byte(challenge + hex.EncodeToString(m[:])))
	return hex.EncodeToString(s[:])
}

func (c *Client) Login(user, pass string) error {
	r, err := c.hc.Get(c.url("/auth"))
	if err != nil {
		return fmt.Errorf("роутер не отвечает по адресу %s", c.Host)
	}
	r.Body.Close()
	if r.StatusCode == 200 {
		return nil
	}
	realm, ch := r.Header.Get("X-NDM-Realm"), r.Header.Get("X-NDM-Challenge")
	if realm == "" || ch == "" {
		return fmt.Errorf("по адресу %s не роутер Keenetic", c.Host)
	}
	body, _ := json.Marshal(map[string]string{"login": user, "password": AuthHash(user, pass, realm, ch)})
	r2, err := c.hc.Post(c.url("/auth"), "application/json", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("роутер не отвечает по адресу %s", c.Host)
	}
	r2.Body.Close()
	if r2.StatusCode != 200 {
		return errors.New("неверный логин или пароль роутера")
	}
	return nil
}

// RCI — команды CLI → сырой ответ роутера (JSON-текст, «\/» уже заменены на «/»).
func (c *Client) RCI(cmds ...string) (string, error) {
	arr := make([]map[string]string, len(cmds))
	for i, x := range cmds {
		arr[i] = map[string]string{"parse": x}
	}
	body, _ := json.Marshal(arr)
	r, err := c.hc.Post(c.url("/rci/"), "application/json", bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("связь с роутером прервалась: %v", err)
	}
	defer r.Body.Close()
	if r.StatusCode == 401 {
		return "", errors.New("роутер завершил сеанс — войдите заново")
	}
	b, err := io.ReadAll(io.LimitReader(r.Body, 8<<20))
	if err != nil {
		return "", fmt.Errorf("связь с роутером прервалась: %v", err)
	}
	return strings.ReplaceAll(string(b), `\/`, "/"), nil
}

var reErr = regexp.MustCompile(`"status"\s*:\s*"error"[^}]*"message"\s*:\s*"([^"]*)"`)

// Errors — тексты ошибок из ответа RCI.
func Errors(resp string) []string {
	var out []string
	for _, m := range reErr.FindAllStringSubmatch(resp, -1) {
		out = append(out, m[1])
	}
	return out
}

// Candidates — где искать роутер: x.y.z.1 в каждой домашней сети ПК, потом привычные адреса.
func Candidates() []string {
	seen := map[string]bool{}
	var out []string
	add := func(s string) {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	ifs, _ := net.Interfaces()
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			n, ok := a.(*net.IPNet)
			if !ok || n.IP.To4() == nil || !n.IP.IsPrivate() {
				continue
			}
			ip := n.IP.To4()
			if ip[0] == 10 && ip[1] == 66 { // адреса NordKey VPN
				continue
			}
			add(fmt.Sprintf("%d.%d.%d.1", ip[0], ip[1], ip[2]))
		}
	}
	for _, s := range []string{"192.168.1.1", "192.168.0.1", "my.keenetic.net", "my.netcraze.ru"} {
		add(s)
	}
	return out
}

// Find — первый адрес из списка, где отвечает Keenetic ("" — не нашли).
func Find(ctx context.Context, hosts []string) string {
	ctx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()
	found := make([]bool, len(hosts))
	var wg sync.WaitGroup
	for i, h := range hosts {
		wg.Add(1)
		go func() { defer wg.Done(); found[i] = New(h).Probe(ctx) }()
	}
	wg.Wait()
	for i, ok := range found {
		if ok {
			return hosts[i]
		}
	}
	return ""
}
