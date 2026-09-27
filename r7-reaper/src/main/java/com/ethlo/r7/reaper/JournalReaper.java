package com.ethlo.r7.reaper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.journal.api.R7fFileNaming;

/**
 * The dumbest retention policy that can work: delete sealed segments once they are older
 * than {@code ttl}, full stop. No coordination with any tailer's checkpoint, no attempt to
 * confirm anything was actually read - see {@link ReaperConfig}'s javadoc for what that
 * trades away and what the operator has to guarantee instead.
 * <p>
 * Only two kinds of file are ever candidates for deletion: sealed segments ({@code .r7f})
 * and quarantined segments ({@code .r7f.corrupt} / {@code .flux.corrupt}). An active segment
 * ({@code .flux}) is never touched, regardless of age - it belongs exclusively to the
 * gateway process still writing it, and this reaper has no way to know whether an old
 * {@code .flux} file is still being written to (a low-traffic shard) or abandoned (a crashed
 * writer); either way, deleting or renaming it out from under a writer that might resume is
 * not this component's call to make.
 */
public final class JournalReaper
{
    private static final Logger logger = LoggerFactory.getLogger(JournalReaper.class);

    private final Path journalDir;
    private final Duration ttl;

    public JournalReaper(final Path journalDir, final Duration ttl)
    {
        this.journalDir = journalDir;
        this.ttl = ttl;
    }

    /**
     * Scans {@code journalDir} once and deletes every reapable segment older than {@code
     * ttl}. Never recurses - segments live flat in this directory, see
     * {@code R7fJournalProvider}'s naming scheme.
     *
     * @return the number of segments deleted
     */
    public int sweep() throws IOException
    {
        if (!Files.isDirectory(journalDir))
        {
            logger.warn("Journal directory {} does not exist (yet); nothing to reap", journalDir);
            return 0;
        }

        final Instant cutoff = Instant.now().minus(ttl);
        int deleted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(journalDir, this::isReapable))
        {
            for (final Path path : stream)
            {
                if (isExpired(path, cutoff))
                {
                    deleteQuietly(path);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private boolean isReapable(final Path path)
    {
        final String name = path.getFileName().toString();
        if (!name.startsWith(R7fFileNaming.SHARD_FILE_PREFIX))
        {
            return false;
        }
        // A quarantined active segment (name ending ".flux.corrupt") is also fair game: it
        // was already set aside as unreadable by a tailer, so nothing is ever going to read
        // it, sealed or not.
        return name.endsWith(R7fFileNaming.SEALED_FILE_EXTENSION) || name.endsWith(R7fFileNaming.CORRUPT_FILE_EXTENSION);
    }

    private boolean isExpired(final Path path, final Instant cutoff)
    {
        try
        {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        }
        catch (final IOException e)
        {
            // Gone already (raced with something else removing it, e.g. an operator, or
            // another reaper instance) - not expired, simply no longer a candidate.
            logger.debug("Could not read last-modified time of {}, skipping this sweep", path, e);
            return false;
        }
    }

    private void deleteQuietly(final Path path)
    {
        try
        {
            Files.delete(path);
            logger.info("Reaped {} (older than {})", path.getFileName(), ttl);
        }
        catch (final FileSystemException e)
        {
            // Most likely a concurrent tailer or another process still holding the file
            // open/mapped on a platform where that blocks deletion. Leave it for the next
            // sweep rather than treating this as fatal.
            logger.warn("Could not reap {} this sweep, will retry: {}", path.getFileName(), e.getMessage());
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("Failed to reap " + path, e);
        }
    }
}
