//go:build rtmp

package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	handshakeLen   = 1536
	defaultVersion = 3
	msgTypeCommand = 20
)

type rtmpConn struct {
	conn          net.Conn
	inChunkSize   int
	outChunkSize  int
	prevHeaders   map[int]*rtmpHeader
	chunkPayloads map[int]*bytes.Buffer
	streamID      int
	startTime     time.Time

	streamName string
	seg        *Segmenter
	hlsRoot    string
}

type rtmpHeader struct {
	csid          int
	fmt           int
	timestamp     int
	length        int
	typeID        int
	messageStream int
}

func newRtmpConn(conn net.Conn, hlsRoot string) *rtmpConn {
	return &rtmpConn{
		conn:          conn,
		inChunkSize:   128,
		outChunkSize:  128,
		prevHeaders:   make(map[int]*rtmpHeader),
		chunkPayloads: make(map[int]*bytes.Buffer),
		startTime:     time.Now(),
		hlsRoot:       hlsRoot,
	}
}

func (c *rtmpConn) run(maxStreams int, activeStreams *int, mu *sync.Mutex) {
	defer c.conn.Close()
	addr := c.conn.RemoteAddr().String()

	if err := c.handshake(); err != nil {
		log.Printf("[%s] handshake failed: %v", addr, err)
		return
	}
	log.Printf("[%s] handshake complete", addr)

	for {
		if err := c.readChunkMessage(maxStreams, activeStreams, mu); err != nil {
			if err != io.EOF {
				log.Printf("[%s] connection error: %v", addr, err)
			}
			c.cleanup()
			return
		}
	}
}

func (c *rtmpConn) handshake() error {
	c0c1 := make([]byte, 1+handshakeLen)
	if _, err := io.ReadFull(c.conn, c0c1); err != nil {
		return fmt.Errorf("read C0+C1: %w", err)
	}
	log.Printf("[%s] recv C0 (version=%d) + C1", c.conn.RemoteAddr(), c0c1[0])

	s0 := byte(defaultVersion)
	s1 := make([]byte, handshakeLen)
	rand.Read(s1[8:])

	if _, err := c.conn.Write(append([]byte{s0}, s1...)); err != nil {
		return fmt.Errorf("send S0+S1: %w", err)
	}
	log.Printf("[%s] sent S0 (version=%d) + S1", c.conn.RemoteAddr(), s0)

	s2 := make([]byte, handshakeLen)
	copy(s2[:3], c0c1[1:4])
	copy(s2[8:], c0c1[9:])
	if _, err := c.conn.Write(s2); err != nil {
		return fmt.Errorf("send S2: %w", err)
	}

	c2 := make([]byte, handshakeLen)
	if _, err := io.ReadFull(c.conn, c2); err != nil {
		return fmt.Errorf("read C2: %w", err)
	}
	log.Printf("[%s] recv C2", c.conn.RemoteAddr())
	return nil
}

func (c *rtmpConn) readChunkMessage(maxStreams int, activeStreams *int, mu *sync.Mutex) error {
	h, err := c.readRTMPHeader()
	if err != nil {
		return err
	}

	//log.Printf("[%s] hdr fmt=%d csid=%d ts=%d len=%d type=%d stream=%d",
		//c.conn.RemoteAddr(), h.fmt, h.csid, h.timestamp, h.length, h.typeID, h.messageStream)

	key := h.csid
	if _, ok := c.chunkPayloads[key]; !ok {
		c.chunkPayloads[key] = &bytes.Buffer{}
	}
	buf := c.chunkPayloads[key]

	remaining := h.length - buf.Len()
	if remaining <= 0 {
		c.prevHeaders[key] = h
		return c.processCompleteMessage(h, maxStreams, activeStreams, mu)
	}

	toRead := remaining
	if toRead > c.inChunkSize {
		toRead = c.inChunkSize
	}

	if _, err := io.CopyN(buf, c.conn, int64(toRead)); err != nil {
		return fmt.Errorf("read chunk payload: %w", err)
	}

	c.prevHeaders[key] = h

	if buf.Len() >= h.length {
		return c.processCompleteMessage(h, maxStreams, activeStreams, mu)
	}
	return nil
}

