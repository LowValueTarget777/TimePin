package contract

import (
	_ "embed"
	"encoding/json"
)

//go:embed release.json
var releaseJSON []byte

var Build = func() (b struct {
	Name   string `json:"versionName"`
	Code   int    `json:"versionCode"`
	Author string `json:"author"`
}) {
	if err := json.Unmarshal(releaseJSON, &b); err != nil {
		panic(err)
	}
	return
}()
