package server

import (
	"context"
	"encoding/json"
	"net"
	"strconv"
)

// A client sends one fixed probe per broadcast interface when opening the picker.
const DiscoveryPort = 41873
const DiscoveryProbe = "TIMEPIN-DISCOVER/1"

func Discovery(ctx context.Context, listen string) error {
	_, portString, err := net.SplitHostPort(listen)
	if err != nil {
		return err
	}
	port, err := strconv.Atoi(portString)
	if err != nil || port < 1 || port > 65535 {
		return invalid("发现服务需要固定 HTTP 端口")
	}
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: DiscoveryPort})
	if err != nil {
		return err
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	reply, _ := json.Marshal(struct {
		Service string `json:"service"`
		Port    int    `json:"port"`
	}{"timepin/1", port})
	var buf [64]byte
	for {
		n, peer, e := conn.ReadFromUDP(buf[:])
		if e != nil {
			if ctx.Err() != nil {
				return nil
			}
			return e
		}
		if string(buf[:n]) != DiscoveryProbe || !(peer.IP.IsPrivate() || peer.IP.IsLinkLocalUnicast() || peer.IP.IsLoopback()) {
			continue
		}
		conn.WriteToUDP(reply, peer)
	}
}
