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
 * structurally intact <em>and</em> passes its CRC, records that point as the segment's Data
 * End, and seals it under the finalized extension. The pre-allocated tail is left in place:
 * Data End is what a reader bounds itself by, and shrinking a file the tailer may have
 * mapped — in another process — is a SIGBUS.
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
    private static final ValueLayout.OfLong LONG_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private R7fRecoveryManager()
    {
    }

    /**
     * Scans the given directory for active segments, seals each at the last valid
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
     * Scans one active segment and seals it at the last fully valid entry.
     *
     * @throws UnreadableSegmentException if the file is not a recognizable r7f segment
     */
    private static RecoveryResult recoverFile(final Path file, final JournalIntegrityListener integrity) throws IOException
    {
        final ScanResult scanResult;
        final long originalSize;
        boolean uninitialised = false;
        boolean dataRegionIsZero = false;
        boolean trailingBytesAreZero = true;

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined())
        {
            originalSize = channel.size();
            if (originalSize < R7fConstants.PREAMBLE_SIZE)
            {
                throw new UnreadableSegmentException("file is shorter than the preamble (" + originalSize + " bytes)");
            }

            final MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, originalSize, arena);

            if (hasUnwrittenPreamble(segment))
            {
                // A zero preamble usually means the warmer pre-allocated this segment and
                // the writer never took it. Every unclean stop leaves one, and reporting
                // it as damage would train operators to ignore the integrity signal.
                //
                // But a segment whose preamble page was lost while later pages survived
                // looks identical from those six bytes, and that is exactly what the
                // out-of-order writeback this format documents can produce. Deleting on
                // that evidence would destroy recoverable records, so the whole file has
                // to be zero before we believe it was never used.
                if (isEntirelyZero(segment, originalSize))
                {
                    uninitialised = true;
                    scanResult = null;
                }
                else
                {
                    throw new UnreadableSegmentException(
                            "preamble is unwritten but the file is not empty — its header was lost "
                                    + "while later data survived");
                }
            }
            else
            {
                validatePreamble(segment);
                scanResult = scan(segment, originalSize, file, integrity);

                if (scanResult.recordCount() == 0)
                {
                    // Decided here, while the mapping is still open: the arena closes below.
                    dataRegionIsZero = isEntirelyZero(segment, R7fConstants.PREAMBLE_SIZE, originalSize);
                }

                // Likewise: how much of what lies past the last valid entry is actually
                // content rather than the untouched remainder of the pre-allocation.
                trailingBytesAreZero = isEntirelyZero(segment, scanResult.lastValidPosition(), originalSize);
            }
        }
        // The confined arena is closed here, so the mapping is released before the file is
        // renamed and its seal record written through a channel.

        if (uninitialised)
        {
            logger.debug("Removing unused pre-allocated segment {}", file.getFileName());
            Files.delete(file);
            return new RecoveryResult(0, 0);
        }

        if (scanResult.recordCount() == 0)
        {
            if (!dataRegionIsZero)
            {
                // Stamped, and it still holds bytes, but the scan could not read a single
                // entry from them — the first entry is torn or corrupt and no later magic
                // was found. Those bytes are the only remaining evidence of what was
                // written, and deleting them makes lost audit data look exactly like a
                // segment that was never used. Quarantine instead: the caller renames it
                // and reports it, so it neither disappears nor is rescanned for ever.
                throw new UnreadableSegmentException(
                        "segment holds data after the preamble but no entry could be read from it");
            }

            logger.debug("Stamped but empty segment {}, removing", file.getFileName());
            Files.delete(file);
            return new RecoveryResult(0, scanResult.missingRecords());
        }

        // Not truncated to lastValidPosition, deliberately. The seal record's Data End says
        // where the entries stop, so nothing needs the file's size to agree — and the tailer
        // may have this very file mapped at its pre-allocated length, in a different
        // process. Shrinking it underneath that mapping is a SIGBUS, which is the only way
        // recovery could take the reader down while putting the writer's data back together.
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE))
        {
            // A regression means the entries past Data End are readable but not replayable.
            // They stay in the file, and the flag tells whoever reads this segment not to
            // delete it once the prefix has been consumed — otherwise recovery would hide
            // them and the tailer would then destroy them, which is exactly the outcome the
            // tailer's own regression handling refuses.
            final int sealFlags = scanResult.stoppedOnRegression() ? R7fConstants.SEAL_FLAG_RETAIN : 0;
            stampSealRecord(channel, scanResult.recordCount(), scanResult.lastSequence(),
                    scanResult.lastValidPosition(), sealFlags);
        }

        final String newName = file.getFileName().toString()
                .replace(R7fConstants.ACTIVE_FILE_EXTENSION, R7fConstants.R7F_FILE_EXTENSION);
        Files.move(file, file.resolveSibling(newName), StandardCopyOption.ATOMIC_MOVE);

        // Reported only once the segment is sealed, and after the rename rather than before.
        // The caller quarantines anything that throws out of here, so a listener that failed
        // — user code, on a path with no other error handling — used to turn a fully
        // recovered segment into a .corrupt file that no reader ever looks at.
        // Only content counts as discarded. Everything past the last valid entry used to be
        // reported here, which was defensible while sealing truncated — those bytes really
        // did leave the file. Now that the tail stays, that number would be the whole unused
        // pre-allocation on every clean recovery: a six-figure "discarded" against a segment
        // that lost nothing at all.
        final long discarded = trailingBytesAreZero ? 0L : originalSize - scanResult.lastValidPosition();

        integrity.onSegmentRecovered(newName,
                scanResult.lastValidPosition(),
                discarded,
                scanResult.recordCount());

        return new RecoveryResult(scanResult.recordCount(), scanResult.missingRecords());
    }

    /**
     * True when the preamble's magic and version are both zero, so it was never stamped.
     * On its own this does not say whether the segment was unused or merely lost its
     * header — see {@link #isEntirelyZero}.
     */
    private static boolean hasUnwrittenPreamble(final MemorySegment segment)
    {
        return segment.get(INT_BE, R7fConstants.PREAMBLE_OFF_MAGIC) == 0
                && segment.get(SHORT_BE, R7fConstants.PREAMBLE_OFF_VERSION) == 0;
    }

    /**
     * Whether the file contains nothing but zeroes, which is the only safe basis for
     * deleting it as an unused pre-allocation. Read eight bytes at a time; this runs once
     * per unwritten-looking segment at startup.
     */
    private static boolean isEntirelyZero(final MemorySegment segment, final long size)
    {
        return isEntirelyZero(segment, 0, size);
    }

    private static boolean isEntirelyZero(final MemorySegment segment, final long from, final long size)
    {
        long position = from;
        final long wordEnd = size - ((size - from) % Long.BYTES);

        for (; position < wordEnd; position += Long.BYTES)
        {
            if (segment.get(LONG_BE, position) != 0L)
            {
                return false;
            }
        }
        for (; position < size; position++)
        {
            if (segment.get(ValueLayout.JAVA_BYTE, position) != 0)
            {
                return false;
            }
        }
        return true;
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
     * Walks entries from the end of the preamble to the end of the file.
     * <p>
     * Damage does not end the scan. An unclean stop leaves a torn entry at the tail, but a
     * power loss can leave a gap anywhere, with perfectly good entries after it — stopping
     * at the first anomaly and sealing there would strand those entries before anyone
     * could see that they were separated from the rest by a hole. So the scan resynchronises
     * on the next entry magic, reports what it skipped, and keeps going. The file is later
     * sealed at the end of the <em>last</em> valid entry found, not the first anomaly.
     * <p>
     * Sequence numbers make the difference visible: a forward jump means entries that were
     * written are not on disk, which append-only writing cannot produce on its own. Those
     * entries are gone, but we can say how many and where.
     */
    private static ScanResult scan(final MemorySegment segment, final long size, final Path file, final JournalIntegrityListener integrity)
    {
        long position = R7fConstants.PREAMBLE_SIZE;
        long lastValidPosition = R7fConstants.PREAMBLE_SIZE;
        long recordCount = 0;
        long missingRecords = 0;
        boolean stoppedOnRegression = false;
        int expectedSequence = R7fConstants.FIRST_ENTRY_SEQUENCE;

        final String name = file.getFileName().toString();
        final CRC32C crc = new CRC32C();

        while (size - position >= R7fConstants.MIN_ENTRY_SIZE)
        {
            final String problem = validateEntryAt(segment, size, position, crc);

            if (problem != null)
            {
                // Look for a later entry before concluding that this is the end. If none
                // follows, this is the ordinary torn tail and the scan is done.
                final long resume = findNextEntry(segment, size, position + 1);
                if (resume < 0)
                {
                    if (recordCount > 0 || segment.get(ValueLayout.JAVA_BYTE, position) != 0)
                    {
                        logger.debug("{} ends at offset {} ({})", name, position, problem);
                    }
                    break;
                }

                final long skipped = resume - position;
                logger.error("Damaged region in {} at offset {} ({} bytes, {}); valid entries follow, "
                        + "resuming at {}.", name, position, skipped, problem, resume);
                integrity.onCorruptRegion(name, position, skipped, problem);
                position = resume;
                continue;
            }

            final int sequence = segment.get(INT_BE, position + 4L);
            final int fbLen = segment.get(INT_BE, position + 12L);
            final int rawLen = segment.get(INT_BE, position + 16L);
            final long entryEnd = position + R7fConstants.ENTRY_HEADER_SIZE + (long) fbLen + rawLen + Integer.BYTES;

            if (sequence != expectedSequence)
            {
                if (sequence > expectedSequence)
                {
                    final int lost = sequence - expectedSequence;
                    missingRecords += lost;
                    integrity.onEntriesMissing(name, position, expectedSequence, sequence, lost);
                    logger.error("Sequence gap in {} at offset {}: expected #{} but found #{} — {} entries are missing from this segment.",
                            name, position, expectedSequence, sequence, lost);
                }
                else
                {
                    integrity.onSequenceRegression(name, position, expectedSequence, sequence);
                    logger.error("Sequence went backwards in {} at offset {}: expected #{} but found #{}. Stopping, "
                                    + "and marking the segment so that what follows is kept rather than deleted.",
                            name, position, expectedSequence, sequence);
                    stoppedOnRegression = true;
                    break;
                }
            }

            expectedSequence = sequence + 1;
            position = entryEnd;
            lastValidPosition = entryEnd;
            recordCount++;
        }

        return new ScanResult(lastValidPosition, recordCount, missingRecords, expectedSequence - 1, stoppedOnRegression);
    }

    /**
     * Checks the entry at {@code position}.
     *
     * @return null when the entry is structurally sound and its CRC matches, otherwise a
     * short description of what is wrong with it
     */
    private static String validateEntryAt(final MemorySegment segment, final long size, final long position, final CRC32C crc)
    {
        if (segment.get(ValueLayout.JAVA_BYTE, position) == 0)
        {
            return "unwritten region";
        }

        if (segment.get(INT_BE, position) != R7fConstants.MAGIC)
        {
            return "bad entry magic";
        }

        final int sequence = segment.get(INT_BE, position + 4L);
        final int payloadLen = segment.get(INT_BE, position + 8L);
        final int fbLen = segment.get(INT_BE, position + 12L);
        final int rawLen = segment.get(INT_BE, position + 16L);

        if (fbLen < 0 || rawLen < 0 || payloadLen != (Integer.BYTES * 2 + fbLen + rawLen))
        {
            return "inconsistent entry lengths (payloadLen=" + payloadLen + ", fbLen=" + fbLen + ", rawLen=" + rawLen + ")";
        }

        final long dataLen = (long) fbLen + rawLen;
        if (position + R7fConstants.ENTRY_HEADER_SIZE + dataLen + Integer.BYTES > size)
        {
            return "entry extends past end of file";
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

        if ((int) crc.getValue() != segment.get(INT_BE, position + R7fConstants.ENTRY_HEADER_SIZE + dataLen))
        {
            return "checksum mismatch";
        }

        return null;
    }

    /**
     * Scans forward for the next entry magic, crossing zeroes rather than stopping at them.
     * <p>
     * The zero-filled tail of a pre-allocated segment and a hole left by incomplete
     * writeback look identical from one byte, so the only way to tell them apart is to look
     * for what comes after. The first-byte test keeps the common case — scanning a long
     * zero tail to the end of the file — down to one comparison per position.
     *
     * @return the offset of the next entry magic, or -1 if none remains
     */
    private static long findNextEntry(final MemorySegment segment, final long size, final long from)
    {
        final byte firstMagicByte = (byte) (R7fConstants.MAGIC >>> 24);
        final long end = size - R7fConstants.MIN_ENTRY_SIZE;

        for (long pos = from; pos <= end; pos++)
        {
            if (segment.get(ValueLayout.JAVA_BYTE, pos) == firstMagicByte
                    && segment.get(INT_BE, pos) == R7fConstants.MAGIC)
            {
                return pos;
            }
        }
        return -1;
    }

    private static void quarantine(final Path file, final String reason, final JournalIntegrityListener integrity)
    {
        final Path target = R7Tailer.nonCollidingQuarantinePath(file);
        try
        {
            // No REPLACE_EXISTING: quarantine preserves what could not be proven good, so it
            // must never destroy an earlier quarantined copy of the same segment.
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
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
     * @param integrity receives an event per quarantined, shortened or incomplete segment
     */
    public static List<Path> cleanAndRecover(final Path journalDirectory, final JournalIntegrityListener integrity) throws IOException
    {
        if (!Files.exists(journalDirectory))
        {
            return List.of();
        }

        recoverActiveSegments(journalDirectory, integrity);

        // The sealed segments now present. Compression is no longer part of a segment's
        // life — it is one thing a consumer may choose to do — so this list is returned for
        // the caller's information rather than fed to a compression queue.
        try (Stream<Path> stream = Files.list(journalDirectory))
        {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(R7fConstants.R7F_FILE_EXTENSION))
                    .toList();
        }
    }

    /**
     * @param lastSequence the highest entry sequence read, or {@code FIRST_ENTRY_SEQUENCE - 1}
     *                     when no entry was read at all
     */
    /**
     * Records what the sealed segment contains, in the segment itself, so that a reader can
     * check its own decode against the file's own account rather than inferring completeness
     * from the absence of surprises.
     * <p>
     * Recovery is the one place the two numbers can legitimately disagree: the writer always
     * seals with {@code count == lastSequence}, so a sealed segment whose count is lower than
     * its last sequence is one that lost entries — visible from the preamble alone, without
     * scanning.
     * <p>
     * The seal magic goes last, after the facts it vouches for, exactly as an entry's magic
     * does.
     */
    private static void stampSealRecord(final FileChannel channel, final long entryCount, final int lastSequence,
                                        final long dataEnd, final int sealFlags) throws IOException
    {
        final ByteBuffer facts = ByteBuffer.allocate(Long.BYTES + Integer.BYTES + Long.BYTES + Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        facts.putLong(entryCount).putInt(lastSequence).putLong(dataEnd).putInt(sealFlags).flip();
        writeFully(channel, facts, R7fConstants.PREAMBLE_OFF_ENTRY_COUNT);

        final ByteBuffer sealMagic = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        sealMagic.putInt(R7fConstants.SEAL_MAGIC).flip();
        writeFully(channel, sealMagic, R7fConstants.PREAMBLE_OFF_SEAL_MAGIC);
    }

    /**
     * Positional {@link FileChannel#write} may write fewer bytes than remain, so it has to
     * be driven to completion. A short write on the facts followed by a complete write of
     * the seal magic would publish a seal record vouching for numbers that were never
     * finished — the one thing the magic-last ordering exists to prevent.
     */
    private static void writeFully(final FileChannel channel, final ByteBuffer buffer, final long position) throws IOException
    {
        long at = position;
        while (buffer.hasRemaining())
        {
            final int written = channel.write(buffer, at);
            if (written <= 0)
            {
                throw new IOException("Made no progress writing the seal record at offset " + at);
            }
            at += written;
        }
    }

    private record ScanResult(long lastValidPosition, long recordCount, long missingRecords, int lastSequence,
                              boolean stoppedOnRegression)
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
