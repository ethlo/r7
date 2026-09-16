package com.ethlo.r7.r7f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class R7fJournalProvider implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(R7fJournalProvider.class);

    /**
     * A segment has to hold the preamble plus at least one entry to be of any use.
     */
    private static final long MIN_SEGMENT_SIZE = 64L * 1024L;

    /**
     * Extension of the per-shard sequence high-water marker. Deliberately not one of the
     * journal extensions, so the tailer's file filter passes over it.
     */
    private static final String SEQUENCE_MARKER_EXTENSION = ".seq";

    private final Path tempDir;
    private final int shardId;
    private final long segmentSizeBytes;

    /**
     * Monotonic per-shard segment counter, written into each segment's preamble and into
     * the file name. Seeded from the highest sequence already on disk, because the tailer
     * keys segments by shard and sequence: a counter that restarted at zero on every boot
     * would give a new segment the same key as a retained one, and the tailer would
     * silently read only one of them.
     */
    private final AtomicLong segmentSequence;

    /**
     * Where the high-water mark for {@link #segmentSequence} is kept.
     * <p>
     * Seeding from the segments on disk is not enough on its own: retention deletes them
     * once the tailer has read them, and a shard drained to empty would then start over at
     * one. The tailer keys segments by (shard, sequence) and keeps checkpoints under that
     * key, so a reused sequence can have a brand-new segment resumed at a dead one's
     * offset. This file is what makes the counter monotonic across a restart that finds no
     * segments at all.
     * <p>
     * This one <em>is</em> fsynced, unlike everything else here, and the paragraph above is
     * why: the claim that losing it "degrades to seeding from the segments on disk" is only
     * true while segments are still there to seed from, and the case this file exists for is
     * precisely the one where they are not. A marker that rolls back after a power loss while
     * a tailer checkpoint survives hands a new segment a key the checkpoint already answers
     * for, and that segment is resumed at a dead one's offset — skipped, or marked read and
     * deleted unread.
     * <p>
     * The no-fsync design is about the write path, where a sync per entry would cost
     * everything. This is one small write per segment rotation, and what it buys is the
     * uniqueness of segment identity. Different trade, different answer.
     */
    private final Path sequenceMarkerPath;

    // Hands a mapped file straight from the warmer thread to the writer
    private final BlockingQueue<WarmedSegment> pool = new SynchronousQueue<>();
    private final Thread warmerThread;
    private final boolean preFault;
    private volatile boolean running = true;

    public R7fJournalProvider(Path tempDir, int shardId, long segmentSizeBytes, boolean preFault)
    {
        if (segmentSizeBytes < MIN_SEGMENT_SIZE)
        {
            throw new IllegalArgumentException("segmentSizeBytes must be at least " + MIN_SEGMENT_SIZE
                    + " bytes, got " + segmentSizeBytes);
        }
        if (segmentSizeBytes > Integer.MAX_VALUE)
        {
            // Downstream readers (the tailer and the compressor) address segments with
            // int offsets, so refuse here rather than fail obscurely much later.
            throw new IllegalArgumentException("segmentSizeBytes must not exceed " + Integer.MAX_VALUE
                    + " bytes, got " + segmentSizeBytes);
        }

        this.tempDir = tempDir;
        this.shardId = shardId;
        this.segmentSizeBytes = segmentSizeBytes;
        this.sequenceMarkerPath = tempDir == null ? null : tempDir.resolve("shard-" + shardId + SEQUENCE_MARKER_EXTENSION);
        // Whichever is higher: the marker can lag if a write of it failed, and the segments
        // can outlive a marker that was lost. Neither source may lower the counter.
        this.segmentSequence = new AtomicLong(Math.max(
                highestExistingSequence(tempDir, shardId),
                readPersistedSequence()));

        this.warmerThread = new Thread(this::warmupLoop, "r7-warmer-shard-" + shardId);
        this.preFault = preFault;
        this.warmerThread.setDaemon(true);
        this.warmerThread.setPriority(Thread.MIN_PRIORITY);
        this.warmerThread.start();
    }

    private void warmupLoop()
    {
        while (running)
        {
            WarmedSegment warmed = null;
            try
            {
                warmed = createSegment();
                pool.put(warmed);
                warmed = null; // ownership transferred to the taker
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (IOException | UncheckedIOException e)
            {
                log.error("Failed to warm up next segment", e);
                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException ignored)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            finally
            {
                // Either put() threw or we are shutting down: release the mapping and the
                // file rather than leaving an orphan behind.
                discard(warmed);
            }
        }

        // Drain anything a taker never collected.
        discard(pool.poll());
    }

    /**
     * Highest segment sequence already present for this shard, or 0 when the directory is
     * empty or unreadable. Names are {@code shard-<id>-<createdMs>-<sequence>} with an
     * optional {@code -<firstTs>-<lastTs>} once sealed, and any extension.
     */
    private static long highestExistingSequence(final Path dir, final int shardId)
    {
        if (dir == null || !Files.isDirectory(dir))
        {
            return 0L;
        }

        final String prefix = "shard-" + shardId + "-";
        try (Stream<Path> files = Files.list(dir))
        {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(prefix))
                    .mapToLong(R7fJournalProvider::sequenceOf)
                    .max()
                    .orElse(0L);
        }
        catch (final IOException e)
        {
            // Starting from zero could reuse a retained segment's (shard, sequence) key,
            // and the tailer deduplicates on that key — one of the two segments would
            // simply never be read. For an audit log, refusing to start is the right
            // failure: there is no safe sequence to continue from.
            throw new UncheckedIOException(
                    "Cannot determine the highest existing segment sequence in " + dir
                            + "; refusing to start rather than risk reusing a segment key", e);
        }
    }

    /**
     * The persisted high-water mark, or 0 when there is none or it cannot be read. Zero is
     * safe here only because the caller takes the maximum of this and the sequences found
     * on disk.
     */
    private long readPersistedSequence()
    {
        if (sequenceMarkerPath == null || !Files.isRegularFile(sequenceMarkerPath))
        {
            return 0L;
        }

        try
        {
            return Long.parseLong(Files.readString(sequenceMarkerPath).trim());
        }
        catch (final IOException | NumberFormatException e)
        {
            throw new IllegalStateException("Cannot read segment sequence marker " + sequenceMarkerPath
                    + "; refusing to start rather than risk reusing a segment key", e);
        }
    }

    /**
     * Records the high-water mark, before the segment that claims it is created. A crash
     * between the two leaves the counter ahead of the segments on disk, which costs a
     * skipped sequence number and nothing else; the opposite order could hand the same key
     * to two different segments.
     */
    private void persistSequence(final long sequence)
    {
        if (sequenceMarkerPath == null)
        {
            return;
        }

        final Path tmp = sequenceMarkerPath.resolveSibling(sequenceMarkerPath.getFileName() + ".tmp");
        try
        {
            // Contents first, and forced before the rename. A rename is atomic, not durable,
            // and the two are independent: the classic delayed-allocation failure leaves the
            // new name in place over an empty file. That one at least fails closed —
            // readPersistedSequence refuses to start on an unparsable marker — but there is
            // no reason to rely on it.
            try (final FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
            {
                channel.write(ByteBuffer.wrap(Long.toString(sequence).getBytes(StandardCharsets.US_ASCII)));
                channel.force(true);
            }

            Files.move(tmp, sequenceMarkerPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            // Then the directory entry the rename created. Without this the marker can roll
            // back to its previous value after a power loss — the failure that actually
            // matters here, because it is the one that reuses a key rather than skipping one.
            forceDirectory(sequenceMarkerPath.getParent());
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("Cannot persist segment sequence marker " + sequenceMarkerPath
                    + "; refusing to create a segment with an unrecorded key", e);
        }
    }

    /**
     * Makes a directory's own contents durable, so that a rename into it survives a power
     * loss.
     * <p>
     * Opening a directory as a channel is a POSIX affordance and is rejected on Windows. A
     * failure is logged rather than thrown: the file's own contents are already forced by
     * the caller, and refusing to start a gateway because a development machine cannot sync
     * a directory would trade a narrow durability gap for a total outage.
     */
    private static void forceDirectory(final Path directory)
    {
        if (directory == null)
        {
            return;
        }
        try (final FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ))
        {
            channel.force(true);
        }
        catch (final IOException | UnsupportedOperationException e)
        {
            log.warn("Could not fsync the journal directory {}; the segment sequence marker is written but "
                    + "its directory entry may not survive a power loss on this platform: {}", directory, e.toString());
        }
    }

    private static long sequenceOf(final String fileName)
    {
        final String[] parts = fileName.split("-");
        if (parts.length < 4)
        {
            return 0L;
        }
        final String field = parts[3].split("\\.")[0];
        try
        {
            return Long.parseLong(field);
        }
        catch (final NumberFormatException e)
        {
            return 0L;
        }
    }

    private WarmedSegment createSegment() throws IOException
    {
        final long sequence = segmentSequence.incrementAndGet();
        persistSequence(sequence);
        final String name = String.format("shard-%d-%d-%d%s",
                shardId, System.currentTimeMillis(), sequence, R7fConstants.ACTIVE_FILE_EXTENSION
        );
        final Path nextPath = tempDir.resolve(name);

        try (FileChannel channel = FileChannel.open(nextPath, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE))
        {
            // A shared arena: created by the warmer thread, written to by the gateway
            // threads, and closed by whichever thread retires the segment.
            final Arena arena = Arena.ofShared();
            final MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, segmentSizeBytes, arena);

            if (preFault)
            {
                try
                {
                    segment.fill((byte) 0);
                }
                catch (InternalError e)
                {
                    arena.close();
                    Files.deleteIfExists(nextPath);
                    throw new UncheckedIOException(new IOException("Unable to pre-fault segment. Is there enough disk space?", e));
                }
            }

            // The channel closes here, but the Arena keeps the Segment alive
            log.debug("Warmed up and queued segment: {}", nextPath.getFileName());
            return new WarmedSegment(nextPath, segment, arena, sequence);
        }
    }

    private void discard(final WarmedSegment warmed)
    {
        if (warmed == null)
        {
            return;
        }
        try
        {
            warmed.arena().close();
        }
        catch (final RuntimeException e)
        {
            // The mapping is still live — a shared arena refuses to close while its segment
            // is in use. Unlinking the file now would leave the writer appending into an
            // inode with no name: the entries go nowhere recoverable, rotation later fails
            // to find the path, and there is no .corrupt file and no integrity event to say
            // so. Leaving the file is recoverable at the next startup; deleting it is not.
            log.warn("Unable to release warmed segment {}; leaving the file in place for recovery",
                    warmed.path().getFileName(), e);
            return;
        }
        try
        {
            Files.deleteIfExists(warmed.path());
        }
        catch (final IOException e)
        {
            log.warn("Unable to delete unused warmed segment {}", warmed.path().getFileName(), e);
        }
    }

    public WarmedSegment getNextSegment()
    {
        try
        {
            final WarmedSegment warmedSegment = pool.take();
            log.debug("Fetched segment {} of size {}", warmedSegment.path().getFileName(), DiskSpaceUtils.formatBytes(warmedSegment.segment().byteSize()));
            return warmedSegment;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for warmed segment", e);
        }
    }

    public long getSegmentSizeBytes()
    {
        return segmentSizeBytes;
    }

    @Override
    public void close()
    {
        running = false;
        warmerThread.interrupt();
        try
        {
            // Give the warmer a moment to release a segment it is holding, so a clean
            // shutdown does not leave a pre-allocated orphan behind.
            warmerThread.join(5_000L);
            if (warmerThread.isAlive())
            {
                log.warn("Warmer thread for shard {} did not stop in time", shardId);
            }
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        // Belt and braces: if the warmer was blocked in put() and handed the segment over
        // before noticing the interrupt, collect it here.
        discard(pool.poll());
    }

    // We must pass the Arena along with the Segment so the hot path can close it on rollover
    public record WarmedSegment(Path path, MemorySegment segment, Arena arena, long segmentSequence)
    {
    }
}
