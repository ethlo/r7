package com.ethlo.venturi.vlf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AsyncSegmentProvider implements AutoCloseable
{
    // Standard OS page size is almost universally 4KB
    private static final long OS_PAGE_SIZE = 4096L;

    private final int segmentSize;
    // Assuming your Path/ID provider is thread-safe or only accessed by this background thread
    private final VlfJournalProvider provider;

    // Holds fully mapped and pre-faulted segments ready for instant use
    private final ArrayBlockingQueue<PreparedSegment> readySegments;
    private final ExecutorService ioExecutor;
    private volatile boolean running = true;

    public AsyncSegmentProvider(int segmentSize, VlfJournalProvider provider, int prefetchCount)
    {
        this.segmentSize = segmentSize;
        this.provider = provider;
        this.readySegments = new ArrayBlockingQueue<>(prefetchCount);

        // A single thread ensures disk I/O remains sequential and predictable
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vlf-async-io");
            t.setDaemon(true);
            return t;
        });

        // Start the infinite pre-allocation loop
        ioExecutor.execute(this::preAllocationLoop);
    }

    private void preAllocationLoop()
    {
        while (running)
        {
            try
            {
                // Create the next segment. This does the heavy disk I/O.
                PreparedSegment seg = createSegmentSync();

                // Blocks here until the worker thread consumes a segment
                readySegments.put(seg);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                // If disk fails, sleep briefly to avoid spinning CPU at 100%
                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException ie)
                {
                    break;
                }
            }
        }
    }

    private PreparedSegment createSegmentSync() throws IOException
    {
        Path path = provider.getNextPath();
        int fileId = provider.getRotationCount();

        try (FileChannel fc = FileChannel.open(path,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE
        ))
        {
            fc.truncate(segmentSize);
            Arena arena = Arena.ofShared();
            MemorySegment segment = fc.map(FileChannel.MapMode.READ_WRITE, 0, segmentSize, arena);

            try
            {
                preFaultSegment(segment); // Your existing pre-fault logic
            }
            catch (InternalError e)
            {
                arena.close();
                throw new IOException("Unable to allocate/fault journal segment " + path, e);
            }

            return new PreparedSegment(arena, segment, path, fileId);
        }
    }

    /**
     * Fast-path: Grab a prepared segment in nanoseconds, or fallback to sync if disk is behind.
     */
    public PreparedSegment getNextSegment()
    {
        PreparedSegment seg = readySegments.poll();
        if (seg != null)
        {
            return seg; // Nanosecond fast-path
        }

        // Warning: Disk I/O is slower than network throughput! Falling back to synchronous.
        try
        {
            return createSegmentSync();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Hand the old segment back to the background thread to take the flush/rename hit.
     */
    public void retireSegment(PreparedSegment oldSegment)
    {
        if (oldSegment == null) return;

        //ioExecutor.execute(() -> {
            try
            {
                oldSegment.segment().force(); // Heavy blocking I/O
                oldSegment.arena().close();

                String newName = oldSegment.activePath().getFileName()
                        .toString().replace(VlfConstants.ACTIVE_FILE_EXTENSION, VlfConstants.VLF_FILE_EXTENSION);

                Files.move(oldSegment.activePath(),
                        oldSegment.activePath().resolveSibling(newName),
                        StandardCopyOption.ATOMIC_MOVE
                );
            }
            catch (IOException e)
            {
                // Log failure to close or rename
            }
        //});
    }

    @Override
    public void close()
    {
        running = false;
        ioExecutor.shutdown();
    }

    /**
     * Forces the OS to physically allocate disk blocks for the entire mapped segment.
     * Prevents sparse file creation and runtime SIGBUS/InternalError crashes.
     */
    private void preFaultSegment(MemorySegment segment) throws InternalError
    {
        final long capacity = segment.byteSize();

        // Stride through the segment, touching one byte per 4KB page
        for (long offset = 0; offset < capacity; offset += OS_PAGE_SIZE)
        {
            segment.set(ValueLayout.JAVA_BYTE, offset, (byte) 0);
        }

        // Guarantee the absolute last byte is also physically backed
        // in case the segment size is not a perfect multiple of 4096
        if (capacity > 0)
        {
            segment.set(ValueLayout.JAVA_BYTE, capacity - 1, (byte) 0);
        }
    }

    public record PreparedSegment(Arena arena, MemorySegment segment, Path activePath, int fileId)
    {
    }
}