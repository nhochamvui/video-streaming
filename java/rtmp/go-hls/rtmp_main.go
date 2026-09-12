//go:build rtmp

package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"runtime"
	"sync"
	"time"
)

func main() {
	port := flag.Int("port", 1935, "RTMP listen port")
	outRoot := flag.String("out-root", "./hls", "HLS output root directory")
	maxStreams := flag.Int("max-streams", 18, "Max concurrent streams")
	memInterval := flag.Int("mem-interval", 10, "Memory stats interval in seconds")
	flag.Parse()

	if err := os.MkdirAll(*outRoot, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "cannot create out-root: %v\n", err)
		os.Exit(1)
	}

	addr := fmt.Sprintf("0.0.0.0:%d", *port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "listen %s: %v\n", addr, err)
		os.Exit(1)
	}
	log.Printf("rtmp-hls listening on %s, out-root=%s, max-streams=%d", addr, *outRoot, *maxStreams)

	var (
		activeStreams int
		mu            sync.Mutex
	)

	go logMemStats(*memInterval)

	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept error: %v", err)
			continue
		}
		go newRtmpConn(conn, *outRoot).run(*maxStreams, &activeStreams, &mu)
	}
}

func logMemStats(interval int) {
	t := time.NewTicker(time.Duration(interval) * time.Second)
	defer t.Stop()
	for range t.C {
		var m runtime.MemStats
		runtime.ReadMemStats(&m)
		log.Printf("[mem] alloc=%dMB sys=%dMB heap_idle=%dMB heap_inuse=%dMB heap_objects=%d",
			m.Alloc/1024/1024, m.Sys/1024/1024,
			m.HeapIdle/1024/1024, m.HeapInuse/1024/1024, m.HeapObjects)
	}
}
