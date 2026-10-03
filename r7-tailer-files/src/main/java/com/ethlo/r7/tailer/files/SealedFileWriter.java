package com.ethlo.r7.tailer.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.r7f.JournalFiles;

/**
 * Records appended to a rotating sequence of local files, rolled on size or age, with the
 * open/sealed lifecycle the journal itself uses for {@code .flux} to {@code .r7f}.
 * <p>
 * A file is written as {@code <prefix>-<millis>-<uuid><extension>.open} and, once nothing more
 * will be written to it, fsync'd and atomically renamed to its final name. A consumer that picks
 * up finished files by name never sees one still being written. Files are created with the
 * permissions of a journal segment ({@link JournalFiles}): they carry the same request and
 * response data.
 * <p>
 * The {@link Format} says what a complete record looks like, so the two failure cases can be
 * cut back to one:
 * <ul>
 *     <li><b>A failed append</b> is cut back to where it started, and the file stays open. Every
 *     record before it was delivered and checkpointed past, so it must not be thrown away with
 *     the failed one; the failed record is offered again by the tailer.</li>
 *     <li><b>A crash</b> leaves the current file under its {@code .open} name. On the next start
 *     each such file is cut back to its last complete record and sealed: what was in it reached
 *     the OS before the tailer checkpointed past it, so it is part of the output and must not be
 *     left where no consumer looks.</li>
 * </ul>
 * Rolling happens only between appends ({@link #rollIfFull()}, {@link #rollIfStale()}), so a
 * record is never split across files. The size is a ceiling a file crosses, not one it keeps:
 * a record larger than the limit gets a file of its own.
 */
public final class SealedFileWriter implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(SealedFileWriter.class);

    /**
     * Same floor and the same reason as {@code R7fJournalProvider.MIN_SEGMENT_SIZE}: a rollover
     * size that cannot hold a single record would roll on every one, so it is refused at
     * construction instead.
     */
    public static final long MIN_ROLLOVER_SIZE = 64L * 1024L;

    static final String OPEN_SUFFIX = ".open";

    /**
     * What a file of this output holds.
     */
    public interface Format
    {
        /**
         * The sealed file's extension, such as {@code .jsonl}.
         */
        String extension();

        /**
         * The length of the longest prefix of {@code file} made of complete records; a crash
         * leaves the file cut back to this.
         */
        long endOfLastCompleteRecord(FileChannel file) throws IOException;

        /**
         * Called when a new file is opened, before anything is written to it. A format with a
         * file header appends it here; that header alone does not make the file worth sealing.
         *
         * @param fileName the name the file will have once sealed
         */
        default void opened(final SealedFileWriter writer, final String fileName) throws IOException
        {
        }

        /**
         * Called with a finished file, fsync'd, just before it is renamed to {@code sealed}: the
         * place for a companion file (an index) that must exist wherever the sealed file does.
         * A throw leaves the file open, to be sealed again later.
         */
        default void sealing(final Path file, final Path sealed) throws IOException
        {
        }
    }

    private final Path directory;
    private final String filePrefix;
    private final long maxFileSizeBytes;
    private final long maxFileAgeMillis;
    private final Format format;
    private final UnaryOperator<FileChannel> channelWrapper;

    private FileChannel channel;
    private Path openPath;
    private String fileName;
    private long size;
    private long headerSize;
    private long openedAtMillis;

    public SealedFileWriter(final Path directory, final String filePrefix, final long maxFileSizeBytes, final long maxFileAgeMillis,
                            final Format format) throws IOException
    {
        this(directory, filePrefix, maxFileSizeBytes, maxFileAgeMillis, format, UnaryOperator.identity());
    }

    /**
     * @param channelWrapper wraps each file's channel; lets a test fail a write partway
     */
    SealedFileWriter(final Path directory, final String filePrefix, final long maxFileSizeBytes, final long maxFileAgeMillis,
                     final Format format, final UnaryOperator<FileChannel> channelWrapper) throws IOException
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
        this.format = format;
        this.channelWrapper = channelWrapper;

        JournalFiles.createDirectories(directory);
        sealLeftovers();
    }

    /**
     * Opens a file if none is open, so a caller that numbers records per file can do so before
     * building them. {@link #append} does this itself.
     */
    public synchronized void ensureOpen() throws IOException
    {
        if (channel == null)
        {
            open();
        }
    }

    /**
     * Appends one or more complete records as one unit. If the write fails, the file is cut
     * back to where this append started and the exception rethrown: the records before it stay,
     * and none of this one does.
     *
     * @return the offset in the current file where the bytes start
     */
    public synchronized long append(final ByteBuffer records) throws IOException
    {
        ensureOpen();
        final long start = size;
        try
        {
            while (records.hasRemaining())
            {
                size += channel.write(records);
            }
        }
        catch (final IOException | RuntimeException e)
        {
            cutBack(start);
            throw e;
        }
        return start;
    }

    public synchronized long append(final byte[] records) throws IOException
    {
        return append(ByteBuffer.wrap(records));
    }

    /**
     * Takes back everything appended to the current file from {@code start} on: the place for a
     * caller whose unit of work spans more than this file, when a later step of that unit fails
     * after this append succeeded. Without it the retry would write the same records a second
     * time. {@code start} is an offset {@link #append} returned for the file still current.
     * <p>
     * If the cut-back itself fails, the file is closed and left under its {@code .open} name,
     * as after a failed append; recovery on the next start keeps its complete records.
     */
    public synchronized void discardFrom(final long start)
    {
        if (channel == null || start < headerSize || start > size)
        {
            throw new IllegalStateException("Nothing appended at " + start + " in the current file (" + fileName + ", " + size + " bytes)");
        }
        cutBack(start);
    }

    /**
     * The name the current file will have once sealed, or {@code null} when no file is open.
     */
    public synchronized String fileName()
    {
        return channel != null ? fileName : null;
    }

    /**
     * The current file's length, or {@code 0} when no file is open.
     */
    public synchronized long size()
    {
        return channel != null ? size : 0;
    }

    /**
     * Seals the current file if it has reached the size limit.
     */
    public synchronized void rollIfFull() throws IOException
    {
        if (channel != null && size >= maxFileSizeBytes)
        {
            seal();
        }
    }

    /**
     * Seals the current file if it is older than the age limit and holds a record. Called from
     * the tailer's loop, so a quiet stream still produces finished files on time, and before
     * each append, because one read of a backlog can run far longer than the age limit.
     */
    public synchronized void rollIfStale() throws IOException
    {
        if (channel != null && size > headerSize && System.currentTimeMillis() - openedAtMillis >= maxFileAgeMillis)
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
        final String name = filePrefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + format.extension();
        final Path path = directory.resolve(name + OPEN_SUFFIX);
        channel = channelWrapper.apply(FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                JournalFiles.fileAttributes(directory)));
        openPath = path;
        fileName = name;
        size = 0;
        headerSize = 0;
        openedAtMillis = System.currentTimeMillis();
        try
        {
            format.opened(this, name);
        }
        catch (final IOException | RuntimeException e)
        {
            // Nothing in the file yet but a partial header: drop it, and open afresh next time.
            closeQuietly();
            Files.deleteIfExists(path);
            openPath = null;
            throw e;
        }
        headerSize = size;
    }

    /**
     * Cuts the file back to {@code length} after a failed append. If even that fails, the file's
     * end is unknown, so it is closed and left under its {@code .open} name for the next start
     * to cut back to its last complete record; the next append opens a new file.
     */
    private void cutBack(final long length)
    {
        try
        {
            channel.truncate(length);
            channel.position(length);
            size = length;
        }
        catch (final IOException | RuntimeException e)
        {
            logger.error("Could not cut {} back after a failed write; leaving it to be recovered on the next start", openPath, e);
            closeQuietly();
            openPath = null;
        }
    }

    private void closeQuietly()
    {
        if (channel == null)
        {
            // Already closed by a failed cut-back inside a header write.
            return;
        }
        try
        {
            channel.close();
        }
        catch (final IOException e)
        {
            logger.warn("Failed to close {}", fileName, e);
        }
        channel = null;
    }

    /**
     * Fsyncs the current file and renames it to its final name; a file holding nothing but its
     * header is deleted instead. A failure leaves the file open, and the next seal retries it.
     */
    private void seal() throws IOException
    {
        if (channel == null)
        {
            return;
        }
        channel.force(true);
        if (size <= headerSize)
        {
            // Deleted before it is closed, as a seal renames before closing: a failed delete
            // leaves it the current file, and the next seal retries it.
            Files.deleteIfExists(openPath);
            openPath = null;
            closeQuietly();
            return;
        }
        final Path sealed = directory.resolve(fileName);
        format.sealing(openPath, sealed);
        // Renamed while still open, and closed only after: if the rename fails, the file is
        // still the current one, and the next seal retries it rather than losing track of it.
        Files.move(openPath, sealed, StandardCopyOption.ATOMIC_MOVE);
        openPath = null;
        closeQuietly();
        logger.info("Sealed {}", sealed.getFileName());
    }

    private void sealLeftovers() throws IOException
    {
        final String openExtension = format.extension() + OPEN_SUFFIX;
        final List<Path> leftovers;
        try (Stream<Path> files = Files.list(directory))
        {
            leftovers = files.filter(p ->
            {
                final String name = p.getFileName().toString();
                return name.startsWith(filePrefix + "-") && name.endsWith(openExtension);
            }).toList();
        }
        for (final Path leftover : leftovers)
        {
            final long complete;
            try (FileChannel file = FileChannel.open(leftover, StandardOpenOption.READ, StandardOpenOption.WRITE))
            {
                complete = format.endOfLastCompleteRecord(file);
                if (complete < file.size())
                {
                    logger.warn("Cut a torn last record ({} bytes) from {}; it will be written again",
                            file.size() - complete, leftover.getFileName());
                    file.truncate(complete);
                }
                file.force(true);
            }
            if (complete == 0)
            {
                Files.delete(leftover);
                continue;
            }
            final String name = leftover.getFileName().toString();
            final Path sealed = leftover.resolveSibling(name.substring(0, name.length() - OPEN_SUFFIX.length()));
            format.sealing(leftover, sealed);
            Files.move(leftover, sealed, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Sealed {}, left open by an earlier run", sealed.getFileName());
        }
    }
}
