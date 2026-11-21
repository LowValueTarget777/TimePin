package server

import (
	"net/http"
	contract "timepin/contract/v1"
)

func registerContracts(mux *http.ServeMux) {
	for path, body := range map[string][]byte{"openapi.json": contract.OpenAPI, "schema.json": contract.CalendarSchema()} {
		mux.HandleFunc("GET /api/v1/"+path, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.Write(body)
		})
	}
}
