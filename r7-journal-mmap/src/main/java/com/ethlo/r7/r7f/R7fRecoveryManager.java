package com.ethlo.r7.r7f;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.journal.api.JournalIntegrityListener;

/**
 * Startup recovery for segments that were not sealed cleanly.
 * <p>
 * A segment is pre-allocated at full size and written append-only, so an unclean stop
 * leaves a prefix of valid entries followed by a partially written entry and/or the
 * zero-filled remainder of the pre-allocation. Recovery finds the last entry that is
 * structurally intact <em>and</em> passes its CRC, truncates the file to exactly that
 * boundary, and seals it under the finalized extension.
 * <p>
 * Because every entry carries a monotonic sequence number, recovery can also tell the
 * difference between "the tail was never written" and "a page in the middle did not reach
 * the device". The former is expected after a crash; the latter is reported loudly,
 * because it means entries were lost from the middle of the segment.
 * <p>
 * Files that cannot be interpreted at all (too short, wrong magic, unknown version) are
 * quarantined rather than deleted or left in place, so that they neither disappear
 * silently nor get rescanned on every boot.
 */
public final class R7fRecoveryManager
{
    private static final Logger logger = LoggerFactory.getLogger(R7fRecoveryManager.class);
    private static final ValueLayout.OfInt INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort SHORT_BE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private R7fRecoveryManager()
    {
    }

    /**
     * Scans the given directory for active segments, truncates each at the last valid
     * entry and renames it to the finalized extension.
     */
    private static void recoverActiveSegments(final Path journalDirectory, final JournalIntegrityListener integrity) throws IOException
    {
        if (!Files.exists(journalDirectory))
        {
            logger.warn("Journal directory does not exist, skipping recovery: {}", journalDirectory.toAbsolutePath());
            return;
        }

        final List<Path> activeFiles;
        try (Stream<Path> stream = Files.list(journalDirectory))
        {
            activeFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(R7fConstants.ACTIVE_FILE_EXTENSION))
                    .toList();
        }

        if (activeFiles.isEmpty())
        {
            logger.debug("Found 0 matching {} files. No recovery needed.", R7fConstants.ACTIVE_FILE_EXTENSION);
            return;
        }

        logger.debug("Found {} matching {} files. Starting recovery...", activeFiles.size(), R7fConstants.ACTIVE_FILE_EXTENSION);

        long totalRecordsRecovered = 0;
        long totalRecordsLost = 0;
        int successfulFiles = 0;
        int quarantinedFiles = 0;

        for (final Path activeFile : activeFiles)
        {
            logger.debug("Attempting to recover: {}", activeFile.getFileName());
            try
            {
                final RecoveryResult result = recoverFile(activeFile, integrity);
                totalRecordsRecovered += result.recordCount();
                totalRecordsLost += result.missingRecords();
                successfulFiles++;
                if (result.recordCount() > 0)
                {
                    logger.info("Recovered {} ({} valid records, {} missing)",
                            activeFile.getFileName(), result.recordCount(), result.missingRecords());
                }
            }
            catch (final UnreadableSegmentException e)
            {
                quarantinedFiles++;
                quarantine(activeFile, e.getMessage(), integrity);
            }
            catch (final Exception e)
            {
                quarantinedFiles++;
                logger.error("Unexpected error during recovery of {}, quarantining", activeFile.getFileName(), e);
                quarantine(activeFile, e.toString(), integrity);
            }
        }

