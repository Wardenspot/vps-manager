// Package wire описывает формат сообщений сессии удалённого доступа NordDesk.
//
// Каждое сообщение — это [1 байт тип][4 байта длина BE][данные]. Поверх этого
// формата работает защищённый канал (пакет secure): он шифрует и расшифровывает
// уже готовые кадры целиком, поэтому здесь — только сериализация.
package wire

import (
	"encoding/binary"
	"errors"
	"io"
)

// Type — тип сообщения. Направление указано в комментарии.
type Type byte

const (
	// хост → клиент
	TypeScreenInfo Type = 1 // JSON: {w,h,monitor} — размер и номер монитора
	TypeTile       Type = 2 // бинарно: тайл экрана (см. Tile)
	TypeClipboard  Type = 3 // UTF-8 текст буфера обмена хоста
	TypeCursorPos  Type = 4 // бинарно: x,y uint16 — позиция курсора на хосте
	TypeSessionEnd Type = 5 // UTF-8 текст: причина завершения

	// клиент → хост
	TypeMouseMove  Type = 20 // бинарно: x,y uint16 (абсолютные координаты экрана хоста)
	TypeMouseBtn   Type = 21 // бинарно: button uint8, down uint8
	TypeMouseWheel Type = 22 // бинарно: deltaY int16
	TypeKey        Type = 23 // бинарно: vk uint16, down uint8
	TypeSetClip    Type = 24 // UTF-8 текст: установить буфер обмена на хосте
	TypeQuality    Type = 25 // бинарно: jpegQ uint8, fps uint8
	TypeCombo      Type = 26 // UTF-8 текст: спец-комбинация, напр. "ctrl-alt-del"

	// клиент → хост: передача файла на рабочий стол хоста
	TypeFileStart Type = 40 // JSON: {name,size}
	TypeFileData  Type = 41 // бинарно: сырые байты очередного куска
	TypeFileEnd   Type = 42 // пусто: файл завершён
)

// MaxPayload ограничивает размер одного сообщения, чтобы испорченная длина не
// заставила выделить гигабайты. 16 МБ с запасом хватает для тайла и куска файла.
const MaxPayload = 16 << 20

// ErrTooLarge возвращается, если заявленная длина превышает MaxPayload.
var ErrTooLarge = errors.New("wire: сообщение превышает допустимый размер")

// Msg — одно декодированное сообщение.
type Msg struct {
	Type Type
	Data []byte
}

// Write кодирует сообщение в w.
func Write(w io.Writer, t Type, data []byte) error {
	if len(data) > MaxPayload {
		return ErrTooLarge
	}
	var hdr [5]byte
	hdr[0] = byte(t)
	binary.BigEndian.PutUint32(hdr[1:], uint32(len(data)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	if len(data) > 0 {
		if _, err := w.Write(data); err != nil {
			return err
		}
	}
	return nil
}

// Read читает ровно одно сообщение из r.
func Read(r io.Reader) (Msg, error) {
	var hdr [5]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Msg{}, err
	}
	n := binary.BigEndian.Uint32(hdr[1:])
	if n > MaxPayload {
		return Msg{}, ErrTooLarge
	}
	data := make([]byte, n)
	if n > 0 {
		if _, err := io.ReadFull(r, data); err != nil {
			return Msg{}, err
		}
	}
	return Msg{Type: Type(hdr[0]), Data: data}, nil
}

// --- вспомогательные упаковщики бинарных сообщений ---

// Tile — прямоугольный кусок экрана в формате JPEG.
type Tile struct {
	X, Y, W, H uint16
	JPEG       []byte
}

// Encode упаковывает тайл: x,y,w,h (uint16 BE) + JPEG.
func (t Tile) Encode() []byte {
	out := make([]byte, 8+len(t.JPEG))
	binary.BigEndian.PutUint16(out[0:], t.X)
	binary.BigEndian.PutUint16(out[2:], t.Y)
	binary.BigEndian.PutUint16(out[4:], t.W)
	binary.BigEndian.PutUint16(out[6:], t.H)
	copy(out[8:], t.JPEG)
	return out
}

// DecodeTile разбирает тайл из полезной нагрузки сообщения TypeTile.
func DecodeTile(b []byte) (Tile, error) {
	if len(b) < 8 {
		return Tile{}, errors.New("wire: короткий тайл")
	}
	return Tile{
		X:    binary.BigEndian.Uint16(b[0:]),
		Y:    binary.BigEndian.Uint16(b[2:]),
		W:    binary.BigEndian.Uint16(b[4:]),
		H:    binary.BigEndian.Uint16(b[6:]),
		JPEG: b[8:],
	}, nil
}

// U16 упаковывает одно значение uint16 (BE).
func U16(v uint16) []byte {
	b := make([]byte, 2)
	binary.BigEndian.PutUint16(b, v)
	return b
}

// TwoU16 упаковывает пару uint16 (BE) — например координаты мыши.
func TwoU16(a, b uint16) []byte {
	out := make([]byte, 4)
	binary.BigEndian.PutUint16(out[0:], a)
	binary.BigEndian.PutUint16(out[2:], b)
	return out
}
