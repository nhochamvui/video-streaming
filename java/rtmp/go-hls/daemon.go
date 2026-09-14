package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
)

// streamConfig holds the per-stream segmentation defaults.
type streamConfig struct {
	TargetDurMS     int64
	ListSize        int
	DeleteThreshold int
}

// handshake is the one-line JSON each stream connection sends first.
type handshake struct {
	OutDir          string   `json:"outDir"`
	HLSTime         *float64 `json:"hlsTime,omitempty"`
	ListSize        *int     `json:"listSize,omitempty"`
	DeleteThreshold *int     `json:"deleteThreshold,omitempty"`
}

// serve listens on unix:/path or tcp:host:port and serves many concurrent streams.
func serve(listen string, defaults streamConfig, uploader Uploader) error {
	network, addr := parseListen(listen)
	if network == "unix" {
		_ = os.Remove(addr) // stale socket from a previous run
	}
	ln, err := net.Listen(network, addr)
	if err != nil {
		return err
	}
	if network == "unix" {
		defer os.Remove(addr)
	}
	return serveListener(ln, defaults, uploader)
}

// serveListener runs the accept loop on an existing listener (used by tests).
func serveListener(ln net.Listener, defaults streamConfig, uploader Uploader) error {
	fmt.Fprintf(os.Stderr, "hls-segmenter: daemon listening on %s\n", ln.Addr())
	for {
		conn, err := ln.Accept()
		if err != nil {
			if strings.Contains(err.Error(), "use of closed network connection") {
				return nil
			}
			fmt.Fprintf(os.Stderr, "accept: %v\n", err)
			continue
		}
		go handleConn(conn, defaults, uploader)
	}
}

func parseListen(s string) (string, string) {
	if v, ok := strings.CutPrefix(s, "unix:"); ok {
		return "unix", v
	}
	if v, ok := strings.CutPrefix(s, "tcp:"); ok {
		return "tcp", v
	}
	return "tcp", s
}

// handleConn serves one stream: handshake line, then FLV bytes, then Finish.
func handleConn(conn net.Conn, defaults streamConfig, uploader Uploader) {
	defer conn.Close()
	r := bufio.NewReader(conn)

	line, err := r.ReadString('\n')
	if err != nil {
		fmt.Fprintf(os.Stderr, "handshake read: %v\n", err)
		return
	}

	var hs handshake
	if err := json.Unmarshal([]byte(strings.TrimSpace(line)), &hs); err != nil {
		fmt.Fprintf(conn, "E invalid handshake: %v\n", err)
		return
	}
	if hs.OutDir == "" {
		fmt.Fprint(conn, "E outDir is required\n")
		return
	}

	cfg := defaults
	if hs.HLSTime != nil {
		cfg.TargetDurMS = int64(*hs.HLSTime * 1000)
	}
	if hs.ListSize != nil {
		cfg.ListSize = *hs.ListSize
	}
	if hs.DeleteThreshold != nil {
		cfg.DeleteThreshold = *hs.DeleteThreshold
	}

	if err := os.MkdirAll(hs.OutDir, 0o755); err != nil {
		fmt.Fprintf(conn, "E cannot create out dir: %v\n", err)
		return
	}

	seg := NewSegmenter(hs.OutDir, cfg.TargetDurMS, cfg.ListSize, cfg.DeleteThreshold)
	if uploader != nil {
		seg.SetUploader(uploader)
	}

	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		progressLoop(seg, conn, stop, "P ")
	}()

	streamErr := streamTags(r, seg)
	close(stop)
	wg.Wait()

	if streamErr != nil {
		// End only this stream; the daemon keeps serving the others.
		fmt.Fprintf(conn, "E %v\n", streamErr)
		if err := seg.Finish(); err != nil {
			fmt.Fprintf(os.Stderr, "finish error: %v\n", err)
		}
		return
	}
	if err := seg.Finish(); err != nil {
		fmt.Fprintf(conn, "E finish: %v\n", err)
		return
	}
	fmt.Fprint(conn, "D\n")
}
