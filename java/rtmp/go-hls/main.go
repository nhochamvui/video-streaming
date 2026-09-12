//go:build !rtmp

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
	outDir := flag.String("out-dir", "", "output directory for init.mp4 / output_N.m4s / output.m3u8")
	targetDur := flag.Float64("hls-time", 1.0, "target segment duration in seconds")
	listSize := flag.Int("hls-list-size", 10, "playlist window size")
	deleteThreshold := flag.Int("hls-delete-threshold", 1, "keep N segments beyond the window before deleting")
	s3Bucket := flag.String("s3-bucket", "", "S3 bucket to mirror HLS output to (empty = local only)")
	s3Region := flag.String("s3-region", "", "AWS region for S3 (defaults to SDK/IMDS resolution)")
	flag.Parse()

	if *outDir == "" {
		fmt.Fprintln(os.Stderr, "usage: hls-segmenter --out-dir <dir> [--hls-time 1] [--hls-list-size 10] [--hls-delete-threshold 1] [--s3-bucket <bucket>] [--s3-region <region>]")
		os.Exit(1)
	}

	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "cannot create out dir: %v\n", err)
		os.Exit(1)
	}

	seg := NewSegmenter(*outDir, int64(*targetDur*1000), *listSize, *deleteThreshold)

	ctx := context.Background()
	var uploader *s3Uploader
	if *s3Bucket != "" {
		u, err := newS3Uploader(ctx, *s3Bucket, *s3Region)
		if err != nil {
			fmt.Fprintf(os.Stderr, "cannot init S3 uploader: %v\n", err)
			os.Exit(1)
		}
		uploader = u
		uploader.Start()
		seg.SetUploader(uploader)
	}

	// periodic progress output (parsed by the Java host for fps/bitrate/speed stats)
	stop := make(chan struct{})
	go func() {
		t := time.NewTicker(1 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				fmt.Fprintln(os.Stdout, seg.ProgressLine())
			case <-stop:
				return
			}
		}
	}()

	run(seg)

	close(stop)
	if err := seg.Finish(); err != nil {
		fmt.Fprintf(os.Stderr, "finish error: %v\n", err)
		os.Exit(1)
	}

	if uploader != nil {
		closeCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
		defer cancel()
		uploader.Close(closeCtx)
	}
}

func run(seg *Segmenter) {
	r := io.Reader(os.Stdin)

	// FLV header: "FLV" (3) version(1) flags(1) headerSize(4 BE)
	hdr := make([]byte, 9)
	if _, err := io.ReadFull(r, hdr); err != nil {
		fmt.Fprintf(os.Stderr, "reading FLV header: %v\n", err)
		os.Exit(1)
	}
	if string(hdr[0:3]) != "FLV" {
		fmt.Fprintf(os.Stderr, "input is not FLV (got %q)\n", hdr[0:3])
		os.Exit(1)
	}

	// previous tag size (4 bytes)
	pvs := make([]byte, 4)
	if _, err := io.ReadFull(r, pvs); err != nil {
		fmt.Fprintf(os.Stderr, "reading prev tag size: %v\n", err)
		os.Exit(1)
	}

	for {
		th := make([]byte, 11)
		if _, err := io.ReadFull(r, th); err != nil {
			if err == io.EOF {
				return
			}
			fmt.Fprintf(os.Stderr, "reading tag header: %v\n", err)
			os.Exit(1)
		}
		tagType := th[0]
		dataSize := int(th[1])<<16 | int(th[2])<<8 | int(th[3])
		// big-endian: b[4]=ts>>16, b[5]=ts>>8, b[6]=ts&0xFF, b[7]=ts>>24
		ts := int(th[4])<<16 | int(th[5])<<8 | int(th[6]) | int(th[7])<<24

		payload := make([]byte, dataSize)
		if _, err := io.ReadFull(r, payload); err != nil {
			fmt.Fprintf(os.Stderr, "reading tag payload: %v\n", err)
			os.Exit(1)
		}
		if _, err := io.ReadFull(r, pvs); err != nil {
			fmt.Fprintf(os.Stderr, "reading prev tag size: %v\n", err)
			os.Exit(1)
		}

		if err := seg.Process(tagType, ts, payload); err != nil {
			fmt.Fprintf(os.Stderr, "processing tag: %v\n", err)
			os.Exit(1)
		}
	}
}