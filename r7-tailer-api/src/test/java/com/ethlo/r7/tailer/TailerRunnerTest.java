package com.ethlo.r7.tailer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class TailerRunnerTest
{
    private static final Path JOURNALS = Path.of("journals");

    /**
     * A listener refusing an exchange surfaces as an unchecked exception from the read. The loop
     * survives it (the next read is the retry), and the output's after-read hook still runs, so
     * records already written are sealed on time.
     */
    @Test
    void aFailedReadNeitherEndsTheLoopNorSkipsTheAfterReadHook()
    {
        final AtomicInteger afterTicks = new AtomicInteger();
        final TailerOutput output = new TailerOutput()
        {
            @Override
            public void afterTick()
            {
                afterTicks.incrementAndGet();
            }

            @Override
            public void close()
            {
            }
        };

        TailerRunner.tick(() ->
        {
            throw new UncheckedIOException(new IOException("No space left on device"));
        }, output, JOURNALS);
        TailerRunner.tick(() ->
        {
            throw new IOException("Input/output error");
        }, output, JOURNALS);

        assertThat(afterTicks).hasValue(2);
    }

    @Test
    void aFailedAfterReadHookDoesNotEndTheLoop()
    {
        final AtomicInteger reads = new AtomicInteger();
        final TailerOutput failing = new TailerOutput()
        {
            @Override
            public void afterTick() throws IOException
            {
                throw new IOException("Read-only file system");
            }

            @Override
            public void close()
            {
            }
        };

        TailerRunner.tick(reads::incrementAndGet, failing, JOURNALS);
        TailerRunner.tick(reads::incrementAndGet, failing, JOURNALS);

        assertThat(reads).hasValue(2);
    }
}
