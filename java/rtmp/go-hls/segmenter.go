package main

import (
	"encoding/binary"
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/bluenviron/mediacommon/v2/pkg/codecs/h264"
	"github.com/bluenviron/mediacommon/v2/pkg/codecs/mpeg4audio"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/mp4/codecs"
)

const (
	tagTypeAudio  = 8
	tagTypeVideo  = 9
	tagTypeScript = 18

	flvVideoCodecAVC = 7
	flvAudioCodecAAC = 10

	videoTimeScale = 1000
)

type sample struct {
	dts   int64 // ms
	fmp4  *fmp4.Sample
	key   bool
	aud   bool
	durMS uint32
}

type segment struct {
	num   int
	durMS int64
}

// Segmenter converts an FLV byte stream into fMP4 HLS (init.mp4, output_N.m4s, output.m3u8),
// replicating the layout produced by ffmpeg's HLS fMP4 muxer so the existing Java app can
// consume the output without changes.
type Segmenter struct {
	outDir          string
	targetDurMS     int64
	listSize        int
	deleteThreshold int

	sequenceNum int
	videoCodec  *codecs.H264
	audioCodec  *codecs.MPEG4Audio
	audioRate   int
	initWritten bool

	segmentStarted bool
	pendingVideo   *sample
	curV           []*sample
	curA           []*sample
	written        []segment

	pruneCursor    int // index into written[] up to which local files were pruned
	s3DeleteCursor int // index into written[] up to which S3 objects were deleted

	uploader        Uploader
	lastMasterMtime int64

	thumbnailCaptured bool

	frames    int64
	bytes     int64
	startedAt time.Time
}

// SetUploader attaches the S3 mirror; nil (default) keeps local-only behavior.
func (s *Segmenter) SetUploader(u Uploader) {
	s.uploader = u
}

// s3Prefix derives the object-key prefix from the output directory layout
// <root>/<stream>/hd, matching the S3 layout hls/<stream>/hd/...
func (s *Segmenter) s3Prefix() string {
	return "hls/" + filepath.Base(filepath.Dir(s.outDir))
}

func (s *Segmenter) uploadSegment(name string) {
	if s.uploader == nil {
		return
	}
	s.uploader.PutSegment(s.s3Prefix()+"/hd/"+name, filepath.Join(s.outDir, name))
}

func (s *Segmenter) uploadPlaylist(name string) {
	if s.uploader == nil {
		return
	}
	s.uploader.PutPlaylist(s.s3Prefix()+"/hd/"+name, filepath.Join(s.outDir, name))
}

func (s *Segmenter) uploadMasterIfChanged() {
	if s.uploader == nil {
		return
	}
	master := filepath.Join(filepath.Dir(s.outDir), "master.m3u8")
	info, err := os.Stat(master)
	if err != nil || info.ModTime().UnixNano() == s.lastMasterMtime {
		return
	}
	s.lastMasterMtime = info.ModTime().UnixNano()
	s.uploader.PutPlaylistReliable(s.s3Prefix()+"/master.m3u8", master)
}

func NewSegmenter(outDir string, targetDurMS int64, listSize, deleteThreshold int) *Segmenter {
	return &Segmenter{
		outDir:          outDir,
		targetDurMS:     targetDurMS,
		listSize:        listSize,
		deleteThreshold: deleteThreshold,
		sequenceNum:     1,
		startedAt:       time.Now(),
	}
}

func (s *Segmenter) ProgressLine() string {
	elapsed := time.Since(s.startedAt).Seconds()
	if elapsed <= 0 {
		elapsed = 0.001
	}
	fps := float64(s.frames) / elapsed
	bitrate := float64(s.bytes) * 8 / elapsed / 1000
	return fmt.Sprintf("frame=%d fps=%.2f bitrate=%.2fkbits/s speed=%.2fx", s.frames, fps, bitrate, 1.0)
}

func (s *Segmenter) Process(tagType byte, timestamp int, payload []byte) error {
	s.bytes += int64(len(payload)) + 15
	switch tagType {
	case tagTypeVideo:
		return s.processVideo(timestamp, payload)
	case tagTypeAudio:
		return s.processAudio(timestamp, payload)
	}
	return nil
}

