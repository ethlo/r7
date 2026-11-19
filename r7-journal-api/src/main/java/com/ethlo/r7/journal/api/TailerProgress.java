package com.ethlo.r7.journal.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * What a tailer's checkpoint file says about how far it has got, read by a reaper that may
 * delete a segment early once every tailer is done with it.
 * <p>
 * The file is the tailer's own ({@code R7Tailer} writes it, in a directory the tailer owns), so
 * this is the contract between the two: {@code journal-<shard>-<sequence>} maps to
 * {@code <offset>:<next sequence>}, where an offset of {@link #FINISHED} means read to the end
 * and every exchange in it delivered; {@code open.<shard>} is {@code <segment>:<offset>:<sequence>},
 * where the oldest exchange still being assembled began, and the tailer needs every segment from
 * there on to rebuild it after a restart. Every other value means "not done".
 * <p>
 * Only "done" is ever concluded from this file, and only from what it says: a missing file, a
 * missing key, an unreadable value or any state other than {@link #FINISHED} is "not done".
 */
public final class TailerProgress
{
    public static final String FILE_NAME = ".r7_checkpoints";

    /** The offset recorded for a segment read to the end with everything in it delivered. */
    public static final long FINISHED = -1L;

    public static final String OPEN_PREFIX = "open.";

    private final Map<String, String> entries;
    private final Map<Integer, Long> oldestOpenSegment;

    private TailerProgress(final Map<String, String> entries, final Map<Integer, Long> oldestOpenSegment)
    {
        this.entries = entries;
        this.oldestOpenSegment = oldestOpenSegment;
    }

    public static String segmentKey(final int shardId, final long segmentSequence)
    {
        return "journal-" + shardId + "-" + segmentSequence;
    }

    /**
     * @param checkpointDir the tailer's checkpoint directory
     * @return its progress, or {@code null} if there is no checkpoint file or it cannot be read
     *         (which proves nothing, so a caller must treat it as "not done" for everything)
     */
    public static TailerProgress read(final Path checkpointDir)
    {
        final Properties props = new Properties();
        try (InputStream in = Files.newInputStream(checkpointDir.resolve(FILE_NAME)))
        {
            props.load(in);
        }
        catch (final NoSuchFileException e)
        {
            return null;
        }
        catch (final IOException | IllegalArgumentException e)
        {
            return null;
        }

        final Map<String, String> entries = new HashMap<>();
        final Map<Integer, Long> oldestOpen = new HashMap<>();
        for (final String key : props.stringPropertyNames())
        {
            final String value = props.getProperty(key);
            if (key.startsWith(OPEN_PREFIX))
            {
                final String rest = key.substring(OPEN_PREFIX.length());
                if (rest.indexOf('.') < 0)
                {
                    try
                    {
                        final int shard = Integer.parseInt(rest);
                        final int colon = value.indexOf(':');
                        oldestOpen.put(shard, Long.parseLong((colon < 0 ? value : value.substring(0, colon)).trim()));
                    }
                    catch (final NumberFormatException e)
                    {
                        // Unreadable: this shard's segments are not provably done.
                        oldestOpen.put(parseShardOrMin(rest), Long.MIN_VALUE);
                    }
                }
            }
            else
            {
                entries.put(key, value);
            }
        }
        return new TailerProgress(entries, oldestOpen);
    }

    private static int parseShardOrMin(final String text)
    {
        try
        {
            return Integer.parseInt(text);
        }
        catch (final NumberFormatException e)
        {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Whether this tailer has read the segment to the end, delivered everything in it, and does
     * not need it to rebuild an exchange still open.
     */
    public boolean isDoneWith(final int shardId, final long segmentSequence)
    {
        if (oldestOpenSegment.containsKey(Integer.MIN_VALUE))
        {
            return false;
        }
        final Long oldestOpen = oldestOpenSegment.get(shardId);
        if (oldestOpen != null && oldestOpen <= segmentSequence)
        {
            return false;
        }
        final String value = entries.get(segmentKey(shardId, segmentSequence));
        if (value == null)
        {
            return false;
        }
        final int colon = value.indexOf(':');
        try
        {
            return Long.parseLong((colon < 0 ? value : value.substring(0, colon)).trim()) == FINISHED;
        }
        catch (final NumberFormatException e)
        {
            return false;
        }
    }
}
