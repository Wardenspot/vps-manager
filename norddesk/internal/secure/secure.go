// Package secure реализует сквозное шифрование сессии NordDesk.
//
// Модель угроз. Трафик идёт через релей на VPS; релей и любой, кто знает ID,
// не должны ни прочитать, ни подменить сеанс и не должны иметь возможности
// подобрать код подключения иначе как по одной попытке на подключение.
//
// Код подключения короткий (6 цифр), поэтому используется PAKE — SPAKE2
// (RFC 9382) на P-256. В отличие от «ECDH + пароль в KDF», ни одно сообщение
// SPAKE2 не позволяет проверять варианты кода офлайн: атакующий, вклинившийся
// в обмен, может проверить ровно один вариант (тот, с которым он начал), и
// неудача сразу видна. Перебор возможен только онлайн — а его ограничивает
// хост (нарастающие паузы, смена кода).
//
// Схема (A — клиент, B — хост, w — скаляр из кода и ID):
//
//	A: x — случайный, pA = w·M + x·G      B: y — случайный, pB = w·N + y·G
//	A: K = x·(pB − w·N)                   B: K = y·(pA − w·M)     (K = xy·G)
//	TT = A ‖ B ‖ pA ‖ pB ‖ K ‖ w  (каждое поле с длиной)
//	Ke‖Ka = SHA-256(TT); KcA‖KcB = HKDF(Ka); cA = HMAC(KcA, TT), cB = HMAC(KcB, TT)
//
// Обе стороны сверяют метки подтверждения; при неверном коде — ErrConfirm.
// Из Ke выводятся ключи AES-256-GCM (у каждого направления свой ключ и
// счётчик nonce) и проверочный номер SAS. Точки M и N получены «ничего в
// рукаве» по процедуре RFC 9382 (см. nums) — их дискретный логарифм неизвестен.
package secure

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"fmt"
	"hash"
	"io"
	"math/big"
)

// Role различает стороны, чтобы ключи направлений и метки не путались местами.
type Role int

const (
	RoleHost   Role = iota // хост — тот, чей экран показывают (B в SPAKE2)
	RoleViewer             // клиент — тот, кто управляет (A в SPAKE2)
)

const (
	keySize   = 32 // AES-256
	pointSize = 65 // точка P-256 без сжатия
	tagSize   = 32 // HMAC-SHA256
	nonceFix  = 4  // фиксированный префикс nonce
	nonceCtr  = 8  // счётчик nonce
	nonceSize = nonceFix + nonceCtr
)

// ErrConfirm — метки подтверждения не сошлись: неверный код или вмешательство.
var ErrConfirm = errors.New("secure: не совпал код подтверждения (неверный пароль или вмешательство в канал)")

// ErrNonceExhausted — счётчик nonce переполнен, нужно переустановить сессию.
var ErrNonceExhausted = errors.New("secure: исчерпан счётчик nonce")

var errBadPoint = errors.New("secure: некорректная точка от второй стороны")

var (
	curve            = elliptic.P256()
	curveN           = curve.Params().N
	curveP           = curve.Params().P
	pointMx, pointMy = nums("1.2.840.10045.3.1.7 point generation seed (M)")
	pointNx, pointNy = nums("1.2.840.10045.3.1.7 point generation seed (N)")
)

// Conn — установленный защищённый канал поверх io.ReadWriter.
type Conn struct {
	rw      io.ReadWriter
	encFix  [nonceFix]byte
	decFix  [nonceFix]byte
	encAEAD cipher.AEAD
	decAEAD cipher.AEAD
	encCtr  uint64
	decCtr  uint64
	sas     string
}

// SAS — короткий проверочный номер сеанса (4 цифры). Одинаков у обеих сторон,
// только если между ними никто не вклинился. Его показывают на обоих экранах
// в режиме «без кода».
func (c *Conn) SAS() string { return c.sas }

