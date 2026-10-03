package com.ethlo.r7.tailer.jsonld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollingFileOutputStreamTest
{
    private static final long MIN = RollingFileOutputStream.MIN_ROLLOVER_SIZE;
    private static final long HOUR = 3_600_000L;

    @TempDir
    Path dir;

    /**
     * A file rolls once it reaches the size limit, and only between records: the JSON writer
     * flushes after each one, and that is the only place rolling happens.
     */
    @Test
    void rollsOnSizeBetweenRecordsAndNeverInsideOne() throws IOException
    {
        final String record = "{\"x\":\"" + "a".repeat(40_000) + "\"}\n";
        try (RollingFileOutputStream out = new RollingFileOutputStream(dir, "r7", MIN, HOUR))
        {
            for (int i = 0; i < 4; i++)
            {
                // Written in two pieces, as the writer does (record, then newline).
                out.write(record.substring(0, 100).getBytes(StandardCharsets.UTF_8));
                out.write(record.substring(100).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        final List<Path> sealed = files(".jsonl");
        assertThat(sealed).as("two records reach the limit, so two files").hasSize(2);
        assertThat(files(".open")).isEmpty();
        for (final Path file : sealed)
        {
            assertThat(Files.readAllLines(file)).hasSize(2).allMatch(line -> line.endsWith("\"}"));
        }
    }

    @Test
    void anEmptyFileIsNotLeftBehind() throws IOException
    {
        new RollingFileOutputStream(dir, "r7", MIN, HOUR).close();
        assertThat(files("")).isEmpty();
    }

    @Test
    void aQuietStreamStillRollsOnAge() throws IOException
    {
        try (RollingFileOutputStream out = new RollingFileOutputStream(dir, "r7", MIN, 1))
        {
            out.write("{}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertThat(files(".jsonl")).isEmpty();
            sleepPastAgeLimit();
            out.rollIfStale();
            assertThat(files(".jsonl")).hasSize(1);
            // And nothing to roll when nothing was written since.
            out.rollIfStale();
            assertThat(files("")).hasSize(1);
        }
    }

    /**
     * One read of a backlog can run longer than the age limit, so age is checked before each
     * record too, not only between reads.
     */
    @Test
    void aBusyStreamRollsOnAgeBetweenRecords() throws IOException
    {
        try (RollingFileOutputStream out = new RollingFileOutputStream(dir, "r7", MIN, 1))
        {
            out.write("{\"a\":1}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            sleepPastAgeLimit();
            out.write("{\"b\":2}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertThat(files(".jsonl")).as("the first file sealed before the second record").hasSize(1);
        }
        assertThat(files(".jsonl")).hasSize(2);
    }

    /**
     * A crash leaves the current file under its .open name. Its complete lines were checkpointed
     * past, so they must reach a consumer: the file is sealed on start, minus a torn last line,
     * whose record the tailer had not checkpointed and writes again.
     */
    @Test
    void aFileLeftOpenByACrashIsSealedWithoutItsTornLastLine() throws IOException
    {
        final Path leftover = dir.resolve("r7-1-abc.jsonl.open");
        Files.writeString(leftover, "{\"a\":1}\n{\"b\":2}\n{\"c\":");
        final Path emptyLeftover = dir.resolve("r7-2-def.jsonl.open");
        Files.writeString(emptyLeftover, "{\"torn\":");
        final Path someoneElses = dir.resolve("other-3-ghi.jsonl.open");
        Files.writeString(someoneElses, "x");

        new RollingFileOutputStream(dir, "r7", MIN, HOUR).close();

        assertThat(Files.readString(dir.resolve("r7-1-abc.jsonl"))).isEqualTo("{\"a\":1}\n{\"b\":2}\n");
        assertThat(Files.exists(leftover)).isFalse();
        assertThat(Files.exists(emptyLeftover)).as("nothing complete in it").isFalse();
        assertThat(Files.exists(dir.resolve("r7-2-def.jsonl"))).isFalse();
        assertThat(Files.exists(someoneElses)).as("another prefix is not this writer's").isTrue();
    }

    @Test
    void aRolloverSizeThatCannotHoldARecordIsRefused()
    {
        assertThatThrownBy(() -> new RollingFileOutputStream(dir, "r7", MIN - 1, HOUR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<Path> files(final String suffix) throws IOException
    {
        try (Stream<Path> s = Files.list(dir))
        {
            return new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList());
        }
    }

    private static void sleepPastAgeLimit()
    {
        try
        {
            Thread.sleep(5);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