func (s *Segmenter) processVideo(timestamp int, payload []byte) error {
	if len(payload) < 5 {
		return nil
	}
	codecID := payload[0] & 0x0F
	if codecID != flvVideoCodecAVC {
		return nil
	}
	avcPacketType := payload[1]
	cts := int32(int8(payload[2]))<<16 | int32(payload[3])<<8 | int32(payload[4])
	body := payload[5:]

	switch avcPacketType {
	case 0: // AVCDecoderConfigurationRecord
		return s.parseAVCConfig(body)
	case 1: // AVC NALU access unit
		return s.addVideoSample(int64(timestamp), cts, body)
	}
	return nil
}

func (s *Segmenter) parseAVCConfig(body []byte) error {
	// AVCDecoderConfigurationRecord:
	// version(1) profile(1) compat(1) level(1) lengthSize(1) numSPS(1) [sps...] numPPS(1) [pps...]
	if len(body) < 7 {
		return nil
	}
	pos := 5
	numSPS := int(body[pos] & 0x1F)
	pos++
	var sps []byte
	for i := 0; i < numSPS && pos+2 <= len(body); i++ {
		l := int(binary.BigEndian.Uint16(body[pos:]))
		pos += 2
		if pos+l > len(body) {
			break
		}
		sps = body[pos : pos+l]
		pos += l
	}
	if pos+1 > len(body) {
		return nil
	}
	numPPS := int(body[pos] & 0x1F)
	pos++
	var pps []byte
	for i := 0; i < numPPS && pos+2 <= len(body); i++ {
		l := int(binary.BigEndian.Uint16(body[pos:]))
		pos += 2
		if pos+l > len(body) {
			break
		}
		pps = body[pos : pos+l]
		pos += l
	}
	if len(sps) == 0 || len(pps) == 0 {
		return nil
	}
	s.videoCodec = &codecs.H264{SPS: sps, PPS: pps}
	return s.ensureInit(false)
}

func (s *Segmenter) processAudio(timestamp int, payload []byte) error {
	if len(payload) < 2 {
		return nil
	}
	format := payload[0] >> 4
	if format != flvAudioCodecAAC {
		return nil
	}
	aacType := payload[1]
	body := payload[2:]
	switch aacType {
	case 0: // AudioSpecificConfig
		var asc mpeg4audio.AudioSpecificConfig
		if err := asc.Unmarshal(body); err != nil {
			return nil
		}
		s.audioCodec = &codecs.MPEG4Audio{Config: asc}
		s.audioRate = asc.SampleRate
		return s.ensureInit(false)
	case 1: // raw AAC frame
		if s.audioRate == 0 {
			return nil
		}
		s.curA = append(s.curA, &sample{
			dts:  int64(timestamp),
			fmp4: &fmp4.Sample{Payload: body, Duration: mpeg4audio.SamplesPerAccessUnit},
			aud:  true,
		})
		return nil
	}
	return nil
}

func (s *Segmenter) addVideoSample(dts int64, cts int32, body []byte) error {
	s.frames++

	var au h264.AVCC
	key := false
	if err := au.Unmarshal(body); err == nil {
		key = h264.IsRandomAccess(au)
	} else if len(body) >= 5 && body[4]&0x1F == 5 {
		key = true
	}

	if !s.segmentStarted {
		if !key {
			return nil // skip until first keyframe
		}
		s.segmentStarted = true
		s.pendingVideo = &sample{dts: dts, fmp4: &fmp4.Sample{Payload: body, PTSOffset: cts, IsNonSyncSample: !key}, key: true}
		s.tryCaptureThumbnail(body)
		return nil
	}

	if s.pendingVideo != nil {
		pv := s.pendingVideo
		pv.durMS = uint32(dts - pv.dts)
		pv.fmp4.Duration = pv.durMS
		s.curV = append(s.curV, pv)
		s.pendingVideo = nil
	}

	segStart := s.segStartDTS()
	newPending := &sample{dts: dts, fmp4: &fmp4.Sample{Payload: body, PTSOffset: cts, IsNonSyncSample: !key}, key: key}
	s.pendingVideo = newPending

	if dts-segStart >= s.targetDurMS {
		if err := s.rotate(); err != nil {
			return err
		}
	}
	return nil
}

func (s *Segmenter) segStartDTS() int64 {
	if len(s.curV) > 0 {
		return s.curV[0].dts
	}
	if s.pendingVideo != nil {
		return s.pendingVideo.dts
	}
	return 0
}

