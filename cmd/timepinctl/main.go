package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	contract "timepin/contract/v1"
	"timepin/internal/client"
)

func run() error {
	base := os.Getenv("TIMEPIN_URL")
	if base == "" {
		base = "http://127.0.0.1:8080"
	}
	url := flag.String("url", base, "instance root URL")
	path := flag.String("f", "", "read JSON from file (- for stdin)")
	flag.Usage = func() {
		fmt.Fprintln(os.Stderr, "timepinctl [-url URL] [-f file] <operation|tools|openapi|mcp|mcp-admin>\nRead one JSON object on stdin. Run tools to discover operations. TIMEPIN_TOKEN supplies a scoped bearer token.")
	}
	flag.Parse()
	if flag.NArg() != 1 {
		flag.Usage()
		return errors.New("expected one operation")
	}
	op := flag.Arg(0)
	switch op {
	case "openapi":
		_, e := os.Stdout.Write(contract.OpenAPI)
		return e
	case "tools":
		return json.NewEncoder(os.Stdout).Encode(contract.Tools())
	}
	c, e := client.New(*url, os.Getenv("TIMEPIN_TOKEN"), os.Getenv("TIMEPIN_SESSION_FILE"))
	if e != nil {
		return e
	}
	if op == "mcp-admin" {
		return client.ServeConsoleMCP(context.Background(), c, os.Stdin, os.Stdout)
	}
	if op == "mcp" {
		return client.ServeMCP(context.Background(), c, os.Stdin, os.Stdout)
	}
	var src io.Reader = os.Stdin
	if *path != "" && *path != "-" {
		f, e := os.Open(*path)
		if e != nil {
			return e
		}
		defer f.Close()
		src = f
	} else if *path == "" {
		stat, e := os.Stdin.Stat()
		if e != nil {
			return e
		}
		if stat.Mode()&os.ModeCharDevice != 0 {
			src = bytes.NewReader(nil)
		}
	}
	data, e := io.ReadAll(io.LimitReader(src, (16<<20)+1))
	if e != nil {
		return e
	}
	if len(data) > 16<<20 {
		return errors.New("JSON input exceeds 16 MiB")
	}
	args := map[string]any{}
	if len(bytes.TrimSpace(data)) != 0 {
		d := json.NewDecoder(bytes.NewReader(data))
		d.UseNumber()
		if d.Decode(&args) != nil || args == nil || d.Decode(new(any)) != io.EOF {
			return errors.New("expected one JSON object")
		}
	}
	reply, e := c.Call(context.Background(), op, args)
	if e != nil {
		return e
	}
	fmt.Println(string(reply.Body))
	if reply.Failed {
		return failure{}
	}
	return nil
}

type failure struct{}

func (failure) Error() string { return "operation failed" }
func main() {
	if e := run(); e != nil {
		if _, ok := e.(failure); ok {
			os.Exit(3)
		}
		json.NewEncoder(os.Stderr).Encode(map[string]string{"error": e.Error()})
		os.Exit(1)
	}
}
