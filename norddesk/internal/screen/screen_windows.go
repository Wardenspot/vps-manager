//go:build windows

// Package screen захватывает экран Windows через GDI и отдаёт изменившиеся
// прямоугольные тайлы в формате JPEG. Диффинг по тайлам резко сокращает
// трафик: при статичной картинке не передаётся почти ничего.
package screen

import (
	"bytes"
	"fmt"
	"hash/crc32"
	"image"
	"image/jpeg"
	"unsafe"

	"norddesk/internal/wire"
)

var (
	user32                 = lazyDLL("user32.dll")
	gdi32                  = lazyDLL("gdi32.dll")
	procGetDC              = user32.proc("GetDC")
	procReleaseDC          = user32.proc("ReleaseDC")
	procGetSystemMetrics   = user32.proc("GetSystemMetrics")
	procSetProcessDPIAware = user32.proc("SetProcessDPIAware")
	procCreateCompatDC     = gdi32.proc("CreateCompatibleDC")
	procCreateDIBSection   = gdi32.proc("CreateDIBSection")
	procSelectObject       = gdi32.proc("SelectObject")
	procBitBlt             = gdi32.proc("BitBlt")
	procDeleteObject       = gdi32.proc("DeleteObject")
	procDeleteDC           = gdi32.proc("DeleteDC")
)

const (
	smCXScreen  = 0
	smCYScreen  = 1
	srcCopy     = 0x00CC0020
	captureBlt  = 0x40000000
	dibRGBColor = 0
	tileSize    = 128
)

type bitmapInfoHeader struct {
	Size          uint32
	Width         int32
	Height        int32
	Planes        uint16
	BitCount      uint16
	Compression   uint32
	SizeImage     uint32
	XPelsPerMeter int32
	YPelsPerMeter int32
	ClrUsed       uint32
	ClrImportant  uint32
}

type bitmapInfo struct {
	Header bitmapInfoHeader
	Colors [1]uint32
}

// Capturer держит переиспользуемый DC и DIB-секцию под размер экрана.
type Capturer struct {
	w, h      int
	cols      int
	rows      int
	screenDC  uintptr
	memDC     uintptr
	bitmap    uintptr
	oldBitmap uintptr
	bits      []byte // прямой доступ к пикселям DIB (BGRA, top-down)
	hashes    []uint32
}

// EnsureDPIAware помечает процесс DPI-aware, иначе на HiDPI-экранах GDI отдаёт
// масштабированную (размытую и неполную) картинку.
func EnsureDPIAware() { procSetProcessDPIAware.call() }

// New создаёт захватчик основного монитора.
func New() (*Capturer, error) {
	EnsureDPIAware()
	w, _, _ := procGetSystemMetrics.call(smCXScreen)
	h, _, _ := procGetSystemMetrics.call(smCYScreen)
	W, H := int(w), int(h)
	if W <= 0 || H <= 0 {
		return nil, fmt.Errorf("screen: не удалось определить размер экрана (%dx%d)", W, H)
	}

	screenDC, _, _ := procGetDC.call(0)
	if screenDC == 0 {
		return nil, fmt.Errorf("screen: GetDC вернул 0")
	}
	memDC, _, _ := procCreateCompatDC.call(screenDC)
	if memDC == 0 {
		procReleaseDC.call(0, screenDC)
		return nil, fmt.Errorf("screen: CreateCompatibleDC вернул 0")
	}

	bi := bitmapInfo{Header: bitmapInfoHeader{
		Size:        uint32(unsafe.Sizeof(bitmapInfoHeader{})),
		Width:       int32(W),
		Height:      -int32(H), // отрицательная высота → top-down
		Planes:      1,
		BitCount:    32,
		Compression: 0, // BI_RGB
	}}
	var bitsPtr *uint8
	bitmap, _, _ := procCreateDIBSection.call(
		memDC,
		uintptr(unsafe.Pointer(&bi)),
		dibRGBColor,
		uintptr(unsafe.Pointer(&bitsPtr)),
		0, 0,
	)
	if bitmap == 0 || bitsPtr == nil {
		procDeleteDC.call(memDC)
		procReleaseDC.call(0, screenDC)
		return nil, fmt.Errorf("screen: CreateDIBSection вернул 0")
	}
	oldBmp, _, _ := procSelectObject.call(memDC, bitmap)

	c := &Capturer{
		w: W, h: H,
		cols:      (W + tileSize - 1) / tileSize,
		rows:      (H + tileSize - 1) / tileSize,
		screenDC:  screenDC,
		memDC:     memDC,
		bitmap:    bitmap,
		oldBitmap: oldBmp,
		bits:      unsafe.Slice(bitsPtr, W*H*4),
	}
	c.hashes = make([]uint32, c.cols*c.rows)
	c.Reset()
	return c, nil
}

