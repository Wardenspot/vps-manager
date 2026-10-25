// Package ui — окно приложения (HTML/CSS/JS), встраивается в NordKey.exe.
package ui

import "embed"

//go:embed index.html app.js app.css icon.svg
var FS embed.FS