// rotate closes the current segment and starts a new one. The held pending sample
// becomes the first sample of the next segment.
func (s *Segmenter) rotate() error {
	seg := s.buildSegment()
	if seg != nil {
		s.written = append(s.written, *seg)
	}
	s.curV = nil
	s.curA = nil
	s.sequenceNum++
	s.emitPlaylist()
	return nil
}

func (s *Segmenter) buildSegment() *segment {
	start := s.segStartDTS()
	if len(s.curV) == 0 && len(s.curA) == 0 {
		return nil
	}

	if err := s.ensureInit(true); err != nil {
		return nil
	}

	// segment duration = next sample DTS - segmentStart (segments are split by time)
	end := start
	if s.pendingVideo != nil {
		end = s.pendingVideo.dts
	} else if len(s.curV) > 0 {
		end = s.curV[len(s.curV)-1].dts
	}
	if end < start {
		end = start
	}

	part := s.buildPart()
	if part == nil {
		return nil
	}

	durMS := end - start
	if durMS <= 0 {
		return nil
	}

	fileName := fmt.Sprintf("output_%d.m4s", s.sequenceNum)
	if err := s.writeFrag(part, fileName); err != nil {
		return nil
	}

	return &segment{num: s.sequenceNum, durMS: durMS}
}

func (s *Segmenter) buildPart() *fmp4.Part {
	pt := &fmp4.Part{SequenceNumber: uint32(s.sequenceNum)}

	if len(s.curV) > 0 {
		vt := &fmp4.PartTrack{ID: 1, BaseTime: uint64(s.curV[0].dts)}
		for _, sv := range s.curV {
			vt.Samples = append(vt.Samples, sv.fmp4)
		}
		pt.Tracks = append(pt.Tracks, vt)
	}
	if len(s.curA) > 0 {
		at := &fmp4.PartTrack{ID: 2, BaseTime: uint64(s.curA[0].dts) * uint64(s.audioRate) / videoTimeScale}
		for _, sa := range s.curA {
			at.Samples = append(at.Samples, sa.fmp4)
		}
		pt.Tracks = append(pt.Tracks, at)
	}
	if len(pt.Tracks) == 0 {
		return nil
	}
	return pt
}

// ensureInit writes init.mp4 once both codecs are known. force allows writing with
// whatever codecs are available (used when a segment is about to be written).
func (s *Segmenter) ensureInit(force bool) error {
	if s.initWritten {
		return nil
	}
	if s.videoCodec == nil && s.audioCodec == nil {
		return nil
	}
	if !force && (s.videoCodec == nil || s.audioCodec == nil) {
		return nil // wait for both
	}

	tracks := []*fmp4.InitTrack{}
	if s.videoCodec != nil {
		tracks = append(tracks, &fmp4.InitTrack{ID: 1, TimeScale: videoTimeScale, Codec: s.videoCodec})
	}
	if s.audioCodec != nil {
		tracks = append(tracks, &fmp4.InitTrack{ID: 2, TimeScale: uint32(s.audioRate), Codec: s.audioCodec})
	}
	if len(tracks) == 0 {
		return nil
	}

	init := &fmp4.Init{Tracks: tracks}
	if err := writeSeekerAtomic(s.outDir, "init.mp4", func(f *os.File) error {
		return init.Marshal(f)
	}); err != nil {
		return err
	}
	s.initWritten = true
	s.uploadSegment("init.mp4")
	return nil
}

func (s *Segmenter) writeFrag(part *fmp4.Part, name string) error {
	err := writeSeekerAtomic(s.outDir, name, func(f *os.File) error {
		return part.Marshal(f)
	})
	if err != nil {
		return err
	}
	s.uploadSegment(name)
	return nil
}

func writeSeekerAtomic(dir, name string, fn func(*os.File) error) error {
	tmp := filepath.Join(dir, "."+name+".tmp")
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	ok := false
	defer func() {
		if !ok {
			_ = os.Remove(tmp)
		}
	}()
	if err := fn(f); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, filepath.Join(dir, name)); err != nil {
		return err
	}
	ok = true
	return nil
}

