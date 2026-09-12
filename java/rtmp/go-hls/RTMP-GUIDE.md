# Go RTMP-to-HLS Implementation Guide

A minimal standalone Go binary that accepts RTMP connections and remuxes to HLS. Built for memory footprint testing against the Java RTMP backend.

## Overview

Single binary: accepts RTMP on `:1935`, parses the RTMP protocol, feeds audio/video directly to the existing fMP4 HLS segmenter. No Redis, no auth, no S3.

```
OBS (RTMP) --> Go RTMP server (:1935) --> Segmenter --> HLS output on disk
```

## Building

```bash
cd java/rtmp/go-hls

# Build the RTMP+HLS binary
go build -tags rtmp -o rtmp-hls .

# Run
./rtmp-hls --port 1935 --out-root ./hls --max-streams 50

# Or with Docker
docker compose up --build
```

The original stdin FLV reader binary still works without the `rtmp` tag:

```bash
go build -o hls-segmenter .
```

## Architecture

Uses Go build tags to add a second entry point without modifying existing code:

| File | Build Tag | Purpose |
|---|---|---|
| `main.go` | `!rtmp` | Original stdin FLV reader |
| `rtmp_main.go` | `rtmp` | TCP listener, memory stats |
| `rtmp_server.go` | `rtmp` | RTMP protocol: handshake, chunk parsing, command dispatch |
| `amf0.go` | `rtmp` | AMF0 encoder/decoder |
| `segmenter.go` | (none) | Shared: FLV-to-fMP4 HLS segmentation |
| `uploader.go` | (none) | Shared: S3 mirror (unused in RTMP mode) |

## RTMP Chunk Format Reference

Each RTMP message is split into one or more chunks. The first chunk of a message has a full header; continuation chunks use fmt=3.

### Basic Header (1-3 bytes)

```
Byte 0: [fmt:2][csid:6]

csid 2-63:   1-byte header (csid is the lower 6 bits directly)
csid 64-319: 2-byte header (csid = 64 + byte1)
csid 64+:    3-byte header (csid = 64 + byte1 + byte2*256)
```

### Message Header (depends on fmt)

```
fmt=0 (11 bytes): timestamp(3) + length(3) + typeId(1) + streamId(4 LE)
fmt=1 (7 bytes):  delta(3)    + length(3) + typeId(1)  -- inherits streamId from prev on same csid
fmt=2 (3 bytes):  delta(3)                              -- inherits length, typeId, streamId from prev
fmt=3 (0 bytes):  -- inherits everything from prev header on this csid
```

### Timestamp Handling

- If 3-byte field < 0xFFFFFF: use as-is (fmt=0) or as delta (fmt=1/2)
- If 3-byte field == 0xFFFFFF: read 4 additional bytes as the real extended timestamp

### Chunk Payload

After the header, read up to `inChunkSize` bytes of payload. If the message is larger than `inChunkSize`, the remaining payload arrives in continuation chunks (fmt=3) until `bytesRead >= messageLength`.

## AMF0 Encoding Reference

AMF0 is the serialization format for RTMP command messages. Getting this wrong causes silent failures.

### Value Types

| Type | Marker | Encoding |
|---|---|---|
| Number | `0x00` | 8-byte IEEE 754 double (big-endian) |
| Boolean | `0x01` | 1 byte (0=false, 1=true) |
| String | `0x02` | 2-byte length (BE) + UTF-8 bytes |
| Object | `0x03` | key-value pairs + end marker |
| Null | `0x05` | (no payload) |
| Undefined | `0x06` | (no payload) |
| EcmaArray | `0x08` | 4-byte count (BE) + key-value pairs + end marker |
| LongString | `0x0C` | 4-byte length (BE) + UTF-8 bytes |

### Object Encoding

```
[0x03]                                  -- Object marker
[2-byte key length][key bytes]          -- Property name (NO type marker!)
[AMF0 value]                            -- Property value (WITH type marker)
... repeat for each property ...
[0x00 0x00 0x09]                        -- Object end marker (keyLen=0 + type=0x09)
```

---

## Connection Flow: Step by Step

This is exactly what happens from the moment a client (OBS) connects to when it starts sending video. Each step describes what bytes arrive, how to parse them, what to send back, and what can go wrong.

### Step 1: TCP Accept

```
Server listens on :1935
Client connects -> accept TCP socket
Create per-connection state: chunkSize=128, prevHeaders map, payload buffers
```

