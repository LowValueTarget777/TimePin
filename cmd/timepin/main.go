package main

import (
	"context"
	"errors"
	"flag"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
	"timepin/internal/server"
)

func main() {
	addr := flag.String("listen", "127.0.0.1:8080", "HTTP listen address")
	path := flag.String("db", "data/timepin.db", "SQLite database file")
	resetSync := flag.Bool("reset-sync", false, "invalidate replica cursors on a restored database, then exit")
	operator := flag.String("set-admin", "", "set the standalone console login, read its new password from stdin, and exit")
	flag.Parse()
	db, e := server.Open(*path)
	if e != nil {
		log.Fatal(e)
	}
	defer db.Close()
	if *resetSync {
		if _, e = db.Exec("UPDATE sync_state SET secret=randomblob(32) WHERE singleton=1"); e != nil {
			log.Fatal(e)
		}
		return
	}
	mode := os.Getenv("TIMEPIN_REGISTRATION")
	if mode != "" && mode != "invite" && mode != "open" {
		log.Fatal("TIMEPIN_REGISTRATION must be invite or open")
	}
	app := server.NewApp(db, mode == "open", os.Getenv("TIMEPIN_SECURE_COOKIE") == "1")
	if e := app.SetConsoleLAN(os.Getenv("TIMEPIN_CONSOLE_LAN")); e != nil {
		log.Fatal(e)
	}
	app.APIOnly = os.Getenv("TIMEPIN_API_ONLY") == "1"
	if *operator != "" {
		data, err := io.ReadAll(io.LimitReader(os.Stdin, 259))
		if err != nil || len(data) > 258 {
			log.Fatal("invalid password input")
		}
		password := strings.TrimSuffix(strings.TrimSuffix(string(data), "\n"), "\r")
		if err := app.SetConsoleAccount(context.Background(), *operator, password); err != nil {
			log.Fatal(err)
		}
		return
	}
	app.Model, e = server.ModelFromEnv()
	if e != nil {
		log.Fatal(e)
	}
	srv := &http.Server{Addr: *addr, Handler: app.Handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 45 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: 60 * time.Second}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if tunnel := os.Getenv("TIMEPIN_TUNNEL_LISTEN"); tunnel != "" {
		address, err := netip.ParseAddrPort(tunnel)
		if err != nil || !address.Addr().IsLoopback() || address.Port() == 0 || os.Getenv("TIMEPIN_CONSOLE_LAN") == "" {
			log.Fatal("TIMEPIN_TUNNEL_LISTEN requires a loopback address, a fixed port and TIMEPIN_CONSOLE_LAN")
		}
		listener, err := net.Listen("tcp", tunnel)
		if err != nil {
			log.Fatal(err)
		}
		go func() {
			if err := srv.Serve(listener); !errors.Is(err, http.ErrServerClosed) {
				log.Printf("tunnel listener: %v", err)
				stop()
			}
		}()
	}
	if os.Getenv("TIMEPIN_DISCOVERY") == "1" {
		go func() {
			if err := server.Discovery(ctx, *addr); err != nil {
				log.Printf("LAN discovery: %v", err)
			}
		}()
	}
	done := make(chan struct{})
	go func() {
		<-ctx.Done()
		end, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		srv.Shutdown(end)
		close(done)
	}()
	log.Printf("TimePin listening on %s", *addr)
	if e = srv.ListenAndServe(); !errors.Is(e, http.ErrServerClosed) {
		log.Fatal(e)
	}
	<-done
}