// Size возвращает размер захватываемого экрана.
func (c *Capturer) Size() (int, int) { return c.w, c.h }

// Reset заставляет следующий Grab прислать все тайлы (например, при новом клиенте).
func (c *Capturer) Reset() {
	for i := range c.hashes {
		c.hashes[i] = 0xffffffff // заведомо «не совпадёт»
	}
}

// Grab делает снимок и возвращает изменившиеся тайлы в JPEG.
func (c *Capturer) Grab(quality int) ([]wire.Tile, error) {
	ret, _, _ := procBitBlt.call(
		c.memDC, 0, 0, uintptr(c.w), uintptr(c.h),
		c.screenDC, 0, 0, srcCopy|captureBlt,
	)
	if ret == 0 {
		return nil, fmt.Errorf("screen: BitBlt не удался")
	}

	var tiles []wire.Tile
	for ry := 0; ry < c.rows; ry++ {
		for rx := 0; rx < c.cols; rx++ {
			x := rx * tileSize
			y := ry * tileSize
			tw := tileSize
			if x+tw > c.w {
				tw = c.w - x
			}
			th := tileSize
			if y+th > c.h {
				th = c.h - y
			}
			hash := c.tileHash(x, y, tw, th)
			idx := ry*c.cols + rx
			if hash == c.hashes[idx] {
				continue
			}
			c.hashes[idx] = hash
			jpg, err := c.encodeTile(x, y, tw, th, quality)
			if err != nil {
				return nil, err
			}
			tiles = append(tiles, wire.Tile{
				X: uint16(x), Y: uint16(y),
				W: uint16(tw), H: uint16(th),
				JPEG: jpg,
			})
		}
	}
	return tiles, nil
}

func (c *Capturer) tileHash(x, y, tw, th int) uint32 {
	h := crc32.NewIEEE()
	row := make([]byte, tw*4)
	for r := 0; r < th; r++ {
		off := ((y+r)*c.w + x) * 4
		copy(row, c.bits[off:off+tw*4])
		h.Write(row)
	}
	return h.Sum32()
}

func (c *Capturer) encodeTile(x, y, tw, th, quality int) ([]byte, error) {
	img := image.NewRGBA(image.Rect(0, 0, tw, th))
	for r := 0; r < th; r++ {
		srcOff := ((y+r)*c.w + x) * 4
		dstOff := r * img.Stride
		for col := 0; col < tw; col++ {
			s := srcOff + col*4
			d := dstOff + col*4
			img.Pix[d+0] = c.bits[s+2] // R ← BGRA
			img.Pix[d+1] = c.bits[s+1] // G
			img.Pix[d+2] = c.bits[s+0] // B
			img.Pix[d+3] = 255
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// Close освобождает ресурсы GDI.
func (c *Capturer) Close() error {
	if c.memDC != 0 && c.oldBitmap != 0 {
		procSelectObject.call(c.memDC, c.oldBitmap)
	}
	if c.bitmap != 0 {
		procDeleteObject.call(c.bitmap)
	}
	if c.memDC != 0 {
		procDeleteDC.call(c.memDC)
	}
	if c.screenDC != 0 {
		procReleaseDC.call(0, c.screenDC)
	}
	c.memDC, c.screenDC, c.bitmap = 0, 0, 0
	return nil
}