func (c *rtmpConn) readRTMPHeader() (*rtmpHeader, error) {
	var first [1]byte
	if _, err := io.ReadFull(c.conn, first[:]); err != nil {
		return nil, err
	}

	h := &rtmpHeader{}
	h.fmt = int((first[0] >> 6) & 0x03)
	csid := int(first[0] & 0x3F)

	if csid == 0 {
		var b [1]byte
		if _, err := io.ReadFull(c.conn, b[:]); err != nil {
			return nil, err
		}
		csid = int(b[0]) + 64
	} else if csid == 1 {
		var b [2]byte
		if _, err := io.ReadFull(c.conn, b[:]); err != nil {
			return nil, err
		}
		csid = int(b[0]) + 64 + int(b[1])*256
	}
	h.csid = csid

	switch h.fmt {
	case 0:
		ts, err := c.readInt24()
		if err != nil {
			return nil, err
		}
		if ts == 0xFFFFFF {
			var ext [4]byte
			if _, err := io.ReadFull(c.conn, ext[:]); err != nil {
				return nil, err
			}
			ts = int(binary.BigEndian.Uint32(ext[:]))
		}
		h.timestamp = ts
		h.length, err = c.readInt24()
		if err != nil {
			return nil, err
		}
		var typeBuf [1]byte
		if _, err := io.ReadFull(c.conn, typeBuf[:]); err != nil {
			return nil, err
		}
		h.typeID = int(typeBuf[0])
		var streamBuf [4]byte
		if _, err := io.ReadFull(c.conn, streamBuf[:]); err != nil {
			return nil, err
		}
		h.messageStream = int(binary.LittleEndian.Uint32(streamBuf[:]))

	case 1:
		ts, err := c.readInt24()
		if err != nil {
			return nil, err
		}
		if ts == 0xFFFFFF {
			var ext [4]byte
			if _, err := io.ReadFull(c.conn, ext[:]); err != nil {
				return nil, err
			}
			ts = int(binary.BigEndian.Uint32(ext[:]))
		}
		h.timestamp = ts
		h.length, err = c.readInt24()
		if err != nil {
			return nil, err
		}
		var typeBuf [1]byte
		if _, err := io.ReadFull(c.conn, typeBuf[:]); err != nil {
			return nil, err
		}
		h.typeID = int(typeBuf[0])
		if prev, ok := c.prevHeaders[h.csid]; ok {
			h.messageStream = prev.messageStream
			h.timestamp = prev.timestamp + h.timestamp
		}

	case 2:
		ts, err := c.readInt24()
		if err != nil {
			return nil, err
		}
		if ts == 0xFFFFFF {
			var ext [4]byte
			if _, err := io.ReadFull(c.conn, ext[:]); err != nil {
				return nil, err
			}
			ts = int(binary.BigEndian.Uint32(ext[:]))
		}
		h.timestamp = ts
		if prev, ok := c.prevHeaders[h.csid]; ok {
			h.length = prev.length
			h.typeID = prev.typeID
			h.messageStream = prev.messageStream
			h.timestamp = prev.timestamp + h.timestamp
		}

	case 3:
		if prev, ok := c.prevHeaders[h.csid]; ok {
			h.timestamp = prev.timestamp
			h.length = prev.length
			h.typeID = prev.typeID
			h.messageStream = prev.messageStream
		}
	}

	return h, nil
}

func (c *rtmpConn) readInt24() (int, error) {
	var buf [3]byte
	if _, err := io.ReadFull(c.conn, buf[:]); err != nil {
		return 0, err
	}
	return int(buf[0])<<16 | int(buf[1])<<8 | int(buf[2]), nil
}

func (c *rtmpConn) processCompleteMessage(h *rtmpHeader, maxStreams int, activeStreams *int, mu *sync.Mutex) error {
	key := h.csid
	data := c.chunkPayloads[key].Bytes()
	payload := make([]byte, len(data))
	copy(payload, data)
	c.chunkPayloads[key].Reset()

	addr := c.conn.RemoteAddr()
	switch h.typeID {
	case 1:
		if len(payload) >= 4 {
			c.inChunkSize = int(binary.BigEndian.Uint32(payload[:4]))
			log.Printf("[%s] SetChunkSize: inChunkSize=%d", addr, c.inChunkSize)
		}
	case 2:
		log.Printf("[%s] Abort message", addr)
	case 3:
		log.Printf("[%s] Acknowledgement", addr)
	case 4:
		log.Printf("[%s] UserControl", addr)
	case 5:
		log.Printf("[%s] WindowAckSize", addr)
	case 6:
		log.Printf("[%s] SetPeerBandwidth", addr)
	case 8:
		if c.seg != nil {
			ts := h.timestamp
			if ts < 0 {
				ts = 0
			}
			return c.seg.Process(byte(h.typeID), ts, payload)
		}
	case 9:
		if c.seg != nil {
			ts := h.timestamp
			if ts < 0 {
				ts = 0
			}
			return c.seg.Process(byte(h.typeID), ts, payload)
		}
	case 18:
		// AMF0 Data (stream metadata) — silently ignored
	case 20:
		log.Printf("[%s] AMF0 command payload=%d bytes", addr, len(payload))
		values, err := decodeAMF0(bytes.NewReader(payload))
		if err != nil {
			log.Printf("[%s] AMF0 decode error: %v", addr, err)
			return nil
		}
		if len(values) > 0 {
			log.Printf("[%s] decoded AMF0 command: %q (%d values)", addr, values[0].String, len(values))
		}
		return c.handleCommand(values, h.messageStream, maxStreams, activeStreams, mu)
	default:
		log.Printf("[%s] unknown typeID=%d len=%d", addr, h.typeID, len(payload))
	}
	return nil
}

