package com.ethlo.r7.r7f;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A per-shard counter the writer bumps after every commit, so a reader sharing the page cache
 * can tell that a shard has something new without decoding or listing anything
 * (design/history/live-tailing.md, step 2).
 * <p>
 * {@code shard-<id>.ctl}, one cache line: the magic {@code R7CS} at 0, the counter at 8, big-endian
 * like the rest of the framing. The counter is a hint and nothing else. It says "look again", never
 * what is there: a reader still decodes and validates the segment exactly as before, so a counter
 * that is stale, reset by a restart, or missing costs at most a wasted look or one poll interval.
 * That is also why the file is not fsync'd, not recovered and not part of the format.
 * <p>
 * The writer's cost is one release store per entry into a line only it writes. It is a plain store
 * on x86 and a {@code stlr} on arm64; there is no syscall and no allocation.
 */
public final class CommitSignal
{
    private static final Logger logger = LoggerFactory.getLogger(CommitSignal.class);

    public static final String EXTENSION = ".ctl";
    static final int MAGIC = 0x52374353; // "R7CS"
    static final int SIZE = 64;
    static final long OFF_COUNTER = 8;

    private static final VarHandle COUNTER = ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN).varHandle();
    private static final VarHandle READ_COUNTER = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * For a writer that could not create its signal: commits are just not announced, and
     * readers fall back to polling.
     */
    static final CommitSignal NONE = new CommitSignal(null, null);

    private final Arena arena;
    private final MemorySegment segment;
    private long count;

    private CommitSignal(final Arena arena, final MemorySegment segment)
    {
        this.arena = arena;
        this.segment = segment;
    }

    public static Path pathFor(final Path journalDir, final int shardId)
    {
        return journalDir.resolve("shard-" + shardId + EXTENSION);
    }

    /**
     * Creates or takes over the shard's signal file. Never fails the journal: without it,
     * readers poll as they always did.
     */
    static CommitSignal open(final Path journalDir, final int shardId)
    {
        final Path path = pathFor(journalDir, shardId);
        try (FileChannel channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE),
                JournalFiles.fileAttributes(journalDir)))
        {
            final Arena arena = Arena.ofShared();
            final MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE, arena);
            segment.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0, MAGIC);
            final CommitSignal signal = new CommitSignal(arena, segment);
            // Carry on from whatever is there: a reader comparing against a value it saw from an
            // earlier writer must still see this one move.
            signal.count = (long) COUNTER.getAcquire(segment, OFF_COUNTER);
            return signal;
        }
        catch (final IOException | RuntimeException e)
        {
            logger.warn("Could not create commit signal {}; tailers will poll this shard: {}", path, e.toString());
            return NONE;
        }
    }

    /**
     * Called by the writer after each commit, under its monitor.
     */
    void signal()
    {
        if (segment != null)
        {
            COUNTER.setRelease(segment, OFF_COUNTER, ++count);
        }
    }

    void close()
    {
        if (arena != null)
        {
            arena.close();
        }
    }

    /**
     * Maps a shard's signal for reading, or returns {@code null} if there is none (an older
     * writer, or one that could not create it). Read-only, so it works on a journal directory
     * mounted read-only.
     */
    public static ByteBuffer mapForReading(final Path path) throws IOException
    {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ))
        {
            if (channel.size() < SIZE)
            {
                return null;
            }
            final MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, SIZE);
            return buffer.order(ByteOrder.BIG_ENDIAN).getInt(0) == MAGIC ? buffer : null;
        }
        catch (final NoSuchFileException e)
        {
            return null;
        }
    }

    public static long read(final ByteBuffer mapped)
    {
        return (long) READ_COUNTER.getAcquire(mapped, (int) OFF_COUNTER);
    }

    static boolean isSignalFile(final Path path)
    {
        final String name = path.getFileName().toString();
        return name.startsWith("shard-") && name.endsWith(EXTENSION) && Files.isRegularFile(path);
    }
}
