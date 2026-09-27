package com.ethlo.r7.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        final Path oldSealed = touch("shard-0-1-1.r7f", Duration.ofDays(10));
        final Path oldQuarantined = touch("shard-0-1-2.r7f.corrupt", Duration.ofDays(10));
        final Path freshSealed = touch("shard-0-1-3.r7f", Duration.ofMinutes(1));
        final Path oldActive = touch("shard-0-1-4.flux", Duration.ofDays(10));
        final Path unrelatedOldFile = touch(".r7_checkpoints", Duration.ofDays(10));

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
    void toleratesMissingJournalDirectory() throws IOException
    {
        final JournalReaper reaper = new JournalReaper(journalDir.resolve("does-not-exist"), Duration.ofDays(1));
        assertThat(reaper.sweep()).isZero();
    }

    private Path touch(final String name, final Duration age) throws IOException
    {
        final Path path = journalDir.resolve(name);
        Files.createFile(path);
        Files.getFileAttributeView(path, java.nio.file.attribute.BasicFileAttributeView.class)
                .setTimes(FileTime.from(Instant.now().minus(age)), null, null);
        return path;
    }
}
