package secure

import (
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"math/big"
	"net"
	"testing"
)

// Точки M и N должны совпадать с опубликованными в RFC 9382 для P-256.
func TestNUMSPointsMatchRFC9382(t *testing.T) {
	m := hex.EncodeToString(elliptic.MarshalCompressed(curve, pointMx, pointMy))
	n := hex.EncodeToString(elliptic.MarshalCompressed(curve, pointNx, pointNy))
	if m != "02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f" {
		t.Errorf("M = %s", m)
	}
	if n != "03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49" {
		t.Errorf("N = %s", n)
	}
}

// Атака, которая ломала прежнее рукопожатие за 2 секунды: атакующий знает
// только ID, делает одну попытку со своим вариантом кода, получает метку хоста
// и пытается офлайн проверить другие варианты. С SPAKE2 это невозможно: метка
// хоста не совпадает ни с одним вычисленным вариантом, включая верный.
func TestOfflineBruteforceImpossible(t *testing.T) {
	const id, secret, guess = "123456789", "731905", "000000"
	hostSide, att := net.Pipe()
	go Handshake(hostSide, RoleHost, id, secret)

	// Атакующий — «клиент» со своим (неверным) вариантом кода.
	wg := passwordScalar(id, guess).FillBytes(make([]byte, 32))
	x, _ := randScalar()
	xb := x.FillBytes(make([]byte, 32))
	gx, gy := curve.ScalarBaseMult(xb)
	bx, by := curve.ScalarMult(pointMx, pointMy, wg)
	ax, ay := curve.Add(gx, gy, bx, by)
	pA := elliptic.Marshal(curve, ax, ay)

	pB := make([]byte, pointSize)
	io.ReadFull(att, pB)
	att.Write(pA)
	hostTag := make([]byte, tagSize)
	io.ReadFull(att, hostTag) // метка хоста получена
	att.Close()

	// Офлайн-проверка вариантов (в т.ч. верного) — ни один не должен совпасть.
	tx, ty := elliptic.Unmarshal(curve, pB)
	candidates := []string{secret}
	for i := 0; i < 2000; i++ {
		candidates = append(candidates, fmt.Sprintf("%06d", i*499))
	}
	for _, c := range candidates {
		wc := passwordScalar(id, c).FillBytes(make([]byte, 32))
		ux, uy := curve.ScalarMult(pointNx, pointNy, wc)
		ux, uy = curve.Add(tx, ty, ux, new(big.Int).Sub(curveP, uy))
		kx, ky := curve.ScalarMult(ux, uy, xb)
		tt := transcript([]byte("norddesk-viewer"), []byte("norddesk-host:"+id), pA, pB, elliptic.Marshal(curve, kx, ky), wc)
		h := sha256.Sum256(tt)
		kc := hkdf(h[16:], nil, []byte("ConfirmationKeys"), 64)
		if hmac.Equal(mac(kc[32:], tt), hostTag) {
			t.Fatalf("офлайн-проверка варианта %q сработала — протокол уязвим", c)
		}
	}
}