        if (totalRecordsLost > 0)
        {
            logger.error("Recovery complete: {}/{} files sealed with {} records recovered, {} quarantined. "
                            + "{} records were LOST — the journal for this period is incomplete.",
                    successfulFiles, activeFiles.size(), totalRecordsRecovered, quarantinedFiles, totalRecordsLost);
        }
        else
        {
            logger.info("Recovery complete: {}/{} files sealed with {} records recovered, {} quarantined.",
                    successfulFiles, activeFiles.size(), totalRecordsRecovered, quarantinedFiles);
        }
    }

    /**
     * Scans one active segment, truncates it at the last fully valid entry and seals it.
     *
     * @throws UnreadableSegmentException if the file is not a recognizable r7f segment
     */
    private static RecoveryResult recoverFile(final Path file, final JournalIntegrityListener integrity) throws IOException
    {
        final ScanResult scanResult;
        final long originalSize;
        boolean uninitialised = false;

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined())
        {
            originalSize = channel.size();
            if (originalSize < R7fConstants.PREAMBLE_SIZE)
            {
                throw new UnreadableSegmentException("file is shorter than the preamble (" + originalSize + " bytes)");
            }

            final MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, originalSize, arena);

            if (isUninitialised(segment))
            {
                // A segment the warmer pre-allocated but the writer never took. Every
                // unclean stop leaves one, because the warmer keeps the next segment
                // mapped and ready. It is not damage, and reporting it as such would
                // train operators to ignore the integrity signal.
                uninitialised = true;
                scanResult = null;
            }
            else
            {
                validatePreamble(segment);
                scanResult = scan(segment, originalSize, file, integrity);
            }
        }
        // The confined arena is closed here, so the mapping is released before we resize the file.

        if (uninitialised)
        {
            logger.debug("Removing unused pre-allocated segment {}", file.getFileName());
            Files.delete(file);
            return new RecoveryResult(0, 0);
        }

        if (scanResult.recordCount() == 0)
        {
            logger.debug("No valid records in {}, removing", file.getFileName());
            Files.delete(file);
            return new RecoveryResult(0, scanResult.missingRecords());
        }

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE))
        {
            if (channel.size() > scanResult.lastValidPosition())
            {
                channel.truncate(scanResult.lastValidPosition());
            }
        }

        integrity.onSegmentTruncated(file.getFileName().toString(),
                scanResult.lastValidPosition(),
                originalSize - scanResult.lastValidPosition(),
                scanResult.recordCount());

        final String newName = file.getFileName().toString()
                .replace(R7fConstants.ACTIVE_FILE_EXTENSION, R7fConstants.R7F_FILE_EXTENSION);
        Files.move(file, file.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);

        return new RecoveryResult(scanResult.recordCount(), scanResult.missingRecords());
    }

    /**
     * True when the preamble was never written, which means the writer never took this
     * segment from the warmer. Distinguishable from damage because a written preamble
     * always starts with the file magic, and a damaged one starts with something else —
     * all zeroes only ever means "untouched".
     */
    private static boolean isUninitialised(final MemorySegment segment)
    {
        return segment.get(INT_BE, R7fConstants.PREAMBLE_OFF_MAGIC) == 0
                && segment.get(SHORT_BE, R7fConstants.PREAMBLE_OFF_VERSION) == 0;
    }

    private static void validatePreamble(final MemorySegment segment)
    {
        final int magic = segment.get(INT_BE, R7fConstants.PREAMBLE_OFF_MAGIC);
        if (magic != R7fConstants.MAGIC)
        {
            throw new UnreadableSegmentException(String.format("bad file magic 0x%08X (expected 0x%08X)", magic, R7fConstants.MAGIC));
        }

        final short version = segment.get(SHORT_BE, R7fConstants.PREAMBLE_OFF_VERSION);
        if (version != R7fConstants.CURRENT_VERSION)
        {
            throw new UnreadableSegmentException("unsupported format version " + version
                    + " (this build writes and reads version " + R7fConstants.CURRENT_VERSION + ")");
        }
    }

    /**
     * Walks entries from the end of the preamble, stopping at the first entry that is
     * incomplete, structurally inconsistent or fails its CRC. Everything before that
     * point is intact and is kept.
     * <p>
     * Sequence numbers are checked as we go. A forward jump means entries that were
     * written are not on disk, which the append-only design cannot produce on its own —
     * it means writeback did not complete in order. Those entries are gone, but we can at
     * least say how many and where.
     */
    private static ScanResult scan(final MemorySegment segment, final long size, final Path file, final JournalIntegrityListener integrity)
    {
        long position = R7fConstants.PREAMBLE_SIZE;
        long lastValidPosition = R7fConstants.PREAMBLE_SIZE;
        long recordCount = 0;
        long missingRecords = 0;
        int expectedSequence = R7fConstants.FIRST_ENTRY_SEQUENCE;

        final CRC32C crc = new CRC32C();

        while (size - position >= R7fConstants.MIN_ENTRY_SIZE)
        {
            // The pre-allocated tail is zero-filled, so a zero here means no entry was
            // ever written at this offset.
            if (segment.get(ValueLayout.JAVA_BYTE, position) == 0)
            {
                break;
            }

            final int magic = segment.get(INT_BE, position);
            if (magic != R7fConstants.MAGIC)
            {
                logger.warn("Corrupt entry magic at offset {} in {}, truncating there", position, file.getFileName());
                break;
            }

            final int sequence = segment.get(INT_BE, position + 4L);
            final int payloadLen = segment.get(INT_BE, position + 8L);
            final int fbLen = segment.get(INT_BE, position + 12L);
            final int rawLen = segment.get(INT_BE, position + 16L);

            if (fbLen < 0 || rawLen < 0 || payloadLen != (Integer.BYTES * 2 + fbLen + rawLen))
            {
                logger.warn("Inconsistent entry lengths at offset {} in {} (payloadLen={}, fbLen={}, rawLen={}), truncating there",
                        position, file.getFileName(), payloadLen, fbLen, rawLen);
                break;
            }

            final long dataLen = (long) fbLen + rawLen;
            final long entryEnd = position + R7fConstants.ENTRY_HEADER_SIZE + dataLen + Integer.BYTES;
            if (entryEnd > size)
            {
                logger.warn("Entry at offset {} in {} extends past end of file, truncating there", position, file.getFileName());
                break;
            }

            crc.reset();
            updateInt(crc, sequence);
            updateInt(crc, payloadLen);
            updateInt(crc, fbLen);
            updateInt(crc, rawLen);
            if (dataLen > 0)
            {
                crc.update(segment.asSlice(position + R7fConstants.ENTRY_HEADER_SIZE, dataLen).asByteBuffer());
            }

            final int storedCrc = segment.get(INT_BE, position + R7fConstants.ENTRY_HEADER_SIZE + dataLen);
            if ((int) crc.getValue() != storedCrc)
            {
                logger.warn("Checksum mismatch at offset {} in {} — the stop interrupted this entry. Truncating there.",
                        position, file.getFileName());
                break;
            }

            if (sequence != expectedSequence)
            {
                if (sequence > expectedSequence)
                {
                    final int lost = sequence - expectedSequence;
                    missingRecords += lost;
                    integrity.onEntriesMissing(file.getFileName().toString(), position, expectedSequence, sequence, lost);
                    logger.error("Sequence gap in {} at offset {}: expected #{} but found #{} — {} entries are missing from this segment.",
                            file.getFileName(), position, expectedSequence, sequence, lost);
                }
                else
                {
                    integrity.onSequenceRegression(file.getFileName().toString(), position, expectedSequence, sequence);
                    logger.error("Sequence went backwards in {} at offset {}: expected #{} but found #{}. Truncating there.",
                            file.getFileName(), position, expectedSequence, sequence);
                    break;
                }
            }

            expectedSequence = sequence + 1;
            position = entryEnd;
            lastValidPosition = entryEnd;
            recordCount++;
        }

        return new ScanResult(lastValidPosition, recordCount, missingRecords);
    }

    private static void quarantine(final Path file, final String reason, final JournalIntegrityListener integrity)
    {
        final Path target = file.resolveSibling(file.getFileName() + R7fConstants.CORRUPT_FILE_EXTENSION);
        try
        {
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            integrity.onSegmentQuarantined(file.getFileName().toString(), reason);
            logger.error("Quarantined unreadable segment {} as {}: {}", file.getFileName(), target.getFileName(), reason);
        }
        catch (final IOException e)
        {
            logger.error("Unable to quarantine unreadable segment {}", file.getFileName(), e);
        }
    }

    private static void updateInt(final CRC32C crc, final int value)
    {
        crc.update((value >>> 24) & 0xFF);
        crc.update((value >>> 16) & 0xFF);
        crc.update((value >>> 8) & 0xFF);
        crc.update(value & 0xFF);
    }

    public static List<Path> cleanAndRecover(final Path journalDirectory) throws IOException
    {
        return cleanAndRecover(journalDirectory, JournalIntegrityListener.NOOP);
    }

    /**
     * @param integrity receives an event per quarantined, truncated or incomplete segment
     */
    public static List<Path> cleanAndRecover(final Path journalDirectory, final JournalIntegrityListener integrity) throws IOException
    {
        if (!Files.exists(journalDirectory))
        {
            return List.of();
        }

        // 1. Delete orphaned partial compressions
        try (Stream<Path> stream = Files.list(journalDirectory))
        {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".zst.tmp"))
                    .forEach(p -> {
                        try
                        {
                            Files.deleteIfExists(p);
                            logger.info("Deleted orphaned temp file: {}", p.getFileName());
                        }
                        catch (final Exception e)
                        {
                            logger.warn("Unable to delete orphaned temp file {}", p.getFileName(), e);
                        }
                    });
        }

        // 2. Recover the active files
        recoverActiveSegments(journalDirectory, integrity);

        // 3. Collect ALL uncompressed files for the compression queue
        try (Stream<Path> stream = Files.list(journalDirectory))
        {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(R7fConstants.R7F_FILE_EXTENSION))
                    .toList();
        }
    }

    private record ScanResult(long lastValidPosition, long recordCount, long missingRecords)
    {
    }

    private record RecoveryResult(long recordCount, long missingRecords)
    {
    }

    static final class UnreadableSegmentException extends RuntimeException
    {
        UnreadableSegmentException(final String message)
        {
            super(message);
        }
    }
}
