package com.tradewind.stream.ws;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A bare-socket WebSocket client that can simply stop reading, with a tiny receive buffer, to play a client whose
 * network or browser tab has stalled. JDK and Netty clients keep reading into their own buffers, so they can't.
 */
class RawWebSocket implements AutoCloseable {

    final Socket socket = new Socket();
    final DataInputStream in;

    RawWebSocket(int port, UUID userId, UUID accountId) throws IOException {
        socket.setReceiveBufferSize(2048);
        socket.connect(new InetSocketAddress("localhost", port), 5000);
        socket.setSoTimeout(10_000);
        String request = "GET /ws HTTP/1.1\r\nHost: localhost:" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                + "X-User-Id: " + userId + "\r\nX-Account-Id: " + accountId + "\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        in = new DataInputStream(socket.getInputStream());
        String status = readHeaders(in);
        if (!status.startsWith("HTTP/1.1 101")) {
            throw new IOException("handshake failed: " + status);
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            head.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        return head.toString(StandardCharsets.US_ASCII).lines().findFirst().orElse("");
    }

    /** Sends one masked text frame (clients must mask). */
    void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        if (payload.length > 125) {
            throw new IllegalArgumentException("test frames are small");
        }
        byte[] mask = {1, 2, 3, 4};
        byte[] frame = new byte[2 + 4 + payload.length];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, frame, 2, 4);
        for (int i = 0; i < payload.length; i++) {
            frame[6 + i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        socket.getOutputStream().write(frame);
        socket.getOutputStream().flush();
    }

    /**
     * Reads frames until a close frame and returns its status code, or -1 if the connection ended without one.
     * {@code frames[0]} receives the number of data frames read first.
     */
    int readUntilClose(long[] frames) throws IOException {
        try {
            while (true) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                long length = b1 & 0x7F;
                if (length == 126) {
                    length = in.readUnsignedShort();
                } else if (length == 127) {
                    length = in.readLong();
                }
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                if ((b0 & 0x0F) == 0x8) {
                    return payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
                }
                frames[0]++;
            }
        } catch (EOFException | SocketTimeoutException e) {
            return -1;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
