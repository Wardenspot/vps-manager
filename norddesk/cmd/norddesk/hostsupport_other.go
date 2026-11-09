//go:build !windows

package main

// На не-Windows захвата экрана нет — работает только «подключиться к другому».
const hostSupported = false
