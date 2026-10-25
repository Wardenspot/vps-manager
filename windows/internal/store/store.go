// Package store — что приложение помнит между запусками: вход, конфиг ПК, списки сайтов, режим.
// На Windows файл зашифрован DPAPI (ключ машины) и лежит в папке, доступной только системе и администраторам.
package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"

	"nordkey/win/internal/engine"
)

type State struct {
	Token   string          `json:"token,omitempty"`
	Account json.RawMessage `json:"account,omitempty"` // последний /v1/me (для работы без сети)
	Config  *engine.Config  `json:"config,omitempty"`
	Rules   json.RawMessage `json:"rules,omitempty"`
	Mode    string          `json:"mode,omitempty"`
	WantUp  bool            `json:"want_up,omitempty"` // был включён — включить после перезагрузки
	Staff   bool            `json:"staff,omitempty"`   // сотрудник: доступны режимы «всё через VPN»
	UIKey   string          `json:"ui_key,omitempty"`
}

type Store struct {
	mu   sync.Mutex
	Path string
}

func New(dir string) (*Store, error) {
	if err := mkdirPrivate(dir); err != nil {
		return nil, err
	}
	return &Store{Path: filepath.Join(dir, "state.dat")}, nil
}

func (s *Store) Load() State {
	s.mu.Lock()
	defer s.mu.Unlock()
	var st State
	b, err := os.ReadFile(s.Path)
	if err != nil {
		return st
	}
	if b, err = unprotect(b); err != nil {
		return st
	}
	_ = json.Unmarshal(b, &st)
	return st
}

func (s *Store) Save(st State) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := json.Marshal(st)
	if err != nil {
		return err
	}
	if b, err = protect(b); err != nil {
		return err
	}
	tmp := s.Path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.Path)
}

// Update — прочитать, изменить, записать.
func (s *Store) Update(f func(*State)) (State, error) {
	st := s.Load()
	f(&st)
	return st, s.Save(st)
}
