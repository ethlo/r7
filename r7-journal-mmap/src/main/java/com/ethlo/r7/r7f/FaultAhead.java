package com.ethlo.r7.r7f;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faults a journal segment's pages in a little ahead of its writer, on a thread of its own.
 * <p>
 * Segments are mapped lazily, so without this the first store to each page faults, and the
 * writer making that store holds the shard's monitor while the kernel allocates the page (and,
 * on a disk, while writeback throttles it). Every other writer to the shard waits behind each
 * fault. {@code pre_fault} avoids that by touching the whole segment up front, which charges
 * every warmed segment to memory at once (about 1.2 GB a shard at the default size), and under
 * a container's memory limit cost more than it saved. This keeps a window of
 * {@link #LOOKAHEAD} bytes populated instead. See {@code design/journal-write-contention.md}.
 * <p>
 * Pages are populated with {@code madvise(MADV_POPULATE_WRITE)} (Linux 5.14), which prepares
 * them for writing without writing to them. That is what makes it safe to run beside the
 * writer: the segment's contents are never touched, so the zero that means "no committed entry"
 * stays zero until the writer commits there, and a range the writer has already overtaken is
 * left exactly as it was. Writing a zero to touch a page would not be safe in that case.
 * <p>
 * An optimisation only. Where the call is unavailable (another OS, an older kernel, a native
 * image without the downcall registered) this does nothing, and if it falls behind or fails,
 * the writer faults as it always did.
 */
final class FaultAhead implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(FaultAhead.class);

    /**
     * How far ahead of the write position pages are kept populated. A few milliseconds of
     * journal at hundreds of MB/s, and the memory this costs a shard beyond what is written.
     */
    static final long LOOKAHEAD = 8L * 1024 * 1024;

    /**
     * Populated per call. Large enough that the call overhead does not matter, small enough
     * that one call finishes well before the writer reaches its range.
     */
    static final long CHUNK = 2L * 1024 * 1024;

    private static final int MADV_POPULATE_WRITE = 23;

    private static final MethodHandle MADVISE = linkMadvise();

    private final ExecutorService executor;
    private long populatedTo;
    private boolean failureLogged;

    private FaultAhead(final ExecutorService executor)
    {
        this.executor = executor;
    }

    /**
     * @return a fault-ahead for one journal, or {@code null} when the platform cannot do it or
     * the segments are pre-faulted already, and there is nothing to gain
     */
    static FaultAhead create(final boolean preFault)
    {
        if (preFault || MADVISE == null)
        {
            return null;
        }
        return new FaultAhead(Executors.newSingleThreadExecutor(runnable ->
        {
            final Thread thread = new Thread(runnable, "r7-fault-ahead");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /**
     * A new segment is active; start its window from the top. Called under the journal's
     * monitor.
     */
    void reset(final MemorySegment segment, final long position)
    {
        this.populatedTo = 0;
        advance(segment, position);
    }

    /**
     * Keeps {@link #LOOKAHEAD} bytes past {@code position} populated, handing each newly
     * claimed chunk to the fault-ahead thread. Called under the journal's monitor, which is
     * what makes {@link #populatedTo} safe without a lock of its own; all it costs there is a
     * compare, and now and then a queue offer.
     */
    void advance(final MemorySegment segment, final long position)
    {
        final long size = segment.byteSize();
        while (populatedTo < size && populatedTo < position + LOOKAHEAD)
        {
            final long from = populatedTo;
            final long length = Math.min(CHUNK, size - from);
            populatedTo = from + length;
            try
            {
                executor.execute(() -> populate(segment.asSlice(from, length)));
            }
            catch (final RejectedExecutionException closing)
            {
                return;
            }
        }
    }

    /**
     * Waits for every chunk handed over so far to finish. A segment's arena must not be
     * closed while a call populating it is running: a shared arena refuses to close while a
     * native call holds one of its segments. Chunks run in order on one thread, so once this
     * returns, nothing of an earlier segment is still being populated.
     */
    void drain()
    {
        try
        {
            final Future<?> fence = executor.submit(() -> {
            });
            fence.get(5, TimeUnit.SECONDS);
        }
        catch (final RejectedExecutionException closing)
        {
            // Already closed, and close() waits for the thread itself
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (final Exception e)
        {
            logger.warn("Fault-ahead did not finish within 5 s; closing the segment anyway", e);
        }
    }

    private void populate(final MemorySegment range)
    {
        try
        {
            final int result = (int) MADVISE.invokeExact(range, range.byteSize(), MADV_POPULATE_WRITE);
            if (result != 0 && !failureLogged)
            {
                // Out of disk is the likely one (the populate cannot allocate a block); the
                // writer meets the same condition when it gets there, and reports it.
                failureLogged = true;
                logger.debug("madvise(MADV_POPULATE_WRITE) failed; the writer will fault these pages itself");
            }
        }
        catch (final IllegalStateException alreadyClosed)
        {
            // The segment was retired before its chunk ran: nothing left to populate
        }
        catch (final Throwable e)
        {
            if (!failureLogged)
            {
                failureLogged = true;
                logger.debug("Fault-ahead failed; the writer will fault these pages itself", e);
            }
        }
    }

    @Override
    public void close()
    {
        executor.shutdownNow();
        try
        {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS))
            {
                logger.warn("Fault-ahead thread did not stop within 5 s");
            }
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Links {@code int madvise(void *addr, size_t length, int advice)} and checks, on a page
     * of its own, that the kernel knows {@code MADV_POPULATE_WRITE}: an older one answers
     * {@code EINVAL}, and every later call would fail the same way.
     */
    private static MethodHandle linkMadvise()
    {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"))
        {
            return null;
        }
        try
        {
            final Linker linker = Linker.nativeLinker();
            final MethodHandle madvise = linker.downcallHandle(
                    linker.defaultLookup().find("madvise").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            try (Arena arena = Arena.ofConfined())
            {
                final long page = 4096;
                final MemorySegment probe = arena.allocate(page, page);
                if ((int) madvise.invokeExact(probe, page, MADV_POPULATE_WRITE) != 0)
                {
                    logger.debug("madvise(MADV_POPULATE_WRITE) is not supported here; journal pages fault on first write");
                    return null;
                }
            }
            return madvise;
        }
        catch (final Throwable e)
        {
            logger.debug("madvise is not available; journal pages fault on first write", e);
            return null;
        }
    }
}
