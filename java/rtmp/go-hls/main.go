package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"time"
)

func main() {
	outDir := flag.String("out-dir", "", "single-stream mode: output directory for init.mp4 / output_N.m4s / output.m3u8")
	listen := flag.String("listen", "", "daemon mode: listen address, e.g. unix:/tmp/hls-segmenter.sock or tcp:127.0.0.1:9977")
	targetDur := flag.Float64("hls-time", 1.0, "target segment duration in seconds")
	listSize := flag.Int("hls-list-size", 10, "playlist window size")
	deleteThreshold := flag.Int("hls-delete-threshold", 1, "keep N segments beyond the window before deleting")
	s3Bucket := flag.String("s3-bucket", "", "S3 bucket to mirror HLS output to (empty = local only)")
	s3Region := flag.String("s3-region", "", "AWS region for S3 (defaults to SDK/IMDS resolution)")
	flag.Parse()

	cfg := streamConfig{
		TargetDurMS:     int64(*targetDur * 1000),
		ListSize:        *listSize,
		DeleteThreshold: *deleteThreshold,
	}

	ctx := context.Background()
	var uploader *s3Uploader
	if *s3Bucket != "" {
		u, err := newS3Uploader(ctx, *s3Bucket, *s3Region)
		if err != nil {
			fmt.Fprintf(os.Stderr, "cannot init S3 uploader: %v\n", err)
			os.Exit(1)
		}
		u.Start()
		uploader = u
	}
	defer func() {
		if uploader != nil {
			closeCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
			defer cancel()
			uploader.Close(closeCtx)
		}
	}()

	// Daemon mode: one process serves many streams (one connection each).
	if *listen != "" {
		// A nil *s3Uploader assigned straight into the Uploader parameter is a
		// typed-nil interface (non-nil interface holding a nil pointer), so
		// serve/handleConn would treat local-only mode as S3-enabled and the
		// first upload would panic on the nil receiver. Keep the interface
		// value untyped nil instead.
		var up Uploader
		if uploader != nil {
			up = uploader
		}
		if err := serve(*listen, cfg, up); err != nil {
			fmt.Fprintf(os.Stderr, "daemon error: %v\n", err)
			os.Exit(1)
		}
		return
	}

	// Single-stream mode (back-compat and tests): FLV on stdin.
	if *outDir == "" {
		fmt.Fprintln(os.Stderr, "usage: hls-segmenter --out-dir <dir> ...  |  hls-segmenter --listen unix:/path ...")
		os.Exit(1)
	}
	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "cannot create out dir: %v\n", err)
		os.Exit(1)
	}

	seg := NewSegmenter(*outDir, cfg.TargetDurMS, cfg.ListSize, cfg.DeleteThreshold)
	if uploader != nil {
		seg.SetUploader(uploader)
	}

	stop := make(chan struct{})
	go progressLoop(seg, os.Stdout, stop, "")

	streamErr := streamTags(os.Stdin, seg)
	close(stop)

	if streamErr != nil {
		fmt.Fprintf(os.Stderr, "stream error: %v\n", streamErr)
		if err := seg.Finish(); err != nil {
			fmt.Fprintf(os.Stderr, "finish error: %v\n", err)
		}
		os.Exit(1)
	}
	if err := seg.Finish(); err != nil {
		fmt.Fprintf(os.Stderr, "finish error: %v\n", err)
		os.Exit(1)
	}
}

// progressLoop writes "<prefix><ProgressLine()>" every second until stop.
func progressLoop(seg *Segmenter, w io.Writer, stop <-chan struct{}, prefix string) {
	t := time.NewTicker(1 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-t.C:
			fmt.Fprintf(w, "%s%s\n", prefix, seg.ProgressLine())
		case <-stop:
			return
		}
	}
}

// streamTags reads an FLV stream and feeds every tag to the segmenter.
// A truncated tail (EOF mid-tag) is treated as a clean end of stream.
func streamTags(r io.Reader, seg *Segmenter) error {
	hdr := make([]byte, 9)
	if _, err := io.ReadFull(r, hdr); err != nil {
		return fmt.Errorf("reading FLV header: %w", err)
	}
	if string(hdr[0:3]) != "FLV" {
		return fmt.Errorf("input is not FLV (got %q)", hdr[0:3])
	}

	pvs := make([]byte, 4)
	if _, err := io.ReadFull(r, pvs); err != nil {
		return fmt.Errorf("reading prev tag size: %w", err)
	}

	for {
		th := make([]byte, 11)
		if _, err := io.ReadFull(r, th); err != nil {
			if err == io.EOF || err == io.ErrUnexpectedEOF {
				return nil
			}
			return fmt.Errorf("reading tag header: %w", err)
		}
		tagType := th[0]
		dataSize := int(th[1])<<16 | int(th[2])<<8 | int(th[3])
		ts := int(th[4])<<16 | int(th[5])<<8 | int(th[6]) | int(th[7])<<24

		payload := make([]byte, dataSize)
		if _, err := io.ReadFull(r, payload); err != nil {
			return fmt.Errorf("reading tag payload: %w", err)
		}
		if _, err := io.ReadFull(r, pvs); err != nil {
			return fmt.Errorf("reading prev tag size: %w", err)
		}
		if err := seg.Process(tagType, ts, payload); err != nil {
			return fmt.Errorf("processing tag: %w", err)
		}
	}
}