// Handshake выполняет SPAKE2 поверх rw. password — код подключения ("" — без
// кода), sessionID — ID хоста (входит в идентичности и вывод w).
func Handshake(rw io.ReadWriter, role Role, sessionID, password string) (*Conn, error) {
	w := passwordScalar(sessionID, password)
	wb := w.FillBytes(make([]byte, 32))

	x, err := randScalar()
	if err != nil {
		return nil, err
	}
	xb := x.FillBytes(make([]byte, 32))

	// Своё сообщение: w·M + x·G (клиент) или w·N + x·G (хост).
	myBx, myBy := pointNx, pointNy
	theirBx, theirBy := pointMx, pointMy
	if role == RoleViewer {
		myBx, myBy, theirBx, theirBy = pointMx, pointMy, pointNx, pointNy
	}
	gx, gy := curve.ScalarBaseMult(xb)
	bx, by := curve.ScalarMult(myBx, myBy, wb)
	mx, my := curve.Add(gx, gy, bx, by)
	myMsg := elliptic.Marshal(curve, mx, my)

	// Обмен (хост пишет первым, чтобы не было взаимной блокировки).
	theirMsg := make([]byte, pointSize)
	if err := exchange(rw, role, myMsg, theirMsg); err != nil {
		return nil, err
	}
	tx, ty := elliptic.Unmarshal(curve, theirMsg) // проверяет, что точка на кривой
	if tx == nil {
		return nil, errBadPoint
	}

	// K = x·(их − w·B'), где B' — точка ослепления второй стороны.
	ux, uy := curve.ScalarMult(theirBx, theirBy, wb)
	ux, uy = curve.Add(tx, ty, ux, new(big.Int).Sub(curveP, uy))
	if ux.Sign() == 0 && uy.Sign() == 0 {
		return nil, errBadPoint
	}
	kx, ky := curve.ScalarMult(ux, uy, xb)
	if kx.Sign() == 0 && ky.Sign() == 0 {
		return nil, errBadPoint
	}
	K := elliptic.Marshal(curve, kx, ky)

	pA, pB := myMsg, theirMsg
	if role == RoleHost {
		pA, pB = theirMsg, myMsg
	}
	tt := transcript(
		[]byte("norddesk-viewer"),
		[]byte("norddesk-host:"+sessionID),
		pA, pB, K, wb,
	)
	h := sha256.Sum256(tt)
	ke, ka := h[:16], h[16:]
	kc := hkdf(ka, nil, []byte("ConfirmationKeys"), 64)
	cA := mac(kc[:32], tt)
	cB := mac(kc[32:], tt)

	// Подтверждение: обмен доводится до конца, сверка — после (иначе вторая
	// сторона зависнет на чтении). Офлайн-проверки вариантов кода метки не дают.
	myTag, wantTag := cB, cA
	if role == RoleViewer {
		myTag, wantTag = cA, cB
	}
	got := make([]byte, tagSize)
	if err := exchange(rw, role, myTag, got); err != nil {
		return nil, err
	}
	if subtle.ConstantTimeCompare(got, wantTag) != 1 {
		return nil, ErrConfirm
	}

	// Ключи канала и проверочный номер.
	salt := sha256.Sum256(tt)
	okm := hkdf(ke, salt[:], []byte("norddesk-v2-keys"), keySize*2+nonceFix*2)
	hostKey, viewerKey := okm[:keySize], okm[keySize:keySize*2]
	hostFix, viewerFix := okm[keySize*2:keySize*2+nonceFix], okm[keySize*2+nonceFix:]
	sasBytes := hkdf(ke, salt[:], []byte("norddesk-v2-sas"), 4)

	c := &Conn{rw: rw, sas: fmt.Sprintf("%04d", binary.BigEndian.Uint32(sasBytes)%10000)}
	encKey, decKey := hostKey, viewerKey
	copy(c.encFix[:], hostFix)
	copy(c.decFix[:], viewerFix)
	if role == RoleViewer {
		encKey, decKey = viewerKey, hostKey
		copy(c.encFix[:], viewerFix)
		copy(c.decFix[:], hostFix)
	}
	if c.encAEAD, err = newGCM(encKey); err != nil {
		return nil, err
	}
	if c.decAEAD, err = newGCM(decKey); err != nil {
		return nil, err
	}
	return c, nil
}

// exchange: хост пишет своё и читает чужое, клиент — наоборот.
func exchange(rw io.ReadWriter, role Role, mine, theirs []byte) error {
	if role == RoleHost {
		if _, err := rw.Write(mine); err != nil {
			return err
		}
		_, err := io.ReadFull(rw, theirs)
		return err
	}
	if _, err := io.ReadFull(rw, theirs); err != nil {
		return err
	}
	_, err := rw.Write(mine)
	return err
}

// passwordScalar: w = SHA-256(метка ‖ ID ‖ код) mod n.
func passwordScalar(sessionID, password string) *big.Int {
	h := sha256.Sum256(transcript([]byte("norddesk-v2-w"), []byte(sessionID), []byte(password)))
	w := new(big.Int).SetBytes(h[:])
	return w.Mod(w, curveN)
}

func randScalar() (*big.Int, error) {
	k, err := rand.Int(rand.Reader, new(big.Int).Sub(curveN, big.NewInt(1)))
	if err != nil {
		return nil, err
	}
	return k.Add(k, big.NewInt(1)), nil // [1, n-1]
}

// transcript склеивает поля с 8-байтовой длиной (little-endian), как в RFC 9382.
func transcript(fields ...[]byte) []byte {
	var out []byte
	for _, f := range fields {
		var l [8]byte
		binary.LittleEndian.PutUint64(l[:], uint64(len(f)))
		out = append(out, l[:]...)
		out = append(out, f...)
	}
	return out
}

func mac(key, msg []byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(msg)
	return m.Sum(nil)
}

// hkdf — HKDF-SHA256 на стандартной библиотеке.
func hkdf(secret, salt, info []byte, n int) []byte {
	newHash := func() hash.Hash { return sha256.New() }
	if salt == nil {
		salt = make([]byte, sha256.Size)
	}
	prkMAC := hmac.New(newHash, salt)
	prkMAC.Write(secret)
	prk := prkMAC.Sum(nil)
	var out, prev []byte
	for i := byte(1); len(out) < n; i++ {
		m := hmac.New(newHash, prk)
		m.Write(prev)
		m.Write(info)
		m.Write([]byte{i})
		prev = m.Sum(nil)
		out = append(out, prev...)
	}
	return out[:n]
}

// nums строит точку «ничего в рукаве» по процедуре RFC 9382 (раздел 6):
// хэшируем seed итеративно, первые 33 байта — кандидат сжатой точки; берём
// первый кандидат, лежащий на кривой.
func nums(seed string) (*big.Int, *big.Int) {
	iterated := func(n int) []byte {
		h := []byte(seed)
		for i := 0; i < n; i++ {
			s := sha256.Sum256(h)
			h = s[:]
		}
		return h
	}
	for i := 1; i < 1000; i++ {
		cand := append(iterated(i), iterated(i+1)...)[:33]
		cand[0] = cand[0]&1 | 2
		if x, y := elliptic.UnmarshalCompressed(curve, cand); x != nil {
			return x, y
		}
	}
	panic("secure: не удалось построить точку " + seed)
}

func newGCM(key []byte) (cipher.AEAD, error) {
	b, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(b)
}

func (c *Conn) nonce(fix [nonceFix]byte, ctr uint64) []byte {
	n := make([]byte, nonceSize)
	copy(n, fix[:])
	binary.BigEndian.PutUint64(n[nonceFix:], ctr)
	return n
}

// WriteFrame шифрует и отправляет один кадр.
// Формат: [4 байта длина шифртекста BE][шифртекст с тегом GCM].
func (c *Conn) WriteFrame(plain []byte) error {
	if c.encCtr == ^uint64(0) {
		return ErrNonceExhausted
	}
	ct := c.encAEAD.Seal(nil, c.nonce(c.encFix, c.encCtr), plain, nil)
	c.encCtr++
	var lenbuf [4]byte
	binary.BigEndian.PutUint32(lenbuf[:], uint32(len(ct)))
	if _, err := c.rw.Write(lenbuf[:]); err != nil {
		return err
	}
	_, err := c.rw.Write(ct)
	return err
}

// ReadFrame читает и расшифровывает один кадр.
func (c *Conn) ReadFrame() ([]byte, error) {
	var lenbuf [4]byte
	if _, err := io.ReadFull(c.rw, lenbuf[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(lenbuf[:])
	if int(n) > (16<<20)+c.decAEAD.Overhead() {
		return nil, errors.New("secure: слишком большой кадр")
	}
	ct := make([]byte, n)
	if _, err := io.ReadFull(c.rw, ct); err != nil {
		return nil, err
	}
	if c.decCtr == ^uint64(0) {
		return nil, ErrNonceExhausted
	}
	plain, err := c.decAEAD.Open(nil, c.nonce(c.decFix, c.decCtr), ct, nil)
	c.decCtr++
	return plain, err
}
