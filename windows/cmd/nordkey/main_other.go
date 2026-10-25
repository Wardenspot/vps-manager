//go:build !windows

// Режим разработки (Linux/macOS): ядро VPN — имитация, сервер — любой адрес (например, тестовый без TLS).
//go:debug httpmuxgo121=0

package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"

	"nordkey/win/internal/api"
	"nordkey/win/internal/app"
	"nordkey/win/internal/engine"
	"nordkey/win/internal/store"
	"nordkey/win/ui"
)

var version = "dev"

func main() {
	base := flag.String("api", api.Base, "адрес сервера NordKey")
	addr := flag.String("addr", app.Addr, "локальный адрес окна")
	data := flag.String("data", "./nk-dev-data", "папка состояния")
	flag.Parse()
	pin := api.CertSHA256
	if *base != api.Base {
		pin = ""
	}
	st, err := store.New(*data)
	if err != nil {
		log.Fatal(err)
	}
	a := app.New(app.Options{Addr: *addr, API: api.New(*base, pin), Store: st, Engine: engine.New(nil), UI: ui.FS,
		Version: version, Device: "ПК dev", Logf: log.Printf})
	a.Start()
	if _, err := a.Serve(); err != nil {
		log.Fatal(err)
	}
	fmt.Printf("NordKey dev: http://%s/#k=%s\n", *addr, st.Load().UIKey)
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt)
	<-c
	a.Stop()
}