func (s *Segmenter) emitPlaylist() {
	entries := s.written
	if len(entries) > s.listSize {
		entries = entries[len(entries)-s.listSize:]
	}

	var sb strings.Builder
	sb.WriteString("#EXTM3U\n")
	sb.WriteString("#EXT-X-VERSION:7\n")

	maxDur := 0.0
	for _, e := range entries {
		d := float64(e.durMS) / 1000.0
		if d > maxDur {
			maxDur = d
		}
	}
	target := int(math.Ceil(maxDur))
	if target < 1 {
		target = 1
	}
	sb.WriteString(fmt.Sprintf("#EXT-X-TARGETDURATION:%d\n", target))

	if len(entries) > 0 {
		sb.WriteString(fmt.Sprintf("#EXT-X-MEDIA-SEQUENCE:%d\n", entries[0].num))
	}
	sb.WriteString("#EXT-X-MAP:URI=\"init.mp4\"\n")

	for _, e := range entries {
		sb.WriteString(fmt.Sprintf("#EXTINF:%.6f,\n", float64(e.durMS)/1000.0))
		sb.WriteString(fmt.Sprintf("output_%d.m4s\n", e.num))
	}

	// delete segments that fell out of the window beyond the threshold. Each
	// segment is pruned exactly once: the cursors track how far into written[]
	// we have already deleted, so a full re-scan does not re-fire deletes on
	// every rotation (which flooded the upload queue and dropped playlist PUTs).
	if len(s.written) > s.listSize {
		oldestKeep := s.written[len(s.written)-s.listSize].num - s.deleteThreshold
		// keep a wider window on S3 (15 segments) so viewers behind by a few
		// seconds on stale playlists still find their segments
		s3Floor := s.written[len(s.written)-1].num - 15
		end := len(s.written) - s.listSize
		for s.pruneCursor < end && s.written[s.pruneCursor].num < oldestKeep {
			_ = os.Remove(filepath.Join(s.outDir, fmt.Sprintf("output_%d.m4s", s.written[s.pruneCursor].num)))
			s.pruneCursor++
		}
		for s.s3DeleteCursor < end && s.written[s.s3DeleteCursor].num < s3Floor {
			if s.uploader != nil {
				s.uploader.Delete(s.s3Prefix() + "/hd/" + fmt.Sprintf("output_%d.m4s", s.written[s.s3DeleteCursor].num))
			}
			s.s3DeleteCursor++
		}
	}

	tmp := filepath.Join(s.outDir, ".output.m3u8.tmp")
	if err := os.WriteFile(tmp, []byte(sb.String()), 0o644); err != nil {
		return
	}
	_ = os.Rename(tmp, filepath.Join(s.outDir, "output.m3u8"))

	s.uploadPlaylist("output.m3u8")
	s.uploadMasterIfChanged()
}

// tryCaptureThumbnail decodes the first keyframe into a JPEG thumbnail.
// Errors are logged but do not affect the stream.
func (s *Segmenter) tryCaptureThumbnail(avccBody []byte) {
	if s.thumbnailCaptured || s.videoCodec == nil {
		return
	}
	s.thumbnailCaptured = true

	if err := captureThumbnail(s.outDir, s.videoCodec.SPS, s.videoCodec.PPS, avccBody); err != nil {
		log.Printf("thumbnail capture failed: %v", err)
		return
	}
	if s.uploader != nil {
		thumbPath := filepath.Join(filepath.Dir(s.outDir), "thumbnail.jpg")
		s.uploader.PutThumbnail(s.s3Prefix()+"/thumbnail.jpg", thumbPath)
	}
}

// Finish flushes trailing samples and appends ENDLIST.
func (s *Segmenter) Finish() error {
	if s.pendingVideo != nil {
		dur := s.targetDurMS
		if len(s.curV) > 0 {
			dur = int64(s.curV[len(s.curV)-1].durMS)
		}
		if dur <= 0 {
			dur = s.targetDurMS
		}
		s.pendingVideo.durMS = uint32(dur)
		s.pendingVideo.fmp4.Duration = uint32(dur)
		s.curV = append(s.curV, s.pendingVideo)
		s.pendingVideo = nil
	}

	if seg := s.buildSegment(); seg != nil {
		s.written = append(s.written, *seg)
		s.sequenceNum++
	}

	s.emitPlaylist()

	// append ENDLIST to the final playlist
	pl := filepath.Join(s.outDir, "output.m3u8")
	if data, err := os.ReadFile(pl); err == nil {
		str := string(data)
		if !strings.Contains(str, "#EXT-X-ENDLIST") {
			_ = os.WriteFile(pl, []byte(str+"#EXT-X-ENDLIST\n"), 0o644)
		}
	}

	// stream ended: remove the stream's objects from S3
	if s.uploader != nil {
		s.uploader.DeleteAll(s.s3Prefix())
	}
	return nil
}