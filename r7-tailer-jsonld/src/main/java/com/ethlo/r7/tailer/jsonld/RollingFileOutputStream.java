package com.ethlo.r7.tailer.jsonld;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.r7f.JournalFiles;

/**
 * JSON lines written to a rotating sequence of files, rolled on size or age.
 * <p>
 * The same open/sealed lifecycle as the WARC tailer and the journal: a file is written as
 * {@code <prefix>-<millis>-<uuid>.jsonl.open} and, once nothing more will be written to it,
 * fsync'd and atomically renamed to its {@code .jsonl} name. A consumer that picks up finished
 * files by name never sees one that is still being written.
 * <p>
 * Rotation happens only in {@link #flush()}, which the JSON writer calls once per record, so a
 * record is never split across two files.
 * <p>
 * A crash leaves the current file under its {@code .open} name. Every record in it was
 * flushed to the OS before the tailer checkpointed past it, so it is part of the output and
 * must not be left where no consumer looks: on start, each leftover {@code .open} file is cut
 * back to its last complete line (a torn last line belongs to a record the tailer had not
 * checkpointed, and will write again) and sealed.
 */
final class RollingFileOutputStream extends OutputStream
{
    private static final Logger logger = LoggerFactory.getLogger(RollingFileOutputStream.class);

    /**
     * Same floor, and the same reason, as the WARC tailer's: a size that cannot hold a record
     * would roll on every one.
     */
    static final long MIN_ROLLOVER_SIZE = 64L * 1024L;

    static final String EXTENSION = ".jsonl";
    static final String OPEN_SUFFIX = ".open";

    private final Path directory;
    private final String filePrefix;
    private final long maxFileSizeBytes;
    private final long maxFileAgeMillis;

    private FileChannel channel;
    private Path openPath;
    private long size;
    private long openedAtMillis;
    private final byte[] one = new byte[1];

    RollingFileOutputStream(final Path directory, final String filePrefix, final long maxFileSizeBytes, final long maxFileAgeMillis) throws IOException
    {
        if (maxFileSizeBytes < MIN_ROLLOVER_SIZE)
        {
            throw new IllegalArgumentException("maxFileSizeBytes must be at least " + MIN_ROLLOVER_SIZE + ", but was " + maxFileSizeBytes);
        }
        if (maxFileAgeMillis <= 0)
        {
            throw new IllegalArgumentException("maxFileAgeMillis must be positive, but was " + maxFileAgeMillis);
        }
        this.directory = directory;
        this.filePrefix = filePrefix;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxFileAgeMillis = maxFileAgeMillis;

        JournalFiles.createDirectories(directory);
        sealLeftovers();
    }

    @Override
    public synchronized void write(final int b) throws IOException
    {
        one[0] = (byte) b;
        write(one, 0, 1);
    }

    @Override
    public synchronized void write(final byte[] b, final int off, final int len) throws IOException
    {
        if (channel == null)
        {
            open();
        }
        final ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
        while (buffer.hasRemaining())
        {
            size += channel.write(buffer);
        }
    }

    /**
     * Called once per record. The bytes are already with the OS (the channel is unbuffered),
     * which is what a process crash needs; rolling, if due, happens here, between records.
     */
    @Override
    public synchronized void flush() throws IOException
    {
        if (channel != null && size >= maxFileSizeBytes)
        {
            seal();
        }
    }

    /**
     * Seals the current file if it is older than the age limit and holds anything. Called from
     * the tailer's loop, so a quiet stream still produces finished files on time.
     */
    synchronized void rollIfStale() throws IOException
    {
        if (channel != null && size > 0 && System.currentTimeMillis() - openedAtMillis >= maxFileAgeMillis)
        {
            seal();
        }
    }

    @Override
    public synchronized void close() throws IOException
    {
        seal();
    }

    private void open() throws IOException
    {
        final String name = filePrefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + EXTENSION;
        openPath = directory.resolve(name + OPEN_SUFFIX);
        channel = FileChannel.open(openPath,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                JournalFiles.fileAttributes(directory));
        size = 0;
        openedAtMillis = System.currentTimeMillis();
    }

    private void seal() throws IOException
    {
        if (channel == null)
        {
            return;
        }
        channel.force(true);
        channel.close();
        channel = null;
        finish(openPath, size);
        openPath = null;
    }

    private static void finish(final Path open, final long size) throws IOException
    {
        if (size == 0)
        {
            Files.deleteIfExists(open);
            return;
        }
        final String name = open.getFileName().toString();
        final Path sealed = open.resolveSibling(name.substring(0, name.length() - OPEN_SUFFIX.length()));
        Files.move(open, sealed, StandardCopyOption.ATOMIC_MOVE);
        logger.info("Sealed JSON lines file {}", sealed.getFileName());
    }

    private void sealLeftovers() throws IOException
    {
        final List<Path> leftovers;
        try (Stream<Path> files = Files.list(directory))
        {
            leftovers = files.filter(p ->
            {
                final String name = p.getFileName().toString();
                return name.startsWith(filePrefix + "-") && name.endsWith(EXTENSION + OPEN_SUFFIX);
            }).toList();
        }
        for (final Path leftover : leftovers)
        {
            final long kept;
            try (FileChannel file = FileChannel.open(leftover, StandardOpenOption.READ, StandardOpenOption.WRITE))
            {
                kept = endOfLastLine(file);
                if (kept < file.size())
                {
                    logger.warn("Cut a torn last line ({} bytes) from {}; its record will be written again",
                            file.size() - kept, leftover.getFileName());
                    file.truncate(kept);
                }
                file.force(true);
            }
            finish(leftover, kept);
        }
    }

    /**
     * One past the last {@code '\n'} in the file, or 0 if there is none.
     */
    private static long endOfLastLine(final FileChannel file) throws IOException
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
