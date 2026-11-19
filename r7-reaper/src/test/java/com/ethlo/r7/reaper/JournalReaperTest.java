package com.ethlo.r7.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalReaperTest
{
    @TempDir
    Path journalDir;

    @Test
    void reapsOnlyExpiredSealedAndQuarantinedSegments() throws IOException
    {
        final long tenDaysAgo = ageMillis(Duration.ofDays(10));
        final long oneMinuteAgo = ageMillis(Duration.ofMinutes(1));

        final Path oldSealed = touch("shard-0-" + tenDaysAgo + "-1-" + tenDaysAgo + "-" + tenDaysAgo + ".r7f");
        final Path oldQuarantined = touch("shard-0-" + tenDaysAgo + "-2-" + tenDaysAgo + "-" + tenDaysAgo + ".r7f.corrupt");
        final Path freshSealed = touch("shard-0-" + oneMinuteAgo + "-3-" + oneMinuteAgo + "-" + oneMinuteAgo + ".r7f");
        final Path oldActive = touch("shard-0-" + tenDaysAgo + "-4.flux");
        final Path unrelatedOldFile = touch(".r7_checkpoints");

        final JournalReaper reaper = new JournalReaper(journalDir, Duration.ofDays(7));
        final int deleted = reaper.sweep();

        assertThat(deleted).isEqualTo(2);
        assertThat(oldSealed).doesNotExist();
        assertThat(oldQuarantined).doesNotExist();
        assertThat(freshSealed).exists();
        // Flux (active) segments are never reaped, no matter their age - they belong
        // exclusively to the gateway process that may still be writing to them.
        assertThat(oldActive).exists();
        assertThat(unrelatedOldFile).exists();
    }

    @Test
    void agesByFilenameTimestampRatherThanFilesystemMtime() throws IOException
    {
        // The filename says this segment's last event was ten days ago, but the file itself
        // was just (re)created on this filesystem - e.g. restored from a backup, or copied by
        // a volume migration that did not preserve mtime. It must still be reaped: reading
        // the name instead of mtime exists precisely so this is not missed.
        final long tenDaysAgo = ageMillis(Duration.ofDays(10));
        final Path restoredButOld = touchWithMtime(
                "shard-0-" + tenDaysAgo + "-1-" + tenDaysAgo + "-" + tenDaysAgo + ".r7f", Duration.ZERO);

        // The inverse: a fresh segment whose mtime some backup tool happened to set far in
        // the past must not be reaped early on that basis.
        final long justNow = ageMillis(Duration.ZERO);
        final Path freshButMtimeIsOld = touchWithMtime(
                "shard-0-" + justNow + "-2-" + justNow + "-" + justNow + ".r7f", Duration.ofDays(10));

        final JournalReaper reaper = new JournalReaper(journalDir, Duration.ofDays(7));
        reaper.sweep();

        assertThat(restoredButOld).doesNotExist();
        assertThat(freshButMtimeIsOld).exists();
    }

    @Test
    void leavesAnUnparsableNameAlone() throws IOException
    {
        // Not the recognized shape at all (no created-epoch field) - there is no age to
        // determine, and no mtime fallback to guess one from, so this must never be reaped,
        // no matter how old the file is on disk.
        final Path unparsable = touchWithMtime("shard-not-a-timestamp.r7f", Duration.ofDays(365));

        final JournalReaper reaper = new JournalReaper(journalDir, Duration.ofDays(7));
        reaper.sweep();

        assertThat(unparsable).exists();
    }

    @Test
    void reapsQuarantinedActiveSegmentByItsCreatedTimestamp() throws IOException
    {
        // A quarantined .flux has no sealed time-bound fields, only shardId/created/sequence
        // - segmentTimestamp must fall back to the created-at field for this shape.
        final long tenDaysAgo = ageMillis(Duration.ofDays(10));
        final Path oldQuarantinedActive = touch("shard-0-" + tenDaysAgo + "-1.flux.corrupt");

        final JournalReaper reaper = new JournalReaper(journalDir, Duration.ofDays(7));
        reaper.sweep();

        assertThat(oldQuarantinedActive).doesNotExist();
    }

    @Test
    void reapsRepeatedlyQuarantinedSegmentsWithANumericSuffix() throws IOException
    {
        // R7fRecoveryManager.nonCollidingQuarantinePath appends ".<n>" rather than overwrite an
        // existing quarantine of the same name (recovery partially succeeding twice, or an
        // operator restoring an old copy) - these numbered files must be just as reapable as
        // a plain ".corrupt" one, or they accumulate forever.
        final long tenDaysAgo = ageMillis(Duration.ofDays(10));
        final Path numberedSealedQuarantine = touch(
                "shard-0-" + tenDaysAgo + "-1-" + tenDaysAgo + "-" + tenDaysAgo + ".r7f.corrupt.2");
        final Path numberedActiveQuarantine = touch("shard-0-" + tenDaysAgo + "-2.flux.corrupt.7");

        final JournalReaper reaper = new JournalReaper(journalDir, Duration.ofDays(7));
        reaper.sweep();

        assertThat(numberedSealedQuarantine).doesNotExist();
        assertThat(numberedActiveQuarantine).doesNotExist();
    }

    @Test
    void toleratesMissingJournalDirectory() throws IOException
    {
        final JournalReaper reaper = new JournalReaper(journalDir.resolve("does-not-exist"), Duration.ofDays(1));
        assertThat(reaper.sweep()).isZero();
    }

    private static long ageMillis(final Duration age)
    {
        return System.currentTimeMillis() - age.toMillis();
    }

    private Path touch(final String name) throws IOException
    {
        return Files.createFile(journalDir.resolve(name));
    }

    private Path touchWithMtime(final String name, final Duration mtimeAge) throws IOException
    {
        final Path path = touch(name);
        Files.getFileAttributeView(path, BasicFileAttributeView.class)
                .setTimes(FileTime.from(Instant.now().minus(mtimeAge)), null, null);
        return path;
    }
}
