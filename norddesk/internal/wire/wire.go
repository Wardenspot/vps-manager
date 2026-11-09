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
	TypeNotice     Type = 6 // UTF-8 текст: уведомление для показа клиенту
	TypeHostInfo   Type = 7 // JSON: {pc,user,admin} — сведения о компьютере
	TypeElevated   Type = 8 // UTF-8 текст: код для переподключения к хосту с правами администратора

	// клиент → хост
	TypeMouseMove  Type = 20 // бинарно: x,y uint16 (абсолютные координаты экрана хоста)
	TypeMouseBtn   Type = 21 // бинарно: button uint8, down uint8
	TypeMouseWheel Type = 22 // бинарно: deltaY int16
	TypeKey        Type = 23 // бинарно: vk uint16, down uint8 [, flags uint8: бит 0 — extended]
	TypeSetClip    Type = 24 // UTF-8 текст: установить буфер обмена на хосте
	TypeQuality    Type = 25 // бинарно: jpegQ uint8, fps uint8
	TypeCombo      Type = 26 // UTF-8 текст: спец-комбинация, напр. "alt-tab"
	TypeAction     Type = 27 // UTF-8 текст: действие из меню инструментов, напр. "cmd-admin"

	// клиент → хост: передача файлов
	TypeFileStart Type = 40 // JSON: {name,size[,batch,rel,dir]} — без batch файл идёт на рабочий стол
	TypeFileData  Type = 41 // бинарно: сырые байты очередного куска
	TypeFileEnd   Type = 42 // пусто: файл завершён
	TypePasteDone Type = 43 // JSON: {batch,items} — файлы пакета положить в буфер обмена и вставить (Ctrl+V)
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

// Encode сериализует сообщение в байты — готовый открытый текст для
// secure.Conn.WriteFrame.
func Encode(t Type, data []byte) []byte {
	out := make([]byte, 5+len(data))
	out[0] = byte(t)
	binary.BigEndian.PutUint32(out[1:], uint32(len(data)))
	copy(out[5:], data)
	return out
}

// Decode разбирает сообщение из байтов, полученных от secure.Conn.ReadFrame.
func Decode(b []byte) (Msg, error) {
	if len(b) < 5 {
		return Msg{}, errors.New("wire: короткое сообщение")
	}
	n := binary.BigEndian.Uint32(b[1:])
	if n > MaxPayload || int(n) != len(b)-5 {
		return Msg{}, errors.New("wire: неверная длина сообщения")
	}
	return Msg{Type: Type(b[0]), Data: b[5:]}, nil
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
