package com.ethlo.r7.r7f;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultAheadTest
{
    @TempDir
    Path dir;

    @Test
    void preFaultedSegmentsNeedNoFaultAhead()
    {
        assertThat(FaultAhead.create(true)).isNull();
    }

    /**
     * The property the whole design rests on: populating a range never changes what is in it.
     * The writer and the fault-ahead thread work on the same segment without a lock between
     * them, and a writer that has overtaken the window has already put committed entries, magic
     * and all, where a populate is still to run.
     */
    @Test
    void populatingNeverChangesWhatTheWriterHasWritten() throws IOException
    {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"));
        final FaultAhead faultAhead = FaultAhead.create(false);
        Assumptions.assumeTrue(faultAhead != null, "MADV_POPULATE_WRITE is not available on this kernel");

        final long size = 4 * FaultAhead.LOOKAHEAD;
        try (FileChannel channel = FileChannel.open(dir.resolve("segment"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             Arena arena = Arena.ofShared();
             faultAhead)
        {
            final MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);

            // Written before the window ever reaches these pages, as a writer that has run ahead
            for (long offset = 0; offset < size; offset += 4096)
            {
                segment.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, ~offset);
            }

            for (long position = 0; position < size; position += FaultAhead.CHUNK)
            {
                faultAhead.advance(segment, position);
            }
            faultAhead.drain();

            for (long offset = 0; offset < size; offset += 4096)
            {
                assertThat(segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)).isEqualTo(~offset);
            }
            // Everything past the written longs is still the zero that means "no entry here"
            assertThat(segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 8)).isZero();
        }
    }

    /**
     * A segment retired while its chunks are still queued: draining before the arena closes is
     * what lets the close succeed, as a shared arena refuses to close under a native call.
     */
    @Test
    void aSegmentClosesOnceItsChunksAreDrained() throws IOException
    {
        final FaultAhead faultAhead = FaultAhead.create(false);
        Assumptions.assumeTrue(faultAhead != null, "MADV_POPULATE_WRITE is not available here");

        try (FileChannel channel = FileChannel.open(dir.resolve("segment"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             faultAhead)
        {
            for (int round = 0; round < 20; round++)
            {
                final Arena arena = Arena.ofShared();
                final MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, 4 * FaultAhead.LOOKAHEAD, arena);
                faultAhead.reset(segment, 0);
                faultAhead.drain();
                arena.close();
            }
        }
    }
}
