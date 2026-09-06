package com.nhochamvui.rtmp.core;

import com.nhochamvui.rtmp.core.enums.AMF0Type;
import com.nhochamvui.rtmp.core.exceptions.StreamClose;
import com.nhochamvui.rtmp.core.models.AMF0Message;
import com.nhochamvui.rtmp.core.models.Basic;
import com.nhochamvui.rtmp.core.models.Message;
import com.nhochamvui.rtmp.core.models.RTMPHeader;
import com.nhochamvui.rtmp.core.models.Tuple2;
import com.nhochamvui.rtmp.session.PublishValidation;
import com.nhochamvui.rtmp.session.SafePlaybackPath;
import com.nhochamvui.rtmp.session.StreamSessionService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.MDC;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.nhochamvui.rtmp.core.constants.Constant.*;
import static com.nhochamvui.rtmp.core.functions.MediaHandler.createFlvHeader;
import static com.nhochamvui.rtmp.core.functions.MediaHandler.writeFlvTag;

public class ClientSession {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClientSession.class);
    private static final org.slf4j.Logger ffmpegLog = org.slf4j.LoggerFactory.getLogger("ffmpeg");

    public static final int HANDSHAKE_LENGTH = 1536;
    public static final int VERSION_LENGTH = 1;
    public static final int DEFAULT_VERSION = 3;

    private static final Random randomSeed = new Random();

    private final Socket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final Server server;
    private final StreamSessionService streamSessionService;
    private final SafePlaybackPath safePlaybackPath;
    private final String serverId;
    private final String hlsBucket;
    private final String hlsRegion;
    private final String hlsCdnUrl;
    private final String connectionId;
    private final String connectionIp;
    private final long connectionStartTime;

    private int inChunkSize = 128;
    private int outChunkSize = 128;

    private final Map<Integer, RTMPHeader> prevHeaders = new HashMap<>();
    private final Map<Integer, ByteBuffer> chunkPayload = new HashMap<>();

    private int nextStreamId = 1;
    private String streamName;
    private String sessionLookupKey;
    private String keyFingerprint;
    private String hlsBaseDir;

    private Process ffmpegProcess;
    private boolean sentFlvHeader = false;

    private long audioPackets;
    private long videoPackets;
    private long audioBytes;
    private long videoBytes;
    private long bytesToFfmpeg;
    private long keyframeCount;
    private long lastKeyframeTimestamp = -1;
    private long maxKeyframeInterval;
    private long firstMediaTimestamp = -1;
    private long lastMediaTimestamp;
    private long maxSeenTimestamp;
    private long droppedPackets;
    private long streamStartWallTime;
    private volatile String ffmpegFps;
    private volatile String ffmpegBitrate;
    private volatile String ffmpegSpeed;
    private volatile boolean streaming;

    public ClientSession(Socket socket, Server server, StreamSessionService streamSessionService, SafePlaybackPath safePlaybackPath, String serverId, String hlsBucket, String hlsRegion, String hlsCdnUrl) throws IOException {
        this.socket = socket;
        this.server = server;
        this.streamSessionService = streamSessionService;
        this.safePlaybackPath = safePlaybackPath;
        this.serverId = serverId;
        this.hlsBucket = hlsBucket;
        this.hlsRegion = hlsRegion;
        this.hlsCdnUrl = hlsCdnUrl;
        this.socket.setTcpNoDelay(true);
        this.socket.setSoTimeout(5000);
        this.connectionStartTime = System.currentTimeMillis();
        this.connectionIp = socket.getInetAddress().getHostAddress();
        this.connectionId = connectionIp + "-" + connectionStartTime;
        this.inputStream = socket.getInputStream();
        this.outputStream = socket.getOutputStream();
    }

    public void run() {
        try {
            MDC.put("serverId", serverId);
            MDC.put("connectionId", connectionId);
            MDC.put("publisherIp", connectionIp);
            log.info("[{}] RTMP connection accepted from {}", connectionId, connectionIp);
            handleHandShake();
            prevHeaders.clear();
            chunkPayload.clear();

            while (!socket.isClosed() && !socket.isInputShutdown()) {
                if (inputStream.available() > 0) {
                    try {
                        handleChunkMessage();
                    } catch (StreamClose e) {
                        break;
                    }
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("[{}] Connection error: {}", connectionId, e.getMessage(), e);
        } finally {
            cleanup();
            long duration = System.currentTimeMillis() - connectionStartTime;
            log.info("[{}] RTMP connection closed | duration={}ms | IP={}", connectionId, duration, connectionIp);
            MDC.clear();
        }
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public String getStreamName() {
        return streamName;
    }

    public long getAudioPackets() {
        return audioPackets;
    }

    public long getVideoPackets() {
        return videoPackets;
    }

    public long getAudioBytes() {
        return audioBytes;
    }

    public long getVideoBytes() {
        return videoBytes;
    }

    public long getDroppedPackets() {
        return droppedPackets;
    }

    public long getKeyframeCount() {
        return keyframeCount;
    }

    public long getBytesToFfmpeg() {
        return bytesToFfmpeg;
    }

    private void handleHandShake() throws IOException {
        log.info("[{}] Handshake started.", connectionId);
        int c0 = inputStream.read();
        byte[] c1 = readToBuffer(inputStream, HANDSHAKE_LENGTH);
        sendS0S1(c0);
        sendS2(c1);
        byte[] c2 = readToBuffer(inputStream, HANDSHAKE_LENGTH);
        log.info("[{}] Handshake finished.", connectionId);
    }

    private void sendS0S1(int c0) throws IOException {
        int s0 = determineVersion(c0);
        byte[] s1 = new byte[HANDSHAKE_LENGTH];
        byte[] randomData = generateRandomBytes(HANDSHAKE_LENGTH - 8);
        System.arraycopy(randomData, 0, s1, 8, randomData.length);
        outputStream.write(s0);
        outputStream.write(s1);
    }

    private void sendS2(byte[] c1) throws IOException {
        byte[] c1Time = Arrays.copyOfRange(c1, 0, 3);
        byte[] c1RandBytes = Arrays.copyOfRange(c1, 8, c1.length);
        byte[] s2 = new byte[HANDSHAKE_LENGTH];
        System.arraycopy(c1Time, 0, s2, 0, c1Time.length);
        System.arraycopy(c1RandBytes, 0, s2, 8, c1RandBytes.length);
        outputStream.write(s2);
    }

    private static int determineVersion(int requestedVersion) {
        switch (requestedVersion) {
            case 0, 2:
                log.warn("Reject deprecated version.");
            default:
                log.info("Selected version: {}", DEFAULT_VERSION);
                return DEFAULT_VERSION;
        }
    }

    // ─── Chunk processing ────────────────────────────────────────

    private void handleChunkMessage() throws IOException, StreamClose {
        final RTMPHeader header = readRTMPHeader();
        prevHeaders.put(header.basic.csid, header);
        chunkPayload.putIfAbsent(header.basic.csid, ByteBuffer.allocateDirect(header.message.length));
        ByteBuffer currentMessageData = chunkPayload.get(header.basic.csid);
        assert currentMessageData != null;
        if (currentMessageData.hasRemaining()) {
            int maxLengthForCurrentChunkData = Math.min(currentMessageData.remaining(), inChunkSize);
            currentMessageData.put(inputStream.readNBytes(maxLengthForCurrentChunkData));
            if (!currentMessageData.hasRemaining()) {
                currentMessageData.flip();

                chunkPayload.remove(header.basic.csid);
                List<Object> messages = new ArrayList<>();
                switch (header.message.typeId) {
                    case 1:
                        this.inChunkSize = currentMessageData.getInt(0);
                        log.info("[{}] Process SetChunkSize message, new inChunkSize: {}", connectionId, this.inChunkSize);
                        break;
                    case 2:
                        log.info("[{}] Process AbortMessage message", connectionId);
                        break;
                    case 3:
                        log.info("[{}] Process Acknowledgement message", connectionId);
                        break;
                    case 4:
                        log.info("[{}] Process UserControl message", connectionId);
                        break;
                    case 5:
                        log.info("[{}] Process Window Acknowledgement Size message", connectionId);
                        break;
                    case 6:
                        log.info("[{}] Process Set Peer Bandwidth message", connectionId);
                        break;
                    case 8:
                    case 9:
                        byte[] payload = new byte[currentMessageData.remaining()];
                        currentMessageData.get(0, payload);

                        if (header.message.typeId == 8) {
                            audioPackets++;
                            audioBytes += payload.length;
                        } else {
                            videoPackets++;
                            videoBytes += payload.length;
                            if (payload.length >= 2 && (payload[1] & 0xFF) == 0) {
                                keyframeCount++;
                                if (lastKeyframeTimestamp >= 0) {
                                    long interval = header.message.timestamp - lastKeyframeTimestamp;
                                    if (interval > maxKeyframeInterval) {
                                        maxKeyframeInterval = interval;
                                    }
                                }
                                lastKeyframeTimestamp = header.message.timestamp;
                            }
                        }
                        if (firstMediaTimestamp < 0) {
                            firstMediaTimestamp = header.message.timestamp;
                        }
                        if (header.message.timestamp < maxSeenTimestamp && maxSeenTimestamp > 0) {
                            droppedPackets++;
                        }
                        maxSeenTimestamp = Math.max(maxSeenTimestamp, header.message.timestamp);
                        lastMediaTimestamp = header.message.timestamp;

                        if (ffmpegProcess == null) {
                            startFfmpeg();
                        }

                        if (!ffmpegProcess.isAlive()) {
                            String error = new String(ffmpegProcess.getErrorStream().readAllBytes());
                            log.warn("[{}] FFmpeg exited: {}, error: {}", connectionId, ffmpegProcess.exitValue(), error);
                            sentFlvHeader = false;
                            ffmpegProcess = null;
                            break;
                        }

                        if (header.message.typeId == 9 && !sentFlvHeader) {
                            if (payload.length < 2 || (payload[1] & 0xFF) != 0) {
                                log.info("[{}] Skip first video frame: not a sequence header", connectionId);
                                break;
                            }
                        }

                        if (!sentFlvHeader) {
                            sentFlvHeader = true;
                            ffmpegProcess.getOutputStream().write(createFlvHeader());
                        }

                        writeFlvTag(ffmpegProcess.getOutputStream(), (byte) header.message.typeId, header.message.timestamp, payload);
                        bytesToFfmpeg += 11 + payload.length + 4;
                        break;
                    case 15:
                        log.info("[{}] Process AMF3 Command message", connectionId);
                        break;
                    case 18:
                        log.info("[{}] Process metadata message", connectionId);
                        break;
                    case 20:
                        log.info("[{}] Process AMF0 Command message", connectionId);
                        while (currentMessageData.hasRemaining()) {
                            final Object message = decodeAMF0CommandMessage(currentMessageData);
                            messages.add(message);
                        }
                        String commandName = messages.isEmpty() ? "UNKNOWN" : messages.getFirst().toString();
                        log.info("[{}] >>> Received command: {}", connectionId, commandName);
                        handleCommandMessage(messages, header.message.streamId);
                        break;
                }
            }
        }
    }

    private RTMPHeader readRTMPHeader() throws IOException {
        final RTMPHeader rtmpHeader = new RTMPHeader();
        final Basic basicHeader = rtmpHeader.basic;
        byte first = inputStream.readNBytes(1)[0];

        basicHeader.fmt = (first & MASK_OF_8_BITS) >> 6;

        int csid = first & MASK_OF_6_BITS;

        if (csid == 0) {
            csid = (inputStream.readNBytes(1)[0] & MASK_OF_8_BITS) + 64;
        } else if (csid == 1) {
            byte[] bytes = inputStream.readNBytes(2);
            csid = ((bytes[0] & MASK_OF_8_BITS) + 64) + ((bytes[1] & MASK_OF_8_BITS) << 8);
        }
        basicHeader.csid = csid;

        final Message message = rtmpHeader.message;
        switch (basicHeader.fmt) {
            case 0:
                message.timestamp = readIntFrom3Bytes(inputStream);
                if (message.timestamp == 0xFFFFFF) {
                    message.timestamp = ByteBuffer.wrap(inputStream.readNBytes(4)).getInt();
                }
                message.length = readIntFrom3Bytes(inputStream);
                message.typeId = inputStream.read();
                message.streamId = ByteBuffer.wrap(inputStream.readNBytes(4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
                break;
            case 1:
                message.timestampDelta = readIntFrom3Bytes(inputStream);
                if (message.timestampDelta == 0xFFFFFF) {
                    message.timestampDelta = ByteBuffer.wrap(inputStream.readNBytes(4)).getInt();
                }
                message.length = readIntFrom3Bytes(inputStream);
                message.typeId = inputStream.read();
                RTMPHeader prev1 = prevHeaders.get(basicHeader.csid);
                if (prev1 != null) {
                    message.streamId = prev1.message.streamId;
                    message.timestamp = prev1.message.timestamp + message.timestampDelta;
                } else {
                    throw new IOException(String.format("[%s] fmt=1 chunk for unknown csid=%d", connectionId, basicHeader.csid));
                }
                break;
            case 2:
                message.timestampDelta = readIntFrom3Bytes(inputStream);
                if (message.timestampDelta == 0xFFFFFF) {
                    message.timestampDelta = ByteBuffer.wrap(inputStream.readNBytes(4)).getInt();
                }
                RTMPHeader prev2 = prevHeaders.get(basicHeader.csid);
                if (prev2 != null) {
                    message.length = prev2.message.length;
                    message.typeId = prev2.message.typeId;
                    message.streamId = prev2.message.streamId;
                    message.timestamp = prev2.message.timestamp + message.timestampDelta;
                } else {
                    throw new IOException(String.format("[%s] fmt=2 chunk for unknown csid=%d", connectionId, basicHeader.csid));
                }
                break;
            case 3:
                RTMPHeader prev3 = prevHeaders.get(basicHeader.csid);
                if (prev3 != null) {
                    message.timestampDelta = prev3.message.timestampDelta;
                    message.timestamp = prev3.message.timestamp;
                    message.length = prev3.message.length;
                    message.typeId = prev3.message.typeId;
                    message.streamId = prev3.message.streamId;
                } else {
                    throw new IOException(String.format("[%s] fmt=3 chunk for unknown csid=%d", connectionId, basicHeader.csid));
                }
                break;
        }

        return rtmpHeader;
    }

    // ─── Command handling ────────────────────────────────────────

    private void handleCommandMessage(List<Object> messages, int messageStreamId) throws IOException, StreamClose {
        if (messages.isEmpty()) {
            log.warn("[{}] Empty command message, exit.", connectionId);
            return;
        }
        String command = messages.getFirst().toString();
        log.info("[{}] >>> Handling command: {} on stream {}", connectionId, command, messageStreamId);
        switch (command) {
            case "connect":
                if (messages.get(2) instanceof Map<?, ?> map) {
                    String clientName = map.get("app").toString();
                    log.info("[{}] Processing connect message for client: {}", connectionId, clientName);

                    int encodeRequest = map.containsKey("objectEncoding")
                            ? Integer.parseInt(map.get("objectEncoding").toString())
                            : 0;
                    if (encodeRequest == 3) {
                        log.warn("[{}] WARNING: AMF3 is not supported, exit.", connectionId);
                        return;
                    }

                    this.outChunkSize = 5000;
                    sendWindowAckSize(5000000);
                    sendSetPeerBandwidth(5000000, 2);
                    sendSetChunkSize(this.outChunkSize);

                    List<Object> responseBody = new ArrayList<>();
                    responseBody.add("_result");
                    responseBody.add(1);
                    responseBody.add(new AMF0Message(new ArrayList<>(Arrays.asList(
                            new Tuple2<>("fmsVer", "FMS/3,0,1,123"),
                            new Tuple2<>("capabilities", 31)))));
                    responseBody.add(new AMF0Message(new ArrayList<>(Arrays.asList(
                            new Tuple2<>("level", "status"),
                            new Tuple2<>("code", "NetConnection.Connect.Success"),
                            new Tuple2<>("description", "Connection succeeded"),
                            new Tuple2<>("objectEncoding", 0)))));
                    final byte[] resultCommandMessage = encodeAMF0CommandMessage(responseBody, 0);
                    log.info("[{}] before sending result: {} available", connectionId, inputStream.available());
                    outputStream.write(resultCommandMessage);
                    log.info("[{}] Result message bytes: {} bytes", connectionId, resultCommandMessage.length);

                    outputStream.flush();
                }
                log.info("[{}] Finished processing connect message", connectionId);
                break;

            case "releaseStream":
                log.info("[{}] Processing releaseStream...", connectionId);
                {
                    double txId = messages.size() > 1 && messages.get(1) instanceof Number
                            ? ((Number) messages.get(1)).doubleValue() : 0;
                    List<Object> response = Arrays.asList("_result", txId, null);
                    byte[] data = encodeAMF0CommandMessage(response, 0);
                    outputStream.write(data);
                    outputStream.flush();
                }
                break;

            case "FCPublish":
                log.info("[{}] Processing FCPublish...", connectionId);
                {
                    double txId = messages.size() > 1 && messages.get(1) instanceof Number
                            ? ((Number) messages.get(1)).doubleValue() : 0;
                    List<Object> response = Arrays.asList("_result", txId, null);
                    byte[] data = encodeAMF0CommandMessage(response, 0);
                    outputStream.write(data);
                    outputStream.flush();
                }
                break;

            case "FCUnpublish":
                log.info("[{}] Processing FCUnpublish...", connectionId);
                {
                    double txId = messages.size() > 1 && messages.get(1) instanceof Number
                            ? ((Number) messages.get(1)).doubleValue() : 0;
                    List<Object> response = Arrays.asList("_result", txId, null);
                    byte[] data = encodeAMF0CommandMessage(response, 0);
                    outputStream.write(data);
                    outputStream.flush();
                }
                break;

            case "createStream":
                log.info("[{}] Processing createStream...", connectionId);
                {
                    double txId = messages.size() > 1 && messages.get(1) instanceof Number
                            ? ((Number) messages.get(1)).doubleValue() : 0;
                    int newStreamId = nextStreamId++;
                    log.info("[{}] Allocated stream ID: {}", connectionId, newStreamId);
                    List<Object> response = Arrays.asList("_result", txId, null, (double) newStreamId);
                    byte[] data = encodeAMF0CommandMessage(response, 0);
                    outputStream.write(data);
                    outputStream.flush();
                }
                break;

            case "publish":
                log.info("[{}] Processing publish...", connectionId);
                {
                    String publishName = messages.size() > 3 && messages.get(3) != null
                            ? messages.get(3).toString() : "stream";
                    String publishType = messages.size() > 4 && messages.get(4) != null
                            ? messages.get(4).toString() : "live";

                    Optional<PublishValidation> validation = streamSessionService.validatePublish(publishName, serverId, connectionId, connectionIp);
                    if (validation.isEmpty()) {
                        log.warn("[{}] Reject publish | type={} | keyFingerprint={}", connectionId, publishType, safeFingerprint(publishName));
                        sendPublishStatus(messageStreamId, "error", "NetStream.Publish.BadName", "Invalid or expired stream key");
                        throw new StreamClose();
                    }

                    PublishValidation authorized = validation.get();
                    this.streamName = authorized.playbackId();
                    this.sessionLookupKey = authorized.lookupKey();
                    this.keyFingerprint = authorized.keyFingerprint();
                    this.hlsBaseDir = safePlaybackPath.streamDirectory(streamName).toString();
                    MDC.put("playbackId", streamName);
                    log.info("[{}] Publish authorized | playbackId={} | type={} | keyFingerprint={}",
                            connectionId, streamName, publishType, keyFingerprint);

                    if (!server.registerStream(streamName, this)) {
                        log.warn("[{}] Reject duplicate publish | playbackId={}", connectionId, streamName);
                        streamSessionService.disconnect(sessionLookupKey, serverId, connectionId);
                        sendPublishStatus(messageStreamId, "error", "NetStream.Publish.BadName", "Stream is already active");
                        throw new StreamClose();
                    }

                    new File(hlsBaseDir + "/hd").mkdirs();
                    writeMasterPlaylist();

                    sendPublishStatus(messageStreamId, "status", "NetStream.Publish.Start", "Stream is now published");
                    log.info("[{}] Stream registered: {}", connectionId, streamName);

                    this.streamStartWallTime = System.currentTimeMillis();
                    startStatsReporter();
                    startSessionHeartbeat();
                }
                break;

            case "deleteStream":
                log.info("[{}] Stop stream, clear session", connectionId);
                this.chunkPayload.clear();
                throw new StreamClose();

            default:
                log.warn("[{}] Unknown command: {}", connectionId, command);
                break;
        }
    }

    private void sendPublishStatus(int messageStreamId, String level, String code, String description) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("level", level);
        info.put("code", code);
        info.put("description", description);
        List<Object> statusResult = Arrays.asList("onStatus", (double) 0, null, info);
        byte[] data = encodeAMF0CommandMessage(statusResult, messageStreamId);
        outputStream.write(data);
        outputStream.flush();
    }

    private String safeFingerprint(String publishKey) {
        try {
            return streamSessionService.fingerprint(publishKey);
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ─── Protocol messages ───────────────────────────────────────

    private void sendWindowAckSize(int windowSize) throws IOException {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(0x02);
        buffer.writeMedium((int) (System.currentTimeMillis() - connectionStartTime));
        buffer.writeMedium(4);
        buffer.writeByte(5);
        buffer.writeIntLE(0);
        buffer.writeInt(windowSize);

        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        outputStream.write(data);
        outputStream.flush();
        buffer.release();
        log.info("[{}] Sent Window Ack Size: {} ({} bytes)", connectionId, windowSize, data.length);
    }

    private void sendSetPeerBandwidth(int bandwidth, int limitType) throws IOException {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(0x02);
        buffer.writeMedium((int) (System.currentTimeMillis() - connectionStartTime));
        buffer.writeMedium(5);
        buffer.writeByte(6);
        buffer.writeIntLE(0);
        buffer.writeInt(bandwidth);
        buffer.writeByte((byte) limitType);

        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        outputStream.write(data);
        outputStream.flush();
        buffer.release();
        log.info("[{}] Sent Set Peer Bandwidth: {}, type: {} ({} bytes)", connectionId, bandwidth, limitType, data.length);
    }

    private void sendSetChunkSize(int chunkSize) throws IOException {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(0x02);
        buffer.writeMedium((int) (System.currentTimeMillis() - connectionStartTime));
        buffer.writeMedium(4);
        buffer.writeByte(1);
        buffer.writeIntLE(0);
        buffer.writeInt(chunkSize);

        byte[] data = new byte[buffer.readableBytes()];
        buffer.readBytes(data);
        outputStream.write(data);
        outputStream.flush();
        buffer.release();
        log.info("[{}] Sent set chunk size message: {} ({} bytes)", connectionId, chunkSize, data.length);
    }

    // ─── AMF0 encoding ───────────────────────────────────────────

    byte[] encodeAMF0CommandMessage(final List<Object> messages, int streamId) throws IOException {
        ByteBuf buffer = Unpooled.buffer();
        if (messages.isEmpty()) {
            log.warn("Empty command message, exit.");
            return new byte[0];
        }

        int csid = 3;
        byte[] basicHeader = encodeBasicHeader(0, csid);
        buffer.writeBytes(basicHeader);
        long timestamp = System.currentTimeMillis() - connectionStartTime;
        int maxTimestamp = 0xFFFFFF;
        boolean needExtraTime = false;
        if (timestamp >= maxTimestamp) {
            needExtraTime = true;
            buffer.writeMedium(maxTimestamp);
        } else {
            buffer.writeMedium((int) timestamp);
        }

        ByteBuf encodedPayload = encodeRTMPCommandMessagePayload(messages);

        buffer.writeMedium(encodedPayload.readableBytes());
        buffer.writeByte(MSG_TYPE_COMMAND_AMF0);
        buffer.writeIntLE(streamId);

        if (needExtraTime) {
            buffer.writeInt((int) timestamp);
        }

        ByteBuf completeMessage = Unpooled.buffer();

        boolean fmt0Part = true;
        while (encodedPayload.isReadable()) {
            int min = Math.min(outChunkSize, encodedPayload.readableBytes());
            if (fmt0Part) {
                buffer.writeBytes(encodedPayload, min);
                fmt0Part = false;
            } else {
                byte[] fmt3BasicHeader = encodeBasicHeader(CHUNK_FMT_3, csid);
                buffer.writeBytes(fmt3BasicHeader);
                buffer.writeBytes(encodedPayload, min);
            }
            completeMessage.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
            buffer.release();
            buffer = Unpooled.buffer();
        }
        encodedPayload.release();
        byte[] result = new byte[completeMessage.readableBytes()];
        completeMessage.readBytes(result);
        completeMessage.release();
        return result;
    }

    private static ByteBuf encodeRTMPCommandMessagePayload(final List<Object> messages) {
        ByteBuf byteBuffer = Unpooled.buffer();
        for (Object message : messages) {
            byteBuffer.writeBytes(encodeMessage(message));
        }
        return byteBuffer;
    }

    private static ByteBuf encodeMessage(final Object message) {
        log.info("Encoding: {} type: {}", message, message != null ? message.getClass() : "null");
        ByteBuf byteBuffer = Unpooled.buffer();
        if (message == null) {
            byteBuffer.writeByte((byte) 0x05);
            return byteBuffer;
        }
        switch (message) {
            case Number numVal:
                byteBuffer.writeByte((byte) AMF0Type.NUMBER.value);
                byteBuffer.writeLong(Double.doubleToLongBits(Double.parseDouble(numVal.toString())));
                break;
            case String stringVal:
                byteBuffer.writeByte((byte) AMF0Type.STRING.value);
                byteBuffer.writeBytes(encodeStringVal(stringVal));
                break;
            case Map<?, ?> mapVal:
                byteBuffer.writeByte((byte) AMF0Type.OBJECT.value);
                mapVal.forEach((key, value) -> {
                    byteBuffer.writeBytes(encodeStringVal(key.toString()));
                    byteBuffer.writeBytes(encodeMessage(value));
                });
                byteBuffer.writeBytes(AMF0Message.OBJECT_END_MARKER);
                break;
            default:
                log.warn("Not supported value type: {}", message.getClass());
                break;
        }
        return byteBuffer;
    }

    private static byte[] encodeBasicHeader(final int fmt, final int csid) {
        if (csid >= 2 && csid <= 63) {
            return new byte[]{(byte) ((fmt << 6) + csid)};
        } else if (csid >= 64 && csid <= 319) {
            return new byte[]{(byte) (fmt << 6), (byte) (csid - 64)};
        } else {
            return new byte[]{(byte) ((fmt << 6) | 1), (byte) ((csid - 64) & 0xff), (byte) ((csid - 64) >> 8)};
        }
    }

    private static byte[] encodeStringVal(String stringVal) {
        byte[] stringValBytes = stringVal.getBytes();
        int length = Math.min(stringValBytes.length, 65535);
        ByteBuffer buffer = ByteBuffer.allocate(length + 2);
        buffer.putShort((short) length);
        buffer.put(stringValBytes, 0, length);
        return buffer.array();
    }

    // ─── AMF0 decoding ───────────────────────────────────────────

    Object decodeAMF0CommandMessage(ByteBuffer currentMessageData) {
        int encodedType = Byte.toUnsignedInt(currentMessageData.get());
        switch (encodedType) {
            case 0x02, 0x0C:
                int length;
                if (encodedType == 0x02) {
                    length = currentMessageData.getShort();
                } else {
                    length = currentMessageData.getInt();
                }
                return AMF0Decoder.getString(currentMessageData, length);
            case 0:
                return Double.longBitsToDouble(currentMessageData.getLong());
            case 0x08:
            case 3:
                Map<String, Object> map = new LinkedHashMap<>();
                int count = 0;
                if (encodedType == 0x08) {
                    count = currentMessageData.getInt();
                }
                log.info("[{}] Process AMF0 MAP/OBJECT message, count: {}", connectionId, count);
                int i = 0;
                final byte[] endMarker = new byte[3];
                while (currentMessageData.hasRemaining()) {
                    currentMessageData.get(currentMessageData.position(), endMarker);
                    if (Arrays.equals(endMarker, AMF0Message.OBJECT_END_MARKER)) {
                        currentMessageData.get(new byte[3]);
                        log.info("[{}] Finished reading MAP/OBJECT data when reaching end marker", connectionId);
                        break;
                    }
                    if (count > 0 && i == count) {
                        log.info("[{}] Finished reading MAP/OBJECT data", connectionId);
                        break;
                    }
                    short propertySize = currentMessageData.getShort();
                    byte[] keyArr = new byte[propertySize];
                    currentMessageData.get(keyArr);
                    String key = new String(keyArr);
                    Object value = decodeAMF0CommandMessage(currentMessageData);
                    map.put(key, value);
                    i++;
                }
                return map;
            case 0x01:
                break;
            case 0x0A:
                break;
            case 0x0B:
                break;
            case 0x05:
                break;
            case 0x06:
                break;
            case 0x0D:
                return null;
            default:
                return null;
        }
        return null;
    }

    // ─── HLS / FFmpeg ────────────────────────────────────────────

    private void startFfmpeg() throws IOException {
        new File(hlsBaseDir + "/hd").mkdirs();
        writeMasterPlaylist();

        List<String> command = new ArrayList<>(List.of("hls-segmenter",
                "--out-dir", hlsBaseDir + "/hd",
                "--hls-time", "2",
                "--hls-list-size", "10",
                "--hls-delete-threshold", "1"));
        if (hlsBucket != null && !hlsBucket.isBlank()) {
            command.add("--s3-bucket");
            command.add(hlsBucket);
            if (hlsRegion != null && !hlsRegion.isBlank()) {
                command.add("--s3-region");
                command.add(hlsRegion);
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        ffmpegProcess = processBuilder.start();

        Thread.ofVirtual().start(() -> {
            applyMdc();
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(ffmpegProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    ffmpegLog.info("[{}] {}", connectionId, line);
                    if (line.contains("fps=")) {
                        try {
                            int fpsIdx = line.indexOf("fps=");
                            ffmpegFps = line.substring(fpsIdx + 4).trim().split("\\s+")[0];
                            int brIdx = line.indexOf("bitrate=");
                            if (brIdx >= 0) {
                                ffmpegBitrate = line.substring(brIdx + 8).trim().split("\\s+")[0];
                            }
                            int spIdx = line.indexOf("speed=");
                            if (spIdx >= 0) {
                                ffmpegSpeed = line.substring(spIdx + 6).trim().split("\\s+")[0];
                            }
                        } catch (Exception ignore) {
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                MDC.clear();
            }
        });

        log.info("[{}] FFmpeg started for stream: {}", connectionId, streamName);
    }

    private void writeMasterPlaylist() throws IOException {
        String playlist = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,NAME="HD"
                hd/output.m3u8
                """;
        Files.writeString(Path.of(hlsBaseDir, "master.m3u8"), playlist);
        log.info("[{}] Written master playlist for stream: {}", connectionId, streamName);
    }

    // ─── Statistics ───────────────────────────────────────────────

    private void startStatsReporter() {
        streaming = true;
        Thread.ofVirtual().start(() -> {
            applyMdc();
            while (streaming && !socket.isClosed()) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (streaming && !socket.isClosed()) {
                    logStats();
                }
            }
            MDC.clear();
        });
    }

    private void startSessionHeartbeat() {
        Thread.ofVirtual().start(() -> {
            applyMdc();
            while (streaming && !socket.isClosed() && sessionLookupKey != null) {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (streaming && !socket.isClosed()) {
                    boolean refreshed = streamSessionService.heartbeat(sessionLookupKey, serverId, connectionId);
                    if (!refreshed) {
                        log.warn("[{}] Stream session heartbeat lease lost | playbackId={} | keyFingerprint={}",
                                connectionId, streamName, keyFingerprint);
                        close();
                        break;
                    }
                }
            }
            MDC.clear();
        });
    }

    private void applyMdc() {
        MDC.put("serverId", serverId);
        MDC.put("connectionId", connectionId);
        MDC.put("publisherIp", connectionIp);
        if (streamName != null) {
            MDC.put("playbackId", streamName);
        }
    }

    private void logStats() {
        if (streamStartWallTime == 0) return;
        long uptimeSec = (System.currentTimeMillis() - streamStartWallTime) / 1000;
        long delayMs = 0;
        if (firstMediaTimestamp >= 0) {
            delayMs = (System.currentTimeMillis() - streamStartWallTime) - (lastMediaTimestamp - firstMediaTimestamp);
            if (delayMs < 0) delayMs = 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("STATS | uptime=").append(uptimeSec).append("s");
        sb.append(" | audio=").append(audioPackets).append("p/").append(formatBytes(audioBytes));
        sb.append(" | video=").append(videoPackets).append("p/").append(formatBytes(videoBytes));
        sb.append(" | kf=").append(keyframeCount);
        sb.append(" | delay=").append(String.format("%.1fs", delayMs / 1000.0));
        sb.append(" | lost=").append(droppedPackets);
        if (ffmpegFps != null || ffmpegBitrate != null) {
            sb.append(" | ffmpeg={");
            if (ffmpegFps != null) sb.append("fps=").append(ffmpegFps).append(",");
            if (ffmpegBitrate != null) sb.append("bitrate=").append(ffmpegBitrate).append(",");
            if (ffmpegSpeed != null) sb.append("speed=").append(ffmpegSpeed);
            sb.append("}");
        }
        log.info("[{}] {}", connectionId, sb.toString());
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("streamName", streamName != null ? streamName : "unknown");
        stats.put("connectionId", connectionId);
        stats.put("connectionIp", connectionIp);

        String thumbBase = hlsCdnUrl != null && !hlsCdnUrl.isBlank()
                ? hlsCdnUrl + "/hls/" : "/hls/";
        stats.put("thumbnailUrl", thumbBase + streamName + "/thumbnail.jpg");

        if (streamStartWallTime > 0) {
            long elapsed = System.currentTimeMillis() - streamStartWallTime;
            stats.put("uptimeSec", elapsed / 1000);
            if (firstMediaTimestamp >= 0) {
                long rtmpElapsed = lastMediaTimestamp - firstMediaTimestamp;
                long delayMs = elapsed - rtmpElapsed;
                stats.put("delayMs", Math.max(0, delayMs));
                stats.put("rtmpElapsedMs", rtmpElapsed);
            }
        }

        stats.put("audioPackets", audioPackets);
        stats.put("videoPackets", videoPackets);
        stats.put("audioBytes", audioBytes);
        stats.put("videoBytes", videoBytes);
        stats.put("audioBytesHuman", formatBytes(audioBytes));
        stats.put("videoBytesHuman", formatBytes(videoBytes));
        stats.put("totalBytesToFfmpeg", bytesToFfmpeg);
        stats.put("bytesToFfmpegHuman", formatBytes(bytesToFfmpeg));
        stats.put("keyframeCount", keyframeCount);
        stats.put("maxKeyframeIntervalMs", maxKeyframeInterval);
        stats.put("droppedPackets", droppedPackets);

        if (ffmpegFps != null) stats.put("ffmpegFps", ffmpegFps);
        if (ffmpegBitrate != null) stats.put("ffmpegBitrate", ffmpegBitrate);
        if (ffmpegSpeed != null) stats.put("ffmpegSpeed", ffmpegSpeed);

        if (streamStartWallTime > 0) {
            long elapsedSec = (System.currentTimeMillis() - streamStartWallTime) / 1000;
            if (elapsedSec > 0) {
                long totalBytes = audioBytes + videoBytes;
                long bps = totalBytes / elapsedSec;
                stats.put("bitrateBps", bps);
                stats.put("bitrateHuman", formatBitrate(bps * 8));
            }
        }

        return stats;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatBitrate(long bps) {
        if (bps < 1000) return bps + "bps";
        if (bps < 1000_000) return String.format("%.1fKbps", bps / 1000.0);
        return String.format("%.1fMbps", bps / 1000_000.0);
    }

    // ─── Cleanup ─────────────────────────────────────────────────

    private void cleanup() {
        streaming = false;
        logStats();
        if (ffmpegProcess != null) {
            try {
                ffmpegProcess.getOutputStream().close();
            } catch (IOException ignored) {
            }
            try {
                if (!ffmpegProcess.waitFor(10, TimeUnit.SECONDS)) {
                    ffmpegProcess.destroyForcibly();
                    ffmpegProcess.waitFor(3, TimeUnit.SECONDS);
                }
                if (ffmpegProcess.isAlive()) {
                    log.info("[{}] FFmpeg still alive after cleanup, ignoring", connectionId);
                } else {
                    log.info("[{}] FFmpeg process finished with exit code: {}", connectionId, ffmpegProcess.exitValue());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ffmpegProcess.destroyForcibly();
            }
            ffmpegProcess = null;
            sentFlvHeader = false;
        }

        if (streamName != null) {
            server.unregisterStream(streamName, this);
        }
        if (sessionLookupKey != null) {
            streamSessionService.disconnect(sessionLookupKey, serverId, connectionId);
        }
    }

    // ─── Static utilities ────────────────────────────────────────

    public static byte[] readToBuffer(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int bytesRead = in.read(buffer, offset, length - offset);
            if (bytesRead == -1) {
                throw new IOException("End of stream");
            }
            offset += bytesRead;
        }
        return buffer;
    }

    public static void fillRandomBytes(byte[] arr, int fromIndex, int toIndex) {
        for (int i = fromIndex; i < toIndex; i++) {
            arr[i] = (byte) randomSeed.nextInt(256);
        }
    }

    public static byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        randomSeed.nextBytes(bytes);
        return bytes;
    }

    private static int readIntFrom3Bytes(InputStream inputStream) throws IOException {
        byte[] threeBytes = inputStream.readNBytes(3);
        byte[] fourBytes = new byte[4];
        System.arraycopy(threeBytes, 0, fourBytes, 1, threeBytes.length);
        return ByteBuffer.wrap(fourBytes).getInt();
    }
}