### Step 2: RTMP Handshake

The handshake is a fixed 4-message exchange that establishes RTMP version and sync.

```
Client sends C0 + C1 (1537 bytes total, can arrive in one read):

  C0 (1 byte):  RTMP version (usually 3)
  C1 (1536 bytes):
    [0..3]:  timestamp (4 bytes, often 0)
    [4..7]:  zero (4 bytes)
    [8..1535]: random data (1528 bytes)

Server sends S0 + S1 (1537 bytes):

  S0 (1 byte):  RTMP version (3)
  S1 (1536 bytes):
    [0..3]:  timestamp (4 bytes, use 0)
    [4..7]:  zero (4 bytes)
    [8..1535]: random data (1528 bytes)

Server sends S2 (1536 bytes):

  S2 (1536 bytes):
    [0..3]:  echo client's C1 timestamp
    [4..7]:  zeros
    [8..1535]: echo client's C1 random data

Client sends C2 (1536 bytes):
  Echoes S1 timestamp + S1 random data (server reads and discards)
```

**What can go wrong:**
- Client sends wrong version in C0 (most clients send 3, just accept it)
- Short reads: always use `io.ReadFull` for the 1536-byte blocks
- Nothing else should arrive during handshake; if it does, the connection is broken

### Step 3: Client Sends SetChunkSize + Connect

After the handshake, the client enters the RTMP command phase. It sends two messages:

#### 3a. Set Chunk Size (type=1, csid=2)

```
Payload (4 bytes, big-endian uint32):
  The client's outgoing chunk size (typically 4096 or 128)

Server action:
  Store as c.inChunkSize
  All subsequent client chunks use this size for payload splitting
```

**Handling:**
```go
case 1:
    c.inChunkSize = int(binary.BigEndian.Uint32(payload[:4]))
```

#### 3b. Connect (type=20, csid=3, streamId=0)

```
Payload is AMF0 encoded. Typical OBS connect:

  values[0] = "connect"          (String)
  values[1] = 1.0                (Number - transaction ID)
  values[2] = {                  (Object - command properties)
      "app":       "live",
      "tcUrl":     "rtmp://host:1935/live",
      "fpad":      false,
      "capabilities": 239.0,
      "audioCodecs": 3575.0,
      "videoCodecs": 252.0,
      "videoFunction": 1.0,
      ...
  }
```

**Server response -- send 4 messages in order:**

```go
// 1. Window Ack Size (type=5, csid=2, streamId=0)
payload := make([]byte, 4)
binary.BigEndian.PutUint32(payload, 5000000)
sendRTMPMessage(5, 2, 0, payload)

// 2. Set Peer Bandwidth (type=6, csid=2, streamId=0)
payload := make([]byte, 5)
binary.BigEndian.PutUint32(payload, 5000000)
payload[4] = 2  // limit type: 2 = Dynamic
sendRTMPMessage(6, 2, 0, payload)

// 3. Set Chunk Size (type=1, csid=2, streamId=0)
payload := make([]byte, 4)
binary.BigEndian.PutUint32(payload, 5000)  // our outgoing chunk size
sendRTMPMessage(1, 2, 0, payload)

// 4. _result (type=20, csid=3, streamId=0)
amf0Payload := encodeAMF0Values([]interface{}{
    "_result",
    1.0,   // echo txId
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
})
sendRTMPMessage(20, 3, 0, amf0Payload)
```

**What can go wrong:**
- Protocol messages (type 1, 5, 6) MUST use csid=2. Using csid=3 causes some clients to reject them.
- The `_result` AMF0 object property keys must NOT have a type marker (see Bug 1 below).

### Step 4: Client Sends releaseStream, FCPublish, createStream

The client sends three commands in sequence. Each expects a `_result` response.

#### 4a. releaseStream (type=20, csid=3)

```
values[0] = "releaseStream"
values[1] = txId (Number)
values[2] = null or transaction object
values[3] = stream key (String, e.g. "abc123")
```

**Server response:**
```go
sendAMF0Command([]interface{}{"_result", txId, nil}, 0)
```

#### 4b. FCPublish (type=20, csid=3)

```
values[0] = "FCPublish"
values[1] = txId
values[2] = null
values[3] = stream key
```

**Server response:**
```go
sendAMF0Command([]interface{}{"_result", txId, nil}, 0)
```

#### 4c. createStream (type=20, csid=3)

```
values[0] = "createStream"
values[1] = txId
values[2] = null
```

**Server response:**
```go
c.streamID++
sendAMF0Command([]interface{}{"_result", txId, nil, float64(c.streamID)}, 0)
// The 4th value is the new stream ID. All subsequent media uses this streamId.
```

**What can go wrong:**
- The `_result` for createStream MUST include the new stream ID as the 4th value. Without it, the client won't know which streamId to use for publish/media.
- The `txId` must be echoed back exactly. If you return 0 instead of the real txId, the client treats it as an unsolicited response.

### Step 5: Client Sends publish

```
values[0] = "publish"
values[1] = txId (often 0 or 1)
values[2] = null or ""
values[3] = stream key (String, e.g. "abc123")
values[4] = "live" (String, publish type)
```

**Server response:**

```go
// 1. onStatus (type=20, csid=3, streamId = the streamId from createStream)
amf0Payload := encodeAMF0Values([]interface{}{
    "onStatus",
    0.0,
    nil,
    map[string]interface{}{
        "level":       "status",
        "code":        "NetStream.Publish.Start",
        "description": "Stream is now published",
    },
})
sendRTMPMessage(20, 3, c.streamID, amf0Payload)
```

**Server side-effect:**

After responding, the server creates the Segmenter:

```go
// 1. Extract and validate publish name
publishName := values[3].String
if publishName == "" {
    // Generate unique fallback (AMF0 Null gives empty String)
    b := make([]byte, 8)
    rand.Read(b)
    publishName = fmt.Sprintf("stream-%x", b)
}

// 2. Create HLS directory
hlsDir := filepath.Join(hlsRoot, publishName, "hd")
os.MkdirAll(hlsDir, 0o755)

// 3. Write master playlist
os.WriteFile(filepath.Join(hlsRoot, publishName, "master.m3u8"),
    []byte("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,NAME=\"HD\"\nhd/output.m3u8\n"),
    0o644)

// 4. Create segmenter
c.seg = NewSegmenter(hlsDir, 2000, 10, 1)
```

**What can go wrong:**
- `values[3].String` can be empty if the AMF0 value is Null type. The `String` field of a Null `amf0Value` is Go's zero value (`""`). Always check for empty and generate a fallback.
- If multiple connections use the same publishName, they write to the same directory and corrupt each other's files.

### Step 6: Client Sends Audio + Video Data

Once the server responds to publish, the client starts streaming media. These are NOT AMF0 commands -- they are raw audio/video payloads.

#### 6a. Audio Data (type=8, csid=4)

```
Payload:
  [0]: Audio codec + flags (e.g. 0xAF = AAC, 44kHz, 16bit, Stereo)
  [1..]: Audio data (AAC raw or AAC sequence header)
```

#### 6b. Video Data (type=9, csid=6)

```
Payload:
  [0]: Frame type + codec (e.g. 0x17 = keyframe + AVC/H.264, 0x27 = interframe + AVC)
  [1]: AVC packet type (0 = sequence header, 1 = NALU)
  [2..4]: Composition time offset (3 bytes, signed)
  [5..]: Video data (AVCC format: length-prefixed NALUs)
```

**Server action:**

```go
case 8:  // Audio
    if c.seg != nil {
        c.seg.Process(byte(h.typeID), h.timestamp, payload)
    }

case 9:  // Video
    if c.seg != nil {
        c.seg.Process(byte(h.typeID), h.timestamp, payload)
    }
```

**What can go wrong:**
- If `c.seg` is nil (publish was never received), audio/video packets are silently dropped.
- Timestamps in fmt=1 headers are deltas, not absolute. The reader must accumulate: `absolute = previous + delta`.
- The video codec config (SPS/PPS) arrives as the first video packet with AVC packet type=0. This is needed for HLS init segment creation.

### Step 7: Client Disconnects

The client stops publishing in one of these ways:

1. **deleteStream** (type=20): Client sends `{"deleteStream", txId, null, streamId}`. Server closes the connection.
2. **TCP disconnect**: Client closes OBS. Server gets `io.EOF` on read.
3. **FCUnpublish**: Client sends `{"FCUnpublish", ...}`. Server sends `_result` but keeps listening (some clients send this before deleting).

**Server action:**

```go
func (c *rtmpConn) cleanup() {
    if c.seg != nil {
        c.seg.Finish()  // Flushes remaining segments, closes init.mp4
    }
}
```

**What can go wrong:**
- If `Finish()` is not called, the last segment is never flushed and the HLS playlist is incomplete.
- Some clients send `deleteStream` + immediately close the TCP socket. The server should handle both gracefully.

---

## Full Sequence Diagram

```
Client (OBS)                                    Server
    |                                              |
    |--- C0+C1 (version + 1536B) ---------------->|
    |<-- S0+S1 (version + 1536B) -----------------|
    |<-- S2 (1536B echo of C1) -------------------|
    |--- C2 (1536B echo of S1) ------------------>|
    |                                              |
    |--- SetChunkSize (type=1, csid=2) ---------->|
    |    [store client's inChunkSize]              |
    |                                              |
    |--- Connect (type=20, csid=3) -------------->|
    |    AMF0: "connect", txId, {app,tcUrl,...}    |
    |                                              |
    |<-- WindowAckSize (type=5, csid=2) ----------|
    |<-- SetPeerBandwidth (type=6, csid=2) -------|
    |<-- SetChunkSize (type=1, csid=2) -----------|
    |<-- _result (type=20, csid=3) ---------------|
    |    AMF0: "_result", txId, {fmsVer}, {code}  |
    |                                              |
    |--- releaseStream (type=20, csid=3) -------->|
    |<-- _result ---------------------------------|
    |                                              |
    |--- FCPublish (type=20, csid=3) ------------>|
    |<-- _result ---------------------------------|
    |                                              |
    |--- createStream (type=20, csid=3) --------->|
    |<-- _result + streamId ----------------------|
    |                                              |
    |--- publish (type=20, csid=3) -------------->|
    |    AMF0: "publish", txId, null, key, "live" |
    |                                              |   [create Segmenter, HLS dir, master.m3u8]
    |<-- onStatus: NetStream.Publish.Start --------|
    |                                              |
    |--- Audio (type=8) ------------------------->|   [seg.Process(8, ts, payload)]
    |--- Video (type=9) ------------------------->|   [seg.Process(9, ts, payload)]
    |--- Audio (type=8) ------------------------->|   [seg.Process(8, ts, payload)]
    |--- Video (type=9) ------------------------->|   [seg.Process(9, ts, payload)]
    |    ...                                      |
    |                                              |
    |--- deleteStream / TCP close --------------->|   [seg.Finish()]
```

## Chunk Stream IDs (csid) Reference

| csid | Used For |
|------|----------|
| 2 | Protocol control: SetChunkSize, WindowAckSize, SetPeerBandwidth |
| 3 | AMF0 commands: connect, publish, _result, onStatus |
| 4 | Audio data |
| 6 | Video data |

The csid is part of the Basic Header and determines which "chunk stream" a message belongs to. Each chunk stream maintains its own header state (prevHeaders) for fmt=1/2/3 continuation.

## CLI Flags

```
--port            RTMP listen port (default 1935)
--out-root        HLS output root directory (default ./hls)
--max-streams     Max concurrent streams (default 18)
--mem-interval    Memory stats logging interval in seconds (default 10)
```

## Memory Profiling

The server logs `runtime.MemStats` every N seconds:

```
[mem] alloc=2MB sys=98MB heap_idle=85MB heap_inuse=3MB heap_objects=14579
```

| Metric | Meaning |
|---|---|
| `alloc` | Live heap objects (actual usage) |
| `sys` | Total memory from OS (includes idle pages) |
| `heap_idle` | Freed by GC but not returned to OS (Go runtime keeps for reuse) |
| `heap_inuse` | Actively used heap pages (**this is the real footprint**) |
| `heap_objects` | Number of live heap objects |

**Note:** `sys` will be much higher than `heap_inuse` after a stress test. This is normal Go GC behavior -- idle memory is kept for fast reuse. The real footprint is `heap_inuse`.

To force idle memory back to the OS, call `debug.FreeOSMemory()` in the stats goroutine.

---

## Bugs Found and Lessons Learned

These are real bugs we encountered while implementing this. Each one caused OBS to fail silently or produce incorrect output.

### Bug 1: AMF0 Object Property Keys Must Not Have a Type Marker

**Impact:** OBS hangs forever after connect. Most critical bug.

**Wrong:**
```go
func encodeAMF0Object(m map[string]interface{}) []byte {
    buf = append(buf, amf0Object)
    for k, v := range m {
        buf = append(buf, encodeAMF0String(k)...)  // BUG: prepends 0x02
        buf = append(buf, encodeAMF0Value(v)...)
    }
    buf = append(buf, 0, 0, amf0ObjectEnd)
}
```