func (c *rtmpConn) handleCommand(values []amf0Value, messageStreamID, maxStreams int, activeStreams *int, mu *sync.Mutex) error {
	if len(values) == 0 {
		return nil
	}
	cmd := values[0].String
	addr := c.conn.RemoteAddr()
	log.Printf("[%s] handling command: %q streamID=%d", addr, cmd, messageStreamID)

	switch cmd {
	case "connect":
		return c.cmdConnect(values, messageStreamID)
	case "releaseStream", "FCPublish", "FCUnpublish":
		return c.cmdSimpleResult(values)
	case "createStream":
		return c.cmdCreateStream(values)
	case "publish":
		return c.cmdPublish(values, messageStreamID, maxStreams, activeStreams, mu)
	case "deleteStream":
		log.Printf("[%s] deleteStream received", addr)
		return fmt.Errorf("deleteStream: closing connection")
	default:
		log.Printf("[%s] unknown command: %q", addr, cmd)
		return nil
	}
}

func (c *rtmpConn) cmdConnect(values []amf0Value, messageStreamID int) error {
	addr := c.conn.RemoteAddr()
	log.Printf("[%s] >> connect response: sending WindowAckSize + SetPeerBandwidth + SetChunkSize + _result", addr)

	c.outChunkSize = 5000
	c.sendProtocolWindowAckSize(5000000)
	c.sendProtocolSetPeerBandwidth(5000000, 2)
	c.sendProtocolSetChunkSize(c.outChunkSize)

	resp := []interface{}{
		"_result",
		1.0,
		map[string]interface{}{
			"fmsVer":       "FMS/3,0,1,123",
			"capabilities": 31.0,
		},
		map[string]interface{}{
			"level":          "status",
			"code":           "NetConnection.Connect.Success",
			"description":    "Connection succeeded",
			"objectEncoding": 0.0,
		},
	}
	return c.sendAMF0Command(resp, 0)
}

func (c *rtmpConn) cmdSimpleResult(values []amf0Value) error {
	txID := 0.0
	if len(values) > 1 {
		txID = values[1].Number
	}
	cmd := values[0].String
	log.Printf("[%s] >> %s _result", c.conn.RemoteAddr(), cmd)
	return c.sendAMF0Command([]interface{}{"_result", txID, nil}, 0)
}

func (c *rtmpConn) cmdCreateStream(values []amf0Value) error {
	txID := 0.0
	if len(values) > 1 {
		txID = values[1].Number
	}
	c.streamID++
	log.Printf("[%s] >> createStream _result streamID=%d", c.conn.RemoteAddr(), c.streamID)
	return c.sendAMF0Command([]interface{}{"_result", txID, nil, float64(c.streamID)}, 0)
}

func (c *rtmpConn) cmdPublish(values []amf0Value, messageStreamID, maxStreams int, activeStreams *int, mu *sync.Mutex) error {
	publishName := "stream"
	if len(values) > 3 {
		publishName = values[3].String
	}
	if publishName == "" {
		b := make([]byte, 8)
		rand.Read(b)
		publishName = fmt.Sprintf("stream-%x", b)
	}
	addr := c.conn.RemoteAddr()

	mu.Lock()
	if *activeStreams >= maxStreams {
		mu.Unlock()
		log.Printf("[%s] rejecting publish: at capacity (%d/%d)", addr, *activeStreams, maxStreams)
		c.sendPublishStatus(messageStreamID, "error", "NetStream.Publish.BadName", "Server at capacity")
		return fmt.Errorf("at capacity")
	}
	*activeStreams++
	mu.Unlock()

	c.streamName = publishName
	hlsDir := filepath.Join(c.hlsRoot, publishName, "hd")
	if err := os.MkdirAll(hlsDir, 0o755); err != nil {
		log.Printf("[%s] create HLS dir failed: %v", addr, err)
		return err
	}

	masterPath := filepath.Join(c.hlsRoot, publishName, "master.m3u8")
	masterContent := "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,NAME=\"HD\"\nhd/output.m3u8\n"
	os.WriteFile(masterPath, []byte(masterContent), 0o644)

	c.seg = NewSegmenter(hlsDir, 2000, 10, 1)

	log.Printf("[%s] publish started: %s", addr, publishName)
	c.sendPublishStatus(messageStreamID, "status", "NetStream.Publish.Start", "Stream is now published")
	return nil
}

