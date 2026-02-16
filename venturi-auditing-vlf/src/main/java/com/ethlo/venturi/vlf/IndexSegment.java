package com.ethlo.venturi.vlf;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class IndexSegment implements AutoCloseable
{
    public static final int ID_SIZE = 32;
    public static final int RECORD_SIZE = ID_SIZE + 4 + 8; // ID(32) + FileID(4) + Offset(8)

    private final MappedByteBuffer buffer;

    public IndexSegment(Path path, long size) throws IOException
    {
        try (FileChannel fc = FileChannel.open(path,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE
        ))
        {
            this.buffer = fc.map(FileChannel.MapMode.READ_WRITE, 0, size);
        }
    }

    public boolean hasSpace()
    {
        return buffer.remaining() >= RECORD_SIZE;
    }

    public void record(CharSequence reqId, int fileId, long offset)
    {
        int len = reqId.length();
        int toCopy = Math.min(len, ID_SIZE);

        for (int i = 0; i < toCopy; i++)
        {
            buffer.put((byte) reqId.charAt(i));
        }
        for (int i = toCopy; i < ID_SIZE; i++)
        {
            buffer.put((byte) 0);
        }

        buffer.putInt(fileId);
        buffer.putLong(offset);
    }

    @Override
    public void close()
    {
        if (buffer != null)
        {
            buffer.force();
            VlfJournal.unmap(buffer);
        }
    }
}