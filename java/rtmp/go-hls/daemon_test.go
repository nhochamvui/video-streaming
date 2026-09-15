package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// buildTestFLV assembles a small synthetic FLV stream (AVC + AAC config, then frames).
func buildTestFLV(frames int) []byte {
	var buf bytes.Buffer
	buf.Write([]byte{'F', 'L', 'V', 1, 5, 0, 0, 0, 9})
	buf.Write([]byte{0, 0, 0, 0})

	writeTag := func(typ byte, ts int, payload []byte) {
		hdr := make([]byte, 11)
		hdr[0] = typ
		hdr[1] = byte(len(payload) >> 16)
		hdr[2] = byte(len(payload) >> 8)
		hdr[3] = byte(len(payload))
		hdr[4] = byte(ts >> 16)
		hdr[5] = byte(ts >> 8)
		hdr[6] = byte(ts)
		hdr[7] = byte(ts >> 24)
		buf.Write(hdr)
		buf.Write(payload)
		pvs := 11 + len(payload)
		buf.Write([]byte{byte(pvs >> 24), byte(pvs >> 16), byte(pvs >> 8), byte(pvs)})
	}

	writeTag(9, 0, testAVCConfig())
	writeTag(8, 0, testAACConfig())
	for i := 0; i < frames; i++ {
		ts := i * 33
		writeTag(9, ts, testAVCFrame(i%15 == 0, 0))
		writeTag(8, ts, testAACFrame())
	}
	return buf.Bytes()
}

func startTestDaemon(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	cfg := streamConfig{TargetDurMS: 2000, ListSize: 10, DeleteThreshold: 1}
	go func() { _ = serveListener(ln, cfg, nil) }()
	t.Cleanup(func() { _ = ln.Close() })
	return ln.Addr().String()
}

func runStream(t *testing.T, addr, outDir string, flv []byte, wg *sync.WaitGroup) {
	defer wg.Done()
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Errorf("dial: %v", err)
		return
	}
	defer conn.Close()

	hs, _ := json.Marshal(map[string]any{"outDir": outDir, "hlsTime": 2, "listSize": 10, "deleteThreshold": 1})
	if _, err := conn.Write(append(hs, '\n')); err != nil {
		t.Errorf("handshake: %v", err)
		return
	}
	if _, err := conn.Write(flv); err != nil {
		t.Errorf("flv: %v", err)
		return
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.CloseWrite()
	}

	rd := bufio.NewReader(conn)
	done := false
	for i := 0; i < 400; i++ {
		_ = conn.SetReadDeadline(time.Now().Add(20 * time.Second))
		line, err := rd.ReadString('\n')
		if err != nil {
			break
		}
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "E ") {
			t.Errorf("stream error: %s", line)
			return
		}
		if line == "D" {
			done = true
			break
		}
	}
	if !done {
		t.Errorf("did not receive D for %s", outDir)
	}
}

func TestDaemonServesConcurrentStreams(t *testing.T) {
	addr := startTestDaemon(t)
	flv := buildTestFLV(150)
	dirs := []string{filepath.Join(t.TempDir(), "hd"), filepath.Join(t.TempDir(), "hd")}

	var wg sync.WaitGroup
	for _, d := range dirs {
		wg.Add(1)
		go runStream(t, addr, d, flv, &wg)
	}
	wg.Wait()

	for _, d := range dirs {
		if _, err := os.Stat(filepath.Join(d, "init.mp4")); err != nil {
			t.Errorf("missing init.mp4 in %s", d)
		}
		if _, err := os.Stat(filepath.Join(d, "output.m3u8")); err != nil {
			t.Errorf("missing output.m3u8 in %s", d)
		}
	}
}

func TestDaemonRejectsInvalidHandshake(t *testing.T) {
	addr := startTestDaemon(t)
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	if _, err := conn.Write([]byte("not-json\n")); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !strings.HasPrefix(line, "E ") {
		t.Fatalf("expected E, got %q", line)
	}
}

// TestDaemonTypedNilUploaderDoesNotPanic regression-tests the typed-nil trap:
// main used to pass its concrete nil *s3Uploader straight into serve, producing
// a non-nil Uploader interface that handleConn attached to every segmenter; the
// first upload (init.mp4) then sent on the nil receiver's queue and crashed the
// daemon. With no --s3-bucket (local-only mode) the daemon must serve a stream
// through segment rotation and finish cleanly.
func TestDaemonTypedNilUploaderDoesNotPanic(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })

	// Reproduce the wiring that used to reach serve in main: an uninitialized
	// *s3Uploader stored in the Uploader interface is a typed nil (the
	// interface itself compares non-nil).
	var typedNil *s3Uploader
	var up Uploader = typedNil
	cfg := streamConfig{TargetDurMS: 2000, ListSize: 10, DeleteThreshold: 1}
	go func() { _ = serveListener(ln, cfg, up) }()

	// ~5s of A/V at hlsTime=2 => at least two mid-stream rotations, so the
	// uploadSegment path that used to panic is exercised.
	dir := filepath.Join(t.TempDir(), "hd")
	var wg sync.WaitGroup
	wg.Add(1)
	runStream(t, ln.Addr().String(), dir, buildTestFLV(150), &wg)
	wg.Wait()

	// runStream already asserts a clean "D" ending; also prove the stream
	// actually rotated so the regression test cannot silently pass weakly.
	if _, err := os.Stat(filepath.Join(dir, "output_1.m4s")); err != nil {
		t.Errorf("expected rotated segment output_1.m4s in %s: %v", dir, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "output.m3u8")); err != nil {
		t.Errorf("expected playlist in %s: %v", dir, err)
	}
}
