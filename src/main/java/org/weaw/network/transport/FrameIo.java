package org.weaw.network.transport;

import org.weaw.network.protocol.Protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

final class FrameIo {
    private FrameIo() {
    }

    static byte[] readFrame(DataInputStream input) throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException exception) {
            return null;
        }
        if (length <= 0 || length > Protocol.MAX_FRAME_BYTES) {
            throw new IOException("Invalid network frame length: " + length);
        }
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("Truncated network frame");
        }
        return payload;
    }

    static void writeFrame(DataOutputStream output, byte[] payload) throws IOException {
        if (payload.length <= 0 || payload.length > Protocol.MAX_FRAME_BYTES) {
            throw new IOException("Invalid outgoing frame length: " + payload.length);
        }
        output.writeInt(payload.length);
        output.write(payload);
        output.flush();
    }
}
