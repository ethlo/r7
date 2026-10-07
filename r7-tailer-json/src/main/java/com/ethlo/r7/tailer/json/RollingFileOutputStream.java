package com.ethlo.r7.tailer.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

import com.ethlo.r7.tailer.files.SealedFileWriter;

/**
 * JSON lines written to rotating files ({@link SealedFileWriter}), named {@code .jsonl}.
 * <p>
 * The JSON writer flushes once per record, so what is written between two flushes is one
 * record: it is held here and appended as one unit on {@link #flush()}. A failed write is then
 * cut back to the end of the previous line instead of leaving half a line for the next record
 * to follow. Rolling, if due, happens before that append, so a record is never split across two
 * files and a failed roll never fails a record that was already written.
 * <p>
 * After a crash, a file left {@code .open} is cut back to its last complete line (a torn last
 * line belongs to a record the tailer had not checkpointed, and will write again) and sealed.
 */
public final class RollingFileOutputStream extends OutputStream
{
    static final long MIN_ROLLOVER_SIZE = SealedFileWriter.MIN_ROLLOVER_SIZE;

    static final String EXTENSION = ".jsonl";

    private final SealedFileWriter files;
    private final RecordBuffer record = new RecordBuffer();

    public RollingFileOutputStream(final Path directory, final String filePrefix, final long maxFileSizeBytes, final long maxFileAgeMillis) throws IOException
    {
        this.files = new SealedFileWriter(directory, filePrefix, maxFileSizeBytes, maxFileAgeMillis, new JsonLines());
    }

    @Override
    public synchronized void write(final int b)
    {
        record.write(b);
    }

    @Override
    public synchronized void write(final byte[] b, final int off, final int len)
    {
        record.write(b, off, len);
    }

    @Override
    public synchronized void flush() throws IOException
    {
        if (record.size() == 0)
        {
            return;
        }
        try
        {
            // Roll before the append, never after it: a failure after the record is written
            // would have the tailer offer the record again, and write it twice.
            // Age is checked here too, not only between reads: one read of a backlog can
            // outlast max_file_age.
            files.rollIfFull();
            files.rollIfStale();
            files.append(record.asByteBuffer());
        }
        finally
        {
            // Written or not, the record is done here: on failure the tailer offers it again.
            record.reset();
        }
    }

    /**
     * Seals the current file if it has reached the size limit, or is older than the age limit
     * and holds anything. Called after every read, so a full file does not wait for the next
     * record to be sealed.
     */
    public synchronized void rollIfStale() throws IOException
    {
        files.rollIfFull();
        files.rollIfStale();
    }

    /**
     * Seals the current file. A record not yet flushed is incomplete, and is not written.
     */
    @Override
    public synchronized void close() throws IOException
    {
        record.reset();
        files.close();
    }

    private static final class RecordBuffer extends ByteArrayOutputStream
    {
        RecordBuffer()
        {
            super(8192);
        }

        ByteBuffer asByteBuffer()
        {
            return ByteBuffer.wrap(buf, 0, count);
        }
    }

    private static final class JsonLines implements SealedFileWriter.Format
    {
        @Override
        public String extension()
        {
            return EXTENSION;
        }

        /**
         * One past the last {@code '\n'} in the file, or 0 if there is none.
         */
        @Override
        public long endOfLastCompleteRecord(final FileChannel file) throws IOException
        {
            final ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long end = file.size();
            while (end > 0)
            {
                final long start = Math.max(0, end - buffer.capacity());
                buffer.clear().limit((int) (end - start));
                file.read(buffer, start);
                for (int i = buffer.position() - 1; i >= 0; i--)
                {
                    if (buffer.get(i) == '\n')
                    {
                        return start + i + 1;
                    }
                }
                end = start;
            }
            return 0;
        }
    }
}
