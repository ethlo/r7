package com.ethlo.r7.r7f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class R7fJournalProvider implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(R7fJournalProvider.class);

    /**
     * A segment has to hold the preamble plus at least one entry to be of any use.
     */
    private static final long MIN_SEGMENT_SIZE = 64L * 1024L;

    private final Path tempDir;
    private final int shardId;
    private final long segmentSizeBytes;

    /**
     * Monotonic per-shard segment counter, written into each segment's preamble.
     */
    private final AtomicLong segmentSequence = new AtomicLong(0);

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

    private WarmedSegment createSegment() throws IOException
    {
        final long sequence = segmentSequence.incrementAndGet();
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
            log.warn("Unable to release warmed segment {}", warmed.path().getFileName(), e);
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
