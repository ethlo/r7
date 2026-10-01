package com.ethlo.r7.reaper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * and quarantined segments ({@code .r7f.corrupt} / {@code .flux.corrupt}, optionally with a
 * further {@code .<n>} suffix - see {@link #QUARANTINE_SUFFIX}). An active segment
 * ({@code .flux}) is never touched, regardless of age - it belongs exclusively to the
 * gateway process still writing it, and this reaper has no way to know whether an old
 * {@code .flux} file is still being written to (a low-traffic shard) or abandoned (a crashed
 * writer); either way, deleting or renaming it out from under a writer that might resume is
 * not this component's call to make.
 * <p>
 * Age is measured from the timestamp embedded in the segment's own filename - the last event
 * epoch millis a sealed segment records, or its created-at millis otherwise - not the
 * filesystem's last-modified time. A file's mtime is not the segment's age, it is an
 * incidental fact about whatever last touched the file on this particular filesystem: a
 * backup restore, an `rsync` run without `-a`, a volume migration, or a bind mount recreated
 * by a container runtime all reset it, silently extending or shortening retention regardless
 * of how old the traffic inside the segment actually is. The filename is written once by the
 * gateway that created the segment and never touched again, so it survives every one of
 * those operations unchanged.
 */
public final class JournalReaper
{
    private static final Logger logger = LoggerFactory.getLogger(JournalReaper.class);

    /**
     * Matches the {@code .corrupt} suffix {@code R7fRecoveryManager.nonCollidingQuarantinePath} appends,
     * including the {@code .corrupt.<n>} shape it falls back to when an earlier quarantine of
     * the same name already exists (recovery partially succeeding twice, or an operator
     * restoring an old copy). A plain {@code name.endsWith(".corrupt")} check misses that
     * numbered form entirely, which would otherwise leave those files permanently unreapable
     * - exactly the "grows without bound" failure this reaper exists to prevent.
     */
    private static final Pattern QUARANTINE_SUFFIX =
            Pattern.compile(Pattern.quote(R7fFileNaming.CORRUPT_FILE_EXTENSION) + "(\\.\\d+)?$");

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
        // A quarantined active segment (name ending ".flux.corrupt"[.<n>]) is also fair
        // game: it was already set aside as unreadable by a tailer, so nothing is ever
        // going to read it, sealed or not.
        return name.endsWith(R7fFileNaming.SEALED_FILE_EXTENSION) || QUARANTINE_SUFFIX.matcher(name).find();
    }

    private boolean isExpired(final Path path, final Instant cutoff)
    {
        final Instant timestamp = segmentTimestamp(path);
        return timestamp != null && timestamp.isBefore(cutoff);
    }

    /**
     * Segments are named {@code shard-<shardId>-<createdEpochMillis>-<segmentSequence>} while
     * active, with {@code -<firstEventEpochMillis>-<lastEventEpochMillis>} appended once
     * sealed (see {@code R7fJournalProvider}); a quarantined segment carries a
     * {@link R7fFileNaming#CORRUPT_FILE_EXTENSION} suffix on top of either shape. This uses
     * the last field present - the last event the segment actually recorded, falling back to
     * when it was created - as the one piece of information about a segment's age that
     * cannot have been reset by anything other than the writer.
     * <p>
     * There is deliberately no filesystem-mtime fallback: {@link #isReapable} already
     * restricts candidates to the two recognized name shapes, and a name that still fails to
     * parse is one this reaper does not understand well enough to age at all - guessing from
     * mtime would silently reintroduce the exact unreliability the filename was chosen to
     * avoid. Such a file is simply left alone, logged once, and never becomes a deletion
     * candidate until whatever produced it is fixed.
     *
     * @return the segment's age anchor, or {@code null} if the name could not be parsed
     */
    private Instant segmentTimestamp(final Path path)
    {
        String name = path.getFileName().toString();
        final Matcher quarantine = QUARANTINE_SUFFIX.matcher(name);
        if (quarantine.find())
        {
            name = name.substring(0, quarantine.start());
        }
        name = name.replace(R7fFileNaming.ACTIVE_FILE_EXTENSION, "").replace(R7fFileNaming.SEALED_FILE_EXTENSION, "");

        final String[] parts = name.split("-");
        try
        {
            if (parts.length > 5)
            {
                return Instant.ofEpochMilli(Long.parseLong(parts[5])); // lastEventEpochMillis
            }
            if (parts.length > 2)
            {
                return Instant.ofEpochMilli(Long.parseLong(parts[2])); // createdEpochMillis
            }
        }
        catch (final NumberFormatException e)
        {
            // Fall through - logged below, treated as unparsable either way.
        }

        logger.warn("Could not determine an age for {} from its filename; leaving it alone", path.getFileName());
        return null;
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
