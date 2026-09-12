package com.nhochamvui.rtmp.core.functions;

import java.io.IOException;
import java.io.OutputStream;

public class MediaHandler {
    private static final byte[] FLV_HEADER = {
            0x46, 0x4c, 0x56,
            0x01,
            0x05,
            0x00, 0x00, 0x00, 0x09,
            0x00, 0x00, 0x00, 0x00
    };

    private final byte[] tagHeader = new byte[11];
    private final byte[] pvsBuf = new byte[4];

    public void writeFlvTag(OutputStream outputStream,
                            byte tagType, int timestamp,
                            byte[] payload, int offset, int length) throws IOException {
        tagHeader[0] = tagType;
        tagHeader[1] = (byte) ((length >> 16) & 0xFF);
        tagHeader[2] = (byte) ((length >> 8) & 0xFF);
        tagHeader[3] = (byte) (length & 0xFF);
        tagHeader[4] = (byte) ((timestamp >> 16) & 0xFF);
        tagHeader[5] = (byte) ((timestamp >> 8) & 0xFF);
        tagHeader[6] = (byte) (timestamp & 0xFF);
        tagHeader[7] = (byte) ((timestamp >> 24) & 0xFF);
        tagHeader[8] = 0;
        tagHeader[9] = 0;
        tagHeader[10] = 0;

        outputStream.write(tagHeader);
        outputStream.write(payload, offset, length);

        int previousTagSize = 11 + length;
        pvsBuf[0] = (byte) ((previousTagSize >> 24) & 0xFF);
        pvsBuf[1] = (byte) ((previousTagSize >> 16) & 0xFF);
        pvsBuf[2] = (byte) ((previousTagSize >> 8) & 0xFF);
        pvsBuf[3] = (byte) (previousTagSize & 0xFF);

        outputStream.write(pvsBuf);
        outputStream.flush();
    }

    public static byte[] createFlvHeader() {
        return FLV_HEADER;
    }
}
