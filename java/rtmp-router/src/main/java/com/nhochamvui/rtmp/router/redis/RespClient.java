package com.nhochamvui.rtmp.router.redis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RespClient implements Closeable {

    private static final byte[] CRLF = {'\r', '\n'};

    private final Socket socket;
    private final BufferedInputStream in;
    private final BufferedOutputStream out;

    public RespClient(String host, int port, int timeoutMs) throws IOException {
        this.socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        socket.setTcpNoDelay(true);
        this.in = new BufferedInputStream(socket.getInputStream(), 8192);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 8192);
    }

    public synchronized Object command(String... args) throws IOException {
        out.write('*');
        writeAscii(Integer.toString(args.length));
        out.write(CRLF);
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            out.write('$');
            writeAscii(Integer.toString(bytes.length));
            out.write(CRLF);
            out.write(bytes);
            out.write(CRLF);
        }
        out.flush();
        return readReply();
    }

    private Object readReply() throws IOException {
        int type = in.read();
        switch (type) {
            case '+':
                return readLine();
            case '-':
                throw new IOException("Redis error: " + readLine());
            case ':':
                return Long.parseLong(readLine());
            case '$':
                return readBulk();
            case '*':
                return readArray();
            case -1:
                throw new EOFException("Redis connection closed");
            default:
                throw new IOException("Unexpected RESP type: " + (char) type);
        }
    }

    private Object readBulk() throws IOException {
        int length = Integer.parseInt(readLine());
        if (length < 0) {
            return null;
        }
        byte[] data = new byte[length];
        readFully(data);
        readLine();
        return new String(data, StandardCharsets.UTF_8);
    }

    private List<Object> readArray() throws IOException {
        int count = Integer.parseInt(readLine());
        if (count < 0) {
            return null;
        }
        List<Object> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(readReply());
        }
        return items;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\r') {
                int next = in.read();
                if (next == '\n' || next == -1) {
                    break;
                }
                buffer.write('\r');
                buffer.write(next);
            } else {
                buffer.write(read);
            }
        }
        if (read == -1) {
            throw new EOFException("Redis connection closed");
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void readFully(byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int read = in.read(data, offset, data.length - offset);
            if (read == -1) {
                throw new EOFException("Redis connection closed");
            }
            offset += read;
        }
    }

    private void writeAscii(String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            out.write(value.charAt(i));
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
