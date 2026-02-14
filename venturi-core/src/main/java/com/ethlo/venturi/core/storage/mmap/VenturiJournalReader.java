package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class VenturiJournalReader {

    public void decode(Path journalPath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(journalPath.toFile(), "r")) {
            long size = raf.length();
            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, size);

            while (buffer.hasRemaining()) {
                byte marker = buffer.get();
                if (marker == 0) break; // Reached unwritten pre-allocated space

                switch (marker) {
                    case 0x01 -> handleBegin(buffer);
                    case 0x02 -> handleBody(buffer);
                    case 0x03 -> handleEnd(buffer);
                    default -> throw new IOException("Corrupt journal at position " + buffer.position());
                }
            }
        }
    }

    private void handleBegin(ByteBuffer buffer) {
        int dir = buffer.getInt();
        String reqId = readString(buffer);
        byte[] startLine = readBuffer(buffer);
        
        System.out.printf("[%s] BEGIN - Dir: %d%n", reqId, dir);
        System.out.println("> " + new String(startLine, StandardCharsets.UTF_8));

        int headerCount = buffer.getInt();
        for (int i = 0; i < headerCount; i++) {
            System.out.printf("  %s: %s%n", readString(buffer), readString(buffer));
        }
    }

    private void handleBody(ByteBuffer buffer) {
        String reqId = readString(buffer);
        byte[] body = readBuffer(buffer);
        System.out.printf("[%s] BODY (%d bytes)%n", reqId, body.length);
    }

    private void handleEnd(ByteBuffer buffer) {
        String reqId = readString(buffer);
        System.out.printf("[%s] COMPLETED%n%n", reqId);
    }

    private String readString(ByteBuffer buffer) {
        int len = buffer.getInt();
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] readBuffer(ByteBuffer buffer) {
        int len = buffer.getInt();
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return bytes;
    }
}