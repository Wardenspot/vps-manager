// Команда host — консольная «входящая» часть NordDesk без окна (для серверов
// и своих машин). Обычным людям нужен один NordDesk.exe — там то же самое с
// интерфейсом.
package main

import (
	"flag"
	"fmt"
	"log"

	"norddesk/internal/hostagent"
	"norddesk/internal/relaylink"
)

// defaultRelay задаётся при сборке: -ldflags "-X main.defaultRelay=IP:PORT".
var defaultRelay = "127.0.0.1:8765"

func main() {
	relay := flag.String("relay", defaultRelay, "адрес релея host:port")
	id := flag.String("id", "", "постоянный ID (по умолчанию случайный)")
	pw := flag.String("pw", "", "постоянный пароль (без подтверждения, для своих машин)")
	noConfirm := flag.Bool("no-confirm", false, "не спрашивать согласие (ОПАСНО)")
	dir := flag.String("dir", "", "куда класть принятые файлы (по умолчанию рабочий стол)")
	flag.Parse()

	if *id == "" {
		*id = relaylink.NewID()
	}
	var confirm func() bool
	if *pw == "" && !*noConfirm {
		confirm = func() bool {
			return hostagent.ConfirmDialog("NordDesk — входящее подключение",
				"К этому компьютеру запрашивают удалённый доступ.\nКод введён верно.\n\nРазрешить управление?")
		}
	}

	var last hostagent.Status
	a := hostagent.New(hostagent.Config{
		Relay:    *relay,
		ID:       *id,
		Password: *pw,
		Confirm:  confirm,
		FilesDir: *dir,
		Logf:     log.Printf,
		OnStatus: func(s hostagent.Status) {
			if s.Code != last.Code {
				fmt.Printf("\n  ID:  %s\n  Код: %s\n\n", spaced(s.ID, 3), codeText(s.Code))
			}
			if s.State != last.State || s.Note != last.Note {
				log.Printf("состояние: %s %s", s.State, s.Note)
			}
			last = s
		},
	})
	st := a.Status()
	fmt.Printf("NordDesk — удалённый доступ к этому компьютеру\n  ID:  %s\n  Код: %s\n\n", spaced(st.ID, 3), codeText(st.Code))
	a.Run(make(chan struct{}))
}

func codeText(c string) string {
	if c == "" {
		return "(постоянный пароль)"
	}
	return spaced(c, 3)
}

// spaced разбивает строку на группы по n символов: 123456789 → 123 456 789.
func spaced(s string, n int) string {
	out := ""
	for i, r := range s {
		if i > 0 && i%n == 0 {
			out += " "
		}
		out += string(r)
	}
	return out
}