**Why it breaks:** `encodeAMF0String` writes `0x02` + 2-byte length + bytes. Inside an Object, property names are bare `2-byte length + bytes` WITHOUT the `0x02` type marker. OBS reads the `0x02` byte as part of the length (e.g., `0x02 0x00` = length 512), then blocks waiting for 512 bytes that never arrive.

**Correct:**
```go
func encodeAMF0ObjectKey(s string) []byte {
    b := []byte(s)
    buf := make([]byte, 2+len(b))
    binary.BigEndian.PutUint16(buf[:2], uint16(len(b)))
    copy(buf[2:], b)
    return buf
}

func encodeAMF0Object(m map[string]interface{}) []byte {
    buf = append(buf, amf0Object)
    for k, v := range m {
        buf = append(buf, encodeAMF0ObjectKey(k)...)  // No 0x02 prefix
        buf = append(buf, encodeAMF0Value(v)...)
    }
    buf = append(buf, 0, 0, amf0ObjectEnd)
}
```

### Bug 2: AMF0 Object End Marker Parsing

**Impact:** Corrupts AMF0 decoding, drops command values.

**Wrong:**
```go
if keyLen == 0 {
    var endMarker [3]byte
    io.ReadFull(r, endMarker[:])  // Reads 3 bytes AFTER keyLen=0
    if endMarker[0] == 0 && endMarker[1] == 0 && endMarker[2] == 0x09 {
        break
    }
}
```

**Why it breaks:** The end marker is `0x00 0x00 0x09`. The first 2 bytes (`0x0000`) are the key length we already read. Only the `0x09` type byte remains. Reading 3 bytes consumes 2 bytes from the next value/message.

**Correct:**
```go
if keyLen == 0 {
    var endType [1]byte
    io.ReadFull(r, endType[:])
    if endType[0] == amf0ObjectEnd {  // 0x09
        break
    }
}
```

### Bug 3: Protocol Messages Must Use csid=2

**Impact:** Some RTMP clients may reject or misparse protocol control messages.

Protocol control messages (Set Chunk Size, Window Ack Size, Set Peer Bandwidth) should be sent on **chunk stream ID 2**. AMF0 commands use **csid 3**.

```go
// Protocol messages -> csid=2
sendRTMPMessage(5, 2, 0, payload)  // Window Ack Size
sendRTMPMessage(6, 2, 0, payload)  // Set Peer Bandwidth
sendRTMPMessage(1, 2, 0, payload)  // Set Chunk Size

// AMF0 commands -> csid=3
sendRTMPMessage(20, 3, streamID, payload)
```

### Bug 4: fmt=1 Timestamp is a Delta

**Impact:** Incorrect timestamps on media packets.

In fmt=1 headers, the 3-byte timestamp field is a **delta** relative to the previous message on that chunk stream, not an absolute timestamp.

```go
case 1:
    delta := readInt24()
    h.timestamp = prev.timestamp + delta  // absolute = previous + delta
```

### Bug 5: Extended Timestamp Field

**Impact:** Corruption after ~4.6 hours of streaming.

When timestamp >= 0xFFFFFF, the 3-byte timestamp field must be set to `0xFFFFFF` and the actual 4-byte timestamp follows after the message header:

```go
if ts >= 0xFFFFFF {
    tsField = 0xFFFFFF  // Not the actual timestamp!
    header = make([]byte, 16)  // 12 + 4 extended bytes
    // Write actual timestamp in bytes 12-15
}
```

### Bug 6: Empty Stream Key Collapses All Streams Into One Directory

**Impact:** File collisions, rename errors, all streams share one HLS output.

If the AMF0 publish name is Null or empty, `filepath.Join(root, "", "hd")` collapses to `root/hd` for ALL streams. Always validate and generate a fallback name:

```go
publishName := values[3].String
if publishName == "" {
    b := make([]byte, 8)
    rand.Read(b)
    publishName = fmt.Sprintf("stream-%x", b)
}
```

### Bug 7: Silent Failures from Missing Message Types

**Impact:** Confusing "unknown typeID" log spam.

RTMP clients send metadata (typeID=18) and other message types. Handle them explicitly:

```go
case 18:
    // AMF0 Data (stream metadata) -- silently ignored
```
