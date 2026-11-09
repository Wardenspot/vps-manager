// Команда relay — сервер рандеву NordDesk для VPS.
//
// Сводит хост и клиента по ID и прозрачно переливает байты между ними. Трафик
// зашифрован сквозным каналом, поэтому релей не видит содержимого сеансов.
//
// Запуск:
//
//	norddesk-relay -addr :8766
package main

import (
	"flag"
	"log"
	"net"

	"norddesk/internal/relaysrv"
)

func main() {
	addr := flag.String("addr", ":8765", "адрес прослушивания (host:port)")
	verbose := flag.Bool("v", false, "подробный лог")
	flag.Parse()

	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("не удалось слушать %s: %v", *addr, err)
	}
	log.Printf("NordDesk relay слушает %s", *addr)

	h := relaysrv.New()
	if *verbose {
		h.Logf = log.Printf
	}
	log.Fatal(h.Serve(ln))
}
