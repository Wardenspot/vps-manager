// Package secure реализует сквозное шифрование сессии NordDesk.
//
// Модель угроз. Трафик идёт через релей на VPS. Релей не должен иметь
// возможности ни прочитать, ни подменить содержимое сессии. Поэтому ключи
// вырабатываются между хостом и клиентом напрямую, а релей остаётся «глупой
// трубой».
//
// Схема согласования ключа:
//  1. Обе стороны генерируют эфемерную пару X25519 (crypto/ecdh) и обмениваются
//     публичными ключами через релей.
//  2. Общий секрет Z = ECDH(своя приватная, чужая публичная). Пассивный релей
//     не знает ни одной приватной части, поэтому Z ему недоступен —
//     конфиденциальность обеспечивается уже самим ECDH.
//  3. Из Z и session-пароля (который человек видит на экране хоста) выводятся
//     ключи через HKDF-SHA256. Пароль «вплетён» в ключ: если у сторон разные
//     пароли или релей пытается провести MITM своим ECDH, подтверждающие метки
//     (confirm-MAC) не сойдутся и соединение рвётся.
//  4. Активный MITM на релее вынужден угадывать пароль в онлайне (одна попытка
//     на подключение, хост показывает новый код и считает неудачи). Это та же
//     модель стойкости, что у готовых решений; дополнительно хост требует
//     ручного «Принять».
//
// Данные шифруются AES-256-GCM (из стандартной библиотеки, без внешних
// зависимостей). У каждого направления — свой ключ и свой счётчик, nonce
// составной: 4 байта фиксированного префикса + 8 байт счётчика. Повторное
// использование nonce исключено, при переполнении счётчика канал закрывается.
package secure

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"hash"
	"io"
)

// Role различает стороны, чтобы ключи направлений и метки не путались местами.
type Role int

const (
	RoleHost   Role = iota // хост — тот, чей экран показывают
	RoleViewer             // клиент — тот, кто управляет
)

const (
	keySize   = 32 // AES-256
	pubSize   = 32 // X25519 публичный ключ
	tagSize   = 32 // HMAC-SHA256 подтверждение
	nonceFix  = 4  // фиксированный префикс nonce
	nonceCtr  = 8  // счётчик nonce
	nonceSize = nonceFix + nonceCtr
)

// ErrConfirm — подтверждающая метка не сошлась: не тот пароль либо MITM.
var ErrConfirm = errors.New("secure: не совпал код подтверждения (неверный пароль или вмешательство в канал)")

// ErrNonceExhausted — счётчик nonce переполнен, нужно переустановить сессию.
var ErrNonceExhausted = errors.New("secure: исчерпан счётчик nonce")

// Conn — установленный защищённый канал поверх io.ReadWriter (обычно это
// соединение с релеем после спаривания).
type Conn struct {
	rw      io.ReadWriter
	encKey  []byte
	decKey  []byte
	encFix  [nonceFix]byte
	decFix  [nonceFix]byte
	encAEAD cipher.AEAD
	decAEAD cipher.AEAD
	encCtr  uint64
	decCtr  uint64
}

// hkdf — минимальная реализация HKDF-SHA256 на стандартной библиотеке.
func hkdf(secret, salt, info []byte, n int) []byte {
	h := func() hash.Hash { return sha256.New() }
	// extract
	prkMAC := hmac.New(h, salt)
	prkMAC.Write(secret)
	prk := prkMAC.Sum(nil)
	// expand
	var out, prev []byte
	for i := byte(1); len(out) < n; i++ {
		m := hmac.New(h, prk)
		m.Write(prev)
		m.Write(info)
		m.Write([]byte{i})
		prev = m.Sum(nil)
		out = append(out, prev...)
	}
	return out[:n]
}

// Handshake выполняет согласование ключей поверх rw.
//
// password — session-код (на хосте показывается, на клиенте вводится).
// sessionID — 9-значный ID, служит солью (одинаков у обеих сторон).
//
// Функция блокирующая: сначала отправляет свой публичный ключ, затем читает
// чужой, выводит ключи, обменивается подтверждающими метками и проверяет их.
func Handshake(rw io.ReadWriter, role Role, sessionID, password string) (*Conn, error) {
	curve := ecdh.X25519()
	priv, err := curve.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	myPub := priv.PublicKey().Bytes()

	// Обмен публичными ключами. Порядок фиксирован (хост пишет первым),
	// чтобы не было взаимной блокировки на небуферизованном канале.
	theirPub := make([]byte, pubSize)
	if role == RoleHost {
		if _, err := rw.Write(myPub); err != nil {
			return nil, err
		}
		if _, err := io.ReadFull(rw, theirPub); err != nil {
			return nil, err
		}
	} else {
		if _, err := io.ReadFull(rw, theirPub); err != nil {
			return nil, err
		}
		if _, err := rw.Write(myPub); err != nil {
			return nil, err
		}
	}

	theirKey, err := curve.NewPublicKey(theirPub)
	if err != nil {
		return nil, err
	}
	z, err := priv.ECDH(theirKey)
	if err != nil {
		return nil, err
	}

	// Транскрипт привязывает ключ к обоим публичным ключам в едином порядке
	// (хост||клиент), иначе MITM мог бы переставить их.
	hostPub, viewerPub := myPub, theirPub
	if role == RoleViewer {
		hostPub, viewerPub = theirPub, myPub
	}

	salt := sha256.Sum256(append([]byte(sessionID), append(hostPub, viewerPub...)...))
	info := append([]byte("norddesk-v1\x00"), []byte(password)...)
	okm := hkdf(z, salt[:], info, keySize*2+nonceFix*2+tagSize)

	hostKey := okm[0:keySize]
	viewerKey := okm[keySize : keySize*2]
	hostFix := okm[keySize*2 : keySize*2+nonceFix]
	viewerFix := okm[keySize*2+nonceFix : keySize*2+nonceFix*2]
	confirmKey := okm[keySize*2+nonceFix*2:]

	c := &Conn{rw: rw}
	if role == RoleHost {
		c.encKey, c.decKey = hostKey, viewerKey
		copy(c.encFix[:], hostFix)
		copy(c.decFix[:], viewerFix)
	} else {
		c.encKey, c.decKey = viewerKey, hostKey
		copy(c.encFix[:], viewerFix)
		copy(c.decFix[:], hostFix)
	}
	if err := c.initAEAD(); err != nil {
		return nil, err
	}

	// Подтверждение: каждая сторона доказывает, что вывела тот же ключ (а
	// значит, знает пароль и в канал не вмешались). Метки «меченые» ролью.
	myTag := confirm(confirmKey, role)
	theirRole := RoleViewer
	if role == RoleViewer {
		theirRole = RoleHost
	}
	wantTag := confirm(confirmKey, theirRole)

	// Обмен метками всегда доводится до конца в фиксированном порядке
	// (хост пишет первым), и только потом сверяем. Если выйти по несовпадению
	// до отправки своей метки, вторая сторона зависнет на чтении.
	got := make([]byte, tagSize)
	if role == RoleHost {
		if _, err := rw.Write(myTag); err != nil {
			return nil, err
		}
		if _, err := io.ReadFull(rw, got); err != nil {
			return nil, err
		}
	} else {
		if _, err := io.ReadFull(rw, got); err != nil {
			return nil, err
		}
		if _, err := rw.Write(myTag); err != nil {
			return nil, err
		}
	}
	if subtle.ConstantTimeCompare(got, wantTag) != 1 {
		return nil, ErrConfirm
	}
	return c, nil
}

func confirm(key []byte, role Role) []byte {
	m := hmac.New(sha256.New, key)
	if role == RoleHost {
		m.Write([]byte("host-confirm"))
	} else {
		m.Write([]byte("viewer-confirm"))
	}
	return m.Sum(nil)
}

func (c *Conn) initAEAD() error {
	eb, err := aes.NewCipher(c.encKey)
	if err != nil {
		return err
	}
	db, err := aes.NewCipher(c.decKey)
	if err != nil {
		return err
	}
	if c.encAEAD, err = cipher.NewGCM(eb); err != nil {
		return err
	}
	if c.decAEAD, err = cipher.NewGCM(db); err != nil {
		return err
	}
	return nil
}

func (c *Conn) nonce(fix [nonceFix]byte, ctr uint64) []byte {
	n := make([]byte, nonceSize)
	copy(n, fix[:])
	binary.BigEndian.PutUint64(n[nonceFix:], ctr)
	return n
}

// WriteFrame шифрует и отправляет один кадр (уже сериализованное wire-сообщение).
// Формат на проводе: [4 байта длина шифртекста BE][шифртекст с тегом GCM].
func (c *Conn) WriteFrame(plain []byte) error {
	if c.encCtr == ^uint64(0) {
		return ErrNonceExhausted
	}
	n := c.nonce(c.encFix, c.encCtr)
	c.encCtr++
	ct := c.encAEAD.Seal(nil, n, plain, nil)

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
	nonce := c.nonce(c.decFix, c.decCtr)
	c.decCtr++
	return c.decAEAD.Open(nil, nonce, ct, nil)
}
