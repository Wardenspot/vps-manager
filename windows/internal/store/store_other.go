//go:build !windows

package store

import "os"

func mkdirPrivate(dir string) error      { return os.MkdirAll(dir, 0o700) }
func protect(b []byte) ([]byte, error)   { return b, nil }
func unprotect(b []byte) ([]byte, error) { return b, nil }
