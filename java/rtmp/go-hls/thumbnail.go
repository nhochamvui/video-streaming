package main

import (
	"bytes"
	"fmt"
	"image"
	"image/jpeg"
	"log"
	"os"
	"path/filepath"

	"github.com/Eyevinn/hi264/pkg/decoder"
	"github.com/bluenviron/mediacommon/v2/pkg/codecs/h264"
)

const thumbnailQuality = 80

// captureThumbnail decodes the first IDR frame from SPS+PPS+AVCC keyframe data
// and writes a JPEG thumbnail to <outDir>/../thumbnail.jpg.
func captureThumbnail(outDir string, sps, pps []byte, avccBody []byte) error {
	var au h264.AVCC
	if err := au.Unmarshal(avccBody); err != nil {
		return fmt.Errorf("parse avcc: %w", err)
	}
	if len(au) == 0 {
		return fmt.Errorf("no NALUs in keyframe")
	}

	nalus := make([][]byte, 0, 2+len(au))
	nalus = append(nalus, sps, pps)
	for _, n := range au {
		nalus = append(nalus, n)
	}

	dec := decoder.New()
	f, err := dec.DecodeNALUs(nalus)
	if err != nil {
		return fmt.Errorf("decode IDR: %w", err)
	}

	img := image.NewYCbCr(image.Rect(0, 0, f.Width, f.Height), image.YCbCrSubsampleRatio420)
	yuv := f.YUV420Bytes()
	copyYCbCr(img, yuv, f.Width, f.Height)

	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: thumbnailQuality}); err != nil {
		return fmt.Errorf("encode jpeg: %w", err)
	}

	thumbPath := filepath.Join(filepath.Dir(outDir), "thumbnail.jpg")
	if err := os.WriteFile(thumbPath, buf.Bytes(), 0o644); err != nil {
		return fmt.Errorf("write thumbnail: %w", err)
	}
	log.Printf("thumbnail captured: %s (%dx%d, %d bytes)", thumbPath, f.Width, f.Height, buf.Len())
	return nil
}

func copyYCbCr(img *image.YCbCr, yuv []byte, w, h int) {
	ySize := w * h
	cw := w / 2
	ch := h / 2
	cSize := cw * ch

	for y := 0; y < h; y++ {
		copy(img.Y[y*img.YStride:y*img.YStride+w], yuv[y*w:y*w+w])
	}
	for y := 0; y < ch; y++ {
		copy(img.Cb[y*img.CStride:y*img.CStride+cw], yuv[ySize+y*cw:ySize+y*cw+cw])
		copy(img.Cr[y*img.CStride:y*img.CStride+cw], yuv[ySize+cSize+y*cw:ySize+cSize+y*cw+cw])
	}
}