func (c *rtmpConn) sendPublishStatus(messageStreamID int, level, code, description string) {
	info := map[string]interface{}{
		"level":       level,
		"code":        code,
		"description": description,
	}
	c.sendAMF0Command([]interface{}{"onStatus", 0.0, nil, info}, messageStreamID)
}

func (c *rtmpConn) sendAMF0Command(values []interface{}, streamID int) error {
	payload := encodeAMF0Values(values)
	log.Printf("[%s] send AMF0 cmd streamID=%d payload=%d bytes", c.conn.RemoteAddr(), streamID, len(payload))
	return c.sendRTMPMessage(msgTypeCommand, 3, streamID, payload)
}

func (c *rtmpConn) sendProtocolWindowAckSize(size int) error {
	payload := make([]byte, 4)
	binary.BigEndian.PutUint32(payload, uint32(size))
	log.Printf("[%s] send WindowAckSize=%d", c.conn.RemoteAddr(), size)
	return c.sendRTMPMessage(5, 2, 0, payload)
}

func (c *rtmpConn) sendProtocolSetPeerBandwidth(bandwidth, limitType int) error {
	payload := make([]byte, 5)
	binary.BigEndian.PutUint32(payload, uint32(bandwidth))
	payload[4] = byte(limitType)
	log.Printf("[%s] send SetPeerBandwidth=%d limitType=%d", c.conn.RemoteAddr(), bandwidth, limitType)
	return c.sendRTMPMessage(6, 2, 0, payload)
}

func (c *rtmpConn) sendProtocolSetChunkSize(size int) error {
	payload := make([]byte, 4)
	binary.BigEndian.PutUint32(payload, uint32(size))
	log.Printf("[%s] send SetChunkSize=%d", c.conn.RemoteAddr(), size)
	return c.sendRTMPMessage(1, 2, 0, payload)
}

func (c *rtmpConn) sendRTMPMessage(typeID, csid, streamID int, payload []byte) error {
	ts := int(time.Since(c.startTime).Milliseconds())
	tsField := ts
	useExtended := false
	if ts >= 0xFFFFFF {
		tsField = 0xFFFFFF
		useExtended = true
	}

	var header []byte
	if useExtended {
		header = make([]byte, 16)
	} else {
		header = make([]byte, 12)
	}
	header[0] = byte(csid)
	header[1] = byte(tsField >> 16)
	header[2] = byte(tsField >> 8)
	header[3] = byte(tsField)
	header[4] = byte(len(payload) >> 16)
	header[5] = byte(len(payload) >> 8)
	header[6] = byte(len(payload))
	header[7] = byte(typeID)
	binary.LittleEndian.PutUint32(header[8:12], uint32(streamID))
	if useExtended {
		binary.BigEndian.PutUint32(header[12:16], uint32(ts))
	}

	c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	defer c.conn.SetWriteDeadline(time.Time{})

	written := 0
	for written < len(payload) {
		remaining := len(payload) - written
		toWrite := remaining
		if toWrite > c.outChunkSize {
			toWrite = c.outChunkSize
		}

		if written == 0 {
			if _, err := c.conn.Write(header); err != nil {
				return err
			}
		} else {
			if _, err := c.conn.Write([]byte{byte((3 << 6) | csid)}); err != nil {
				return err
			}
		}

		if _, err := c.conn.Write(payload[written : written+toWrite]); err != nil {
			return err
		}
		written += toWrite
	}
	return nil
}

func (c *rtmpConn) cleanup() {
	if c.seg != nil {
		log.Printf("[%s] finishing segmenter for %s", c.conn.RemoteAddr(), c.streamName)
		if err := c.seg.Finish(); err != nil {
			log.Printf("[%s] finish error: %v", c.conn.RemoteAddr(), err)
		}
	}
	if c.streamName != "" {
		log.Printf("[%s] stream ended: %s", c.conn.RemoteAddr(), c.streamName)
	}
}
