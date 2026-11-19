package com.ethlo.r7.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * With tailers listed, a sealed segment is reaped before its TTL once it is min_age old and
 * every tailer's checkpoint file says it is done with it, and never on less than that.
 */
class ReaperTailerProgressTest
{
    private static final Duration TTL = Duration.ofDays(7);
    private static final Duration MIN_AGE = Duration.ofMinutes(10);

    @TempDir
    Path dir;

    @Test
    void aSegmentEveryTailerFinishedIsReapedEarly() throws IOException
    {
        final Path journals = Files.createDirectory(dir.resolve("journals"));
        final Path json = checkpoints("json", "journal-0-1=-1:5\njournal-0-2=-1:9\njournal-0-3=4096:12\n");
        final Path warc = checkpoints("warc", "journal-0-1=-1:5\njournal-0-2=8192:7\n");
        final Path done = segment(journals, 0, 1, Duration.ofHours(1));
        final Path oneTailerBehind = segment(journals, 0, 2, Duration.ofHours(1));
        final Path bothReading = segment(journals, 0, 3, Duration.ofHours(1));

        final int deleted = new JournalReaper(journals, TTL, List.of(json, warc), MIN_AGE).sweep();

        assertThat(deleted).isEqualTo(1);
        assertThat(done).doesNotExist();
        assertThat(oneTailerBehind).exists();
        assertThat(bothReading).exists();
    }

    @Test
    void aSegmentYoungerThanMinAgeIsKeptEvenWhenFinished() throws IOException
    {
        final Path journals = Files.createDirectory(dir.resolve("journals"));
        final Path json = checkpoints("json", "journal-0-1=-1:5\n");
        final Path fresh = segment(journals, 0, 1, Duration.ofMinutes(1));

        new JournalReaper(journals, TTL, List.of(json), MIN_AGE).sweep();

        assertThat(fresh).exists();
    }

    /**
     * A tailer that has the start of an open exchange in a segment needs it to rebuild that
     * exchange after a restart, finished or not.
     */
    @Test
    void aSegmentHoldingTheStartOfAnOpenExchangeIsKept() throws IOException
    {
        final Path journals = Files.createDirectory(dir.resolve("journals"));
        final Path json = checkpoints("json", "journal-0-1=-1:5\njournal-0-2=-1:9\nopen.0=2\\:2048\\:7\nopen.0.0=req-x\n");
        final Path before = segment(journals, 0, 1, Duration.ofHours(1));
        final Path holdingTheStart = segment(journals, 0, 2, Duration.ofHours(1));

        new JournalReaper(journals, TTL, List.of(json), MIN_AGE).sweep();

        assertThat(before).doesNotExist();
        assertThat(holdingTheStart).exists();
    }

    /**
     * A listed tailer with no checkpoint file proves nothing: it may be down, or new. Only the
     * TTL applies until it is back.
     */
    @Test
    void aListedTailerWithoutACheckpointFileStopsEarlyReaping() throws IOException
    {
        final Path journals = Files.createDirectory(dir.resolve("journals"));
        final Path json = checkpoints("json", "journal-0-1=-1:5\n");
        final Path absent = dir.resolve("warc");
        final Path finishedByOne = segment(journals, 0, 1, Duration.ofHours(1));
        final Path expired = segment(journals, 0, 0, Duration.ofDays(8));

        final int deleted = new JournalReaper(journals, TTL, List.of(json, absent), MIN_AGE).sweep();

        assertThat(deleted).isEqualTo(1);
        assertThat(finishedByOne).exists();
        assertThat(expired).doesNotExist();
    }

    @Test
    void aSegmentATailerHeldBackOrQuarantinedIsKept() throws IOException
    {
        final Path journals = Files.createDirectory(dir.resolve("journals"));
        final Path json = checkpoints("json", "journal-0-1=-2:5\njournal-0-2=-3:-1\n");
        final Path heldBack = segment(journals, 0, 1, Duration.ofHours(1));
        final Path quarantined = segment(journals, 0, 2, Duration.ofHours(1));

        assertThat(new JournalReaper(journals, TTL, List.of(json), MIN_AGE).sweep()).isZero();
        assertThat(heldBack).exists();
        assertThat(quarantined).exists();
    }

    private Path checkpoints(final String tailer, final String content) throws IOException
    {
        final Path checkpointDir = Files.createDirectories(dir.resolve(tailer));
        Files.writeString(checkpointDir.resolve(".r7_checkpoints"), content);
        return checkpointDir;
    }

    private static Path segment(final Path journals, final int shard, final long sequence, final Duration age) throws IOException
    {
        final long at = System.currentTimeMillis() - age.toMillis();
        return Files.createFile(journals.resolve("shard-" + shard + "-" + at + "-" + sequence + "-" + at + "-" + at + ".r7f"));
    }
}
