package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.ethlo.venturi.api.GatewayHeaders;

public final class Journal
{
    private static final ThreadLocal<CharsetEncoder> ENCODER = ThreadLocal.withInitial(StandardCharsets.UTF_8::newEncoder);
    private final MappedByteBuffer buffer;
    private final Path path;

    public Journal(Path path, long size) throws IOException
    {
        System.out.println("Creating Journal for " + path + " - " + Thread.currentThread().getName());
        this.path = path;
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw"))
        {
            raf.setLength(size);
            this.buffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size);
        }
    }

    public void writeBegin(int dir, CharSequence reqId, ByteBuffer startLine, GatewayHeaders headers) {
        buffer.put(Marker.BEGIN);
        buffer.putInt(dir);

        writeString(reqId);
        writeBuffer(startLine);

        // 1. Record the current position and put a dummy 4-byte int
        int countPos = buffer.position();
        buffer.putInt(0);

        // 2. Write headers
        int[] count = {0};
        headers.forEach((k, v) -> {
            writeString(k);
            writeString(v);
            count[0]++;
        });

        // 3. Backfill the count at countPos
        // IMPORTANT: putInt(index, val) does NOT change the current position
        buffer.putInt(countPos, count[0]);
    }

    public void writeBody(CharSequence reqId, ByteBuffer data)
    {
        buffer.put(Marker.BODY);
        writeString(reqId);
        writeBuffer(data);
    }

    public void writeEnd(CharSequence reqId)
    {
        buffer.put(Marker.END);
        writeString(reqId);
        buffer.putLong(System.currentTimeMillis());
    }

    private void writeString(CharSequence s) {
        if (s == null) {
            buffer.putInt(-1);
            return;
        }
        // Get bytes first to ensure we know the exact length
        byte[] bytes = s.toString().getBytes(StandardCharsets.UTF_8);

        // RELATIVE PUTS ONLY
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    private void writeBuffer(ByteBuffer src) {
        if (src == null) {
            buffer.putInt(-1);
            return;
        }
        ByteBuffer slice = src.slice();
        int len = slice.remaining();
        buffer.putInt(len);
        buffer.put(slice);
    }

    public boolean hasSpace(int bytesNeeded)
    {
        return buffer.remaining() >= bytesNeeded;
    }

    public Path getPath()
    {
        return path;
    }

    public void force()
    {
        buffer.force();
    }
}