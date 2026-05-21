package com.ethlo.r7.r7f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class R7fJournalProvider implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(R7fJournalProvider.class);

    private final Path tempDir;
    private final int shardId;
    private final long segmentSizeBytes;
    private final AtomicInteger rotationCount = new AtomicInteger(0);

    // Queue 1 extra mapped file ready
    private final BlockingQueue<WarmedSegment> pool = new SynchronousQueue<>();
    private final Thread warmerThread;
    private final boolean preFault;
    private volatile boolean running = true;

    public R7fJournalProvider(Path tempDir, int shardId, long segmentSizeBytes, boolean preFault)
    {
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
            try
            {
                String name = String.format("shard-%d-%d-%d" + R7fConstants.ACTIVE_FILE_EXTENSION,
                        shardId, System.currentTimeMillis(), rotationCount.incrementAndGet()
                );
                Path nextPath = tempDir.resolve(name);

                try (FileChannel channel = FileChannel.open(nextPath, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE))
                {
                    // 1. Create a Shared Arena. It is created by the warmer thread,
                    // but will be written to and eventually closed by the Undertow/Gateway threads.
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
                            throw new UncheckedIOException(new IOException("Unable to pre-fault segment. is there enough disk space?"));
                        }
                    }

                    // The channel closes here, but the Arena keeps the Segment alive
                    pool.put(new WarmedSegment(nextPath, segment, arena));
                    log.debug("Warmed up and queue segment: {}", nextPath.getFileName());
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (IOException e)
            {
                log.error("Failed to warm up next segment", e);
                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException ignored)
                {
                }
            }
        }
    }

    public WarmedSegment getNextSegment()
    {
        try
        {
            final WarmedSegment warmedSegment = pool.take();
            log.debug("Fetched segment {} of size {}", warmedSegment.path().getFileName(), DiskSpaceUtils.formatBytes(warmedSegment.segment.byteSize()));
            return warmedSegment;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for warmed segment", e);
        }
    }

    @Override
    public void close()
    {
        running = false;
        warmerThread.interrupt();
    }

    // We must pass the Arena along with the Segment so the hot path can close it on rollover
    public record WarmedSegment(Path path, MemorySegment segment, Arena arena)
    {
    }
}