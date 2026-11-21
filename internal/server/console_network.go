package server

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/netip"
)

// SetConsoleLAN takes the NAS interface address with its subnet length.
// An empty value keeps the access policy of existing installations.
func (a *App) SetConsoleLAN(value string) error {
	if value == "" {
		a.consoleLAN = netip.Prefix{}
		return nil
	}
	lan, err := netip.ParsePrefix(value)
	if err != nil || !lan.Addr().IsGlobalUnicast() || lan.Addr().Is4In6() ||
		lan.Bits() == 0 || lan.Addr() == lan.Masked().Addr() {
		return errors.New("TIMEPIN_CONSOLE_LAN requires the NAS interface address and subnet length, e.g. 192.168.1.10/24")
	}
	a.consoleLAN = lan
	return nil
}

func (a *App) consoleNetwork(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if lan := a.consoleLAN; lan.IsValid() {
			local, ok := r.Context().Value(http.LocalAddrContextKey).(*net.TCPAddr)
			peer, err := netip.ParseAddrPort(r.RemoteAddr)
			if !ok || local.AddrPort().Addr().Unmap() != lan.Addr() || err != nil ||
				!lan.Contains(peer.Addr().Unmap()) || peer.Addr().Unmap() == lan.Addr() {
				w.Header().Set("Content-Type", "application/json; charset=utf-8")
				w.WriteHeader(http.StatusForbidden)
				json.NewEncoder(w).Encode(map[string]string{"error": "服务器后台仅限局域网直连"})
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}
