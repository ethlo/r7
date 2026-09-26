package com.ethlo.r7.r7f;

import static com.ethlo.r7.r7f.R7fConstants.ACTIVE_FILE_EXTENSION;
import static com.ethlo.r7.r7f.R7fConstants.R7F_FILE_EXTENSION;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalIntegrityListener;
import com.ethlo.r7.journal.api.ReassemblyOptions;

public final class R7Tailer
{
    private static final Logger logger = LoggerFactory.getLogger(R7Tailer.class);
    private static final String CHECKPOINT_FILE = ".r7_checkpoints";

    /**
     * Sentinel offset meaning "this segment has been read to the end".
     */
    private static final long FULLY_READ = -1L;

    /**
     * Read to the end, but the reader gave up on entries it could still have decoded — a
     * sequence regression, where the segment stops being a valid append-only log partway
     * through. Such a segment is never re-read and never deleted.
     * <p>
     * Damage is deleted; abandonment is kept. Bytes lost to a hole or a bad CRC are gone
     * whoever looks at them, so keeping the file preserves nothing but forensics. Bytes
     * after a regression are still there and still decodable — deleting the segment is the
     * only thing that would actually destroy them.
     */
    private static final long FULLY_READ_UNDELIVERED = -2L;

    private final Map<String, Checkpoint> checkpoints = new HashMap<>();

    /**
     * File-name metadata, memoised for the duration of one tick and cleared at the start of
     * the next. Single-threaded: {@code runTick} is the only caller.
     */
    private final Map<Path, FileMeta> metaCache = new HashMap<>();
    private final Path logDir;
    private final Duration gracePeriod;
    private final Duration ttl;
    private final ExchangeReassembler reassembler;
    private final JournalIntegrityListener integrity;
    private final Path checkpointPath;

    private long totalBytesRead = 0;
    private long totalMissingEntries = 0;
    private long totalCorruptEntries = 0;

    public R7Tailer(final Path logDir, final Duration gracePeriod, final ExchangeCompletionListener output)
    {
        this(logDir, gracePeriod, output, JournalIntegrityListener.NOOP, ReassemblyOptions.DEFAULTS);
    }

    /**
     * @param integrity receives damage and loss events for the segments read
     */
    public R7Tailer(final Path logDir,
                    final Duration gracePeriod,
                    final ExchangeCompletionListener output,
                    final JournalIntegrityListener integrity)
    {
        this(logDir, gracePeriod, output, integrity, ReassemblyOptions.DEFAULTS);
    }

    /**
     * @param integrity receives damage and loss events for the segments read
     * @param options   reassembly tuning; see {@code README.md} §11 for the side-effects
     *                  of each setting
     */
    public R7Tailer(final Path logDir,
                    final Duration gracePeriod,
                    final ExchangeCompletionListener output,
                    final JournalIntegrityListener integrity,
                    final ReassemblyOptions options)
    {
        this(logDir, null, gracePeriod, null, output, integrity, options);
    }

    /**
     * @param checkpointDir where {@code .r7_checkpoints} is read from and written to; defaults
     *                      to {@code logDir} when {@code null}. Giving each tailer process its
     *                      own directory (outside the shared journal mount) is what lets more
     *                      than one tailer follow the same journal directory without the
     *                      tailers overwriting each other's progress file.
     * @param gracePeriod   grace period after <em>this</em> tailer has fully read a segment
     *                      before it deletes it; {@code null} deletes as soon as read. Ignored
     *                      when {@code ttl} is set.
     * @param ttl           hard retention ceiling; {@code null} disables it and deletion works
     *                      exactly as {@code gracePeriod} describes. Setting it switches this
     *                      tailer's deletion from "as soon as I finished reading it" to "once
     *                      it is this old, whoever else may still be reading it" — a segment
     *                      older than {@code ttl} is deleted even if this tailer never
     *                      finished it, the same way rotation, clean close and recovery
     *                      already delete segments a checkpoint pointed at (see
     *                      {@link #forgetCheckpointsWithoutSegments}): the guarantee of
     *                      delivery expires on a timer instead of running forever. This is
     *                      what lets more than one tailer follow the same journal directory
     *                      without racing each other to delete: set the same generous
     *                      {@code ttl}, comfortably above the slowest tailer's expected
     *                      catch-up time, on every tailer sharing the directory.
     * @param integrity     receives damage and loss events for the segments read
     * @param options       reassembly tuning; see {@code README.md} §11 for the side-effects
     *                      of each setting
     */
    public R7Tailer(final Path logDir,
                    final Path checkpointDir,
                    final Duration gracePeriod,
                    final Duration ttl,
                    final ExchangeCompletionListener output,
                    final JournalIntegrityListener integrity,
                    final ReassemblyOptions options)
    {
        this.logDir = logDir;
        this.gracePeriod = gracePeriod;
        this.ttl = ttl;
        this.reassembler = new ExchangeReassembler(output, options, integrity);
        this.integrity = integrity;
        final Path resolvedCheckpointDir = checkpointDir != null ? checkpointDir : logDir;
        try
        {
            Files.createDirectories(resolvedCheckpointDir);
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("Could not create checkpoint directory " + resolvedCheckpointDir, e);
        }
        this.checkpointPath = resolvedCheckpointDir.resolve(CHECKPOINT_FILE);
        loadCheckpoints();
        logRetentionConfig();
    }

    /**
     * Logs, once at startup, exactly what this tailer will and will not delete - the
     * combination of {@code gracePeriod}, {@code ttl} and whether {@code logDir} is even
     * writable is easy to get wrong silently (a segment nobody ever deletes, or two tailers
     * quietly racing on deletion) and every one of those failure modes looks identical at
     * runtime: nothing in the logs until disk fills up or a tailer loses records it should
     * have kept. Said once here, plainly, instead.
     */
    private void logRetentionConfig()
    {
        if (ttl != null && gracePeriod != null)
        {
            throw new IllegalArgumentException("Both ttl (" + ttl + ") and gracePeriod (" + gracePeriod + ") are "
                    + "configured for " + logDir + "; the two are mutually exclusive by design (see the constructor's "
                    + "javadoc) - configure only one of them.");
        }

        final boolean writable = Files.isWritable(logDir);
        if (!writable)
        {
            logger.info("{} is not writable by this process: this tailer will never delete a segment, whatever ttl or "
                            + "gracePeriod says - retention is left entirely to whichever other tailer (or process) does "
                            + "have write access.",
                    logDir);
        }
        else if (ttl != null)
        {
            logger.info("{} is writable; this tailer deletes a segment once it is older than ttl {}, whether or not it "
                            + "was ever fully read. Give every tailer sharing this directory the same ttl, comfortably "
                            + "above the slowest one's expected catch-up time.",
                    logDir, ttl);
        }
        else
        {
            logger.info("{} is writable; this tailer deletes each segment as soon as it has fully read it{}.",
                    logDir, gracePeriod != null ? " (after waiting " + gracePeriod + ")" : "");
        }
    }

    public long runTick() throws IOException
    {
        totalBytesRead = 0;
        totalMissingEntries = 0;
        totalCorruptEntries = 0;
        metaCache.clear();
        final Set<String> fullyProcessedKeys = new HashSet<>();

        try
        {
            runTickBody(fullyProcessedKeys);
        }
        finally
        {
            // Whatever happened above, work that was already dispatched must not be left
            // un-checkpointed: the next tick would re-read those bytes and emit every
            // exchange in them a second time. One I/O error on one file used to discard the
            // progress of every file processed before it in the same tick.
            // The reassembler's age sweep is amortised over incoming events, so on a quiet
            // stream it would never run and abandoned exchanges would go unreported until
            // traffic resumed. A tick boundary is a natural pause.
            reassembler.sweep();
            logStats();
            saveCheckpoints();
        }

        return totalBytesRead;
    }

    private void runTickBody(final Set<String> fullyProcessedKeys) throws IOException
    {
        try (final Stream<Path> s = Files.list(logDir))
        {
            // Collect and deduplicate files by their stable key, which resolves an active
            // file and the sealed file it rotates into to the same segment.
            final Map<String, Path> resolvedFiles = new HashMap<>();

            s.filter(p -> {
                        final String name = p.getFileName().toString();
                        return name.endsWith(R7F_FILE_EXTENSION) ||
                                name.endsWith(ACTIVE_FILE_EXTENSION);
                    })
                    .filter(this::hasReadableIdentity)
                    .forEach(path -> {
                        final String key = getStableKey(path);
                        final Path existing = resolvedFiles.get(key);

                        // Priority: .r7f > .flux
                        if (existing == null || isHigherPriority(path, existing))
                        {
                            resolvedFiles.put(key, path);
                        }
                    });

            // Sort the resolved files and process them sequentially.
            // Segment sequence is a monotonic counter per shard, so it orders segments
            // exactly; the creation timestamp is wall-clock and cannot be relied on for
            // ordering across a clock step.
            resolvedFiles.values().stream()
                    .sorted((p1, p2) -> {
                        final FileMeta m1 = parseMeta(p1);
                        final FileMeta m2 = parseMeta(p2);
                        if (m1.shardId() != m2.shardId())
                        {
                            return Integer.compare(m1.shardId(), m2.shardId());
                        }
                        return Long.compare(m1.segmentSequence(), m2.segmentSequence());
                    })
                    .forEach(path -> {
                        try
                        {
                            final boolean isFinished = processFile(path);
                            if (isFinished)
                            {
                                fullyProcessedKeys.add(getStableKey(path));
                            }
                            checkDelete(path, fullyProcessedKeys);
                        }
                        catch (final IOException e)
                        {
                            throw new UncheckedIOException(e);
                        }
                    });

            // Only here, where the listing completed and every file in it was processed, do
            // we know which segments still exist. Rotation, clean close and recovery all
            // delete segments the tailer may hold a checkpoint for — an empty segment, an
            // unused pre-allocation — and nothing else would ever remove those entries. They
            // are not merely a leak: a checkpoint outliving its segment is what lets a reused
            // (shard, sequence) resume a brand-new segment at a dead one's offset.
            forgetCheckpointsWithoutSegments(resolvedFiles.keySet());
        }
    }

    /**
     * Whether a segment's name tells us where it belongs in the stream, setting it aside if
     * it does not.
     * <p>
     * Order is not a nicety here: the reassembler joins an exchange whose start is in one
     * segment to its end in the next, so replaying segments out of order turns a whole
     * exchange into an orphaned end. Segments are ordered by (shard, sequence), and both come
     * from the file name — so a name this parser cannot read has no position in the stream at
     * all. Every such file used to be given the same {@code (-1, -1)}, which sorted them
     * equal to each other and ahead of every real shard, leaving the actual order to whatever
     * {@link Files#list} happened to return. That is unspecified, so the outcome varied by
     * filesystem.
     * <p>
     * The writer cannot produce such a name — sealing preserves the stem, and quarantining
     * moves the file out of the tailer's filter entirely — so this only happens when someone
     * puts a file here by hand, usually restoring one. Quarantine says so and keeps the
     * contents; a rename puts it back in the stream. Guessing at the order and replaying it
     * would risk assembling records that never happened, and an audit log may not do that
     * even once.
     * <p>
     * An active file is left alone whatever its name: it may belong to a writer, and renaming
     * a file a writer has mapped would leave rotation unable to seal it.
     */
    private boolean hasReadableIdentity(final Path path)
    {
        if (parseMeta(path).segmentSequence() >= 0)
        {
            return true;
        }

        if (path.toString().endsWith(ACTIVE_FILE_EXTENSION))
        {
            logger.debug("Leaving active file with an unrecognized name alone: {}", path.getFileName());
            return false;
        }

        quarantine(path, "the file name carries no shard and sequence, so the segment has no "
                + "position in the stream and cannot be replayed in order");
        return false;
    }

    /**
     * Drops checkpoints for segments that are no longer on disk.
     */
    private void forgetCheckpointsWithoutSegments(final Set<String> keysOnDisk)
    {
        final int before = checkpoints.size();
        checkpoints.keySet().retainAll(keysOnDisk);
        final int dropped = before - checkpoints.size();
        if (dropped > 0)
        {
            logger.debug("Dropped {} checkpoint(s) whose segment no longer exists", dropped);
        }
    }

    private boolean isHigherPriority(final Path newPath, final Path existingPath)
    {
        return newPath.toString().endsWith(R7F_FILE_EXTENSION)
                && existingPath.toString().endsWith(ACTIVE_FILE_EXTENSION);
    }

    private boolean processFile(final Path path) throws IOException
    {
        final String key = getStableKey(path);
        final Checkpoint checkpoint = checkpoints.getOrDefault(key, Checkpoint.START);

        // Sentinel check: the file has been read to the end, so do not map it again.
        if (checkpoint.offset() == FULLY_READ)
        {
            return true;
        }
        if (checkpoint.offset() == FULLY_READ_UNDELIVERED)
        {
            // Finished, but holding entries this reader declined to deliver. Reporting it
            // "not finished" is what keeps checkDelete away from it, for as long as it is
            // here — deliberately, until someone decides what to do with it.
            return false;
        }

        final boolean isActive = path.toString().endsWith(ACTIVE_FILE_EXTENSION);

        // Enforce the preamble boundary so FlatBuffers never sees the manual binary header
        final long startOffset = Math.max(checkpoint.offset(), R7fConstants.PREAMBLE_SIZE);

        final long fileSize;
        try
        {
            fileSize = Files.size(path);
        }
        catch (final NoSuchFileException e)
        {
            return false;
        }

        try (final RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             final FileChannel channel = raf.getChannel())
        {
            final MappedByteBuffer mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            mappedBuffer.order(ByteOrder.LITTLE_ENDIAN);

            if (isActive && fileSize <= startOffset)
            {
                // Nothing to read at this offset, and the writer will extend it. The only
                // early exit left, and it is only safe because an active segment is never
                // finished and so is never deleted on the strength of this.
                return false;
            }

            // A sealed segment takes no shortcut, whatever its size. "The file is no longer
            // than where I stopped reading" was treated as "I read all of it", and runTick
            // turns that into a delete — but the two are the same statement only while
            // nothing shrinks a file. A sealed segment that lost its tail is exactly the
            // case the seal record was added to expose, and this exit skipped past the seal
            // record to delete the evidence. Everything below — preamble validation, the
            // declared Data End, the comparison against the recorded last sequence — has to
            // run before this file can be called finished.
            final ByteBuffer processingBuffer = mappedBuffer;

            // Clamped, because the resume offset can now legitimately lie past the end of a
            // file that shrank. There is nothing left to decode in that case; what matters is
            // that the checks below still get to run and say so.
            processingBuffer.position((int) Math.min(startOffset, fileSize));

            final String preambleProblem = preambleProblem(processingBuffer);
            if (preambleProblem != null)
            {
                if (isActive)
                {
                    // An active file belongs to the writer. The warmer pre-allocates the
                    // next segment and the writer stamps its preamble only when it claims
                    // it, so an all-zero header here is normal and momentary. More to the
                    // point, renaming a file the writer has mapped would leave rotation
                    // unable to seal it. Leave it be; recovery handles it at next boot.
                    logger.debug("Skipping active segment {} for now: {}", path.getFileName(), preambleProblem);
                    return false;
                }

                // A sealed file is nobody's to write any more. Without this
                // check it would be decoded as whatever its bytes happened to look like,
                // and then deleted as "processed".
                quarantine(path, preambleProblem);
                checkpoints.remove(key);
                return false;
            }

            if (!isActive)
            {
                boundBySealedDataEnd(path, processingBuffer, fileSize);
            }

            final long before = processingBuffer.remaining();

            // Carry the sequence across ticks: resuming mid-segment without it would let
            // entries go missing across the resume boundary unnoticed.
            final JournalDecoder.DecodeStats stats = JournalDecoder.decode(
                    processingBuffer,
                    reassembler,
                    checkpoint.nextSequence(),
                    path.getFileName().toString(),
                    integrity,
                    isActive);

            totalBytesRead += before - processingBuffer.remaining();
            totalMissingEntries += stats.missingEntries();
            totalCorruptEntries += stats.corruptEntriesSkipped();

            if (!stats.isClean())
            {
                logger.error("Segment {} is not intact: {} entries missing, {} corrupt regions skipped ({} bytes).",
                        path.getFileName(), stats.missingEntries(), stats.corruptEntriesSkipped(), stats.bytesSkipped());
            }

            final boolean isFinished = processingBuffer.remaining() == 0 && !isActive;

            final int nextSequence = stats.nextExpectedSequence() == JournalDecoder.UNKNOWN_SEQUENCE
                    ? checkpoint.nextSequence()
                    : stats.nextExpectedSequence();

            if (isFinished)
            {
                verifyAgainstSealRecord(path, processingBuffer, nextSequence);

                if (stats.undeliveredBytes() > 0)
                {
                    logger.error("Segment {} keeps {} bytes of readable entries this reader would not "
                                    + "deliver; it will not be deleted. Inspect it and remove it by hand once "
                                    + "the contents have been accounted for.",
                            path.getFileName(), stats.undeliveredBytes());
                    checkpoints.put(key, new Checkpoint(FULLY_READ_UNDELIVERED, nextSequence));
                    return false;
                }

                if (sealedForRetention(processingBuffer))
                {
                    // Recovery stopped short of the end and said so. What it left behind lies
                    // past Data End, so this reader never saw it and its own stats are
                    // perfectly clean — which is precisely the trap: a clean read of a
                    // deliberately shortened segment would be a delete, and the entries
                    // recovery preserved would be destroyed by the one component whose own
                    // regression handling exists to preserve them.
                    logger.error("Segment {} was sealed by recovery with content past its data end that "
                                    + "is readable but not replayable; it will not be deleted. Inspect it and "
                                    + "remove it by hand once the contents have been accounted for.",
                            path.getFileName());
                    checkpoints.put(key, new Checkpoint(FULLY_READ_UNDELIVERED, nextSequence));
                    return false;
                }

                checkpoints.put(key, new Checkpoint(FULLY_READ, nextSequence));
            }
            else
            {
                checkpoints.put(key, new Checkpoint(processingBuffer.position(), nextSequence));
            }

            return isFinished;
        }
    }

    /**
     * Limits a sealed segment to the extent its seal record declares.
     * <p>
     * Sealing no longer has to truncate for this to work. Where the data ends used to be
     * inferred from the file's size, which meant every seal had to cut the pre-allocated
     * tail or the reader would read zeroes and call them lost pages. Now it is a recorded
     * fact, so the tail is simply outside the segment's contents — neither data nor damage.
     * <p>
     * A segment with no seal record falls back to the whole file and the old inference; that
     * is reported by {@link #verifyAgainstSealRecord} when the segment finishes.
     */
    private void boundBySealedDataEnd(final Path path, final ByteBuffer buffer, final long fileSize)
    {
        final ByteBuffer header = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (header.limit() < R7fConstants.PREAMBLE_SIZE
                || header.getInt(R7fConstants.PREAMBLE_OFF_SEAL_MAGIC) != R7fConstants.SEAL_MAGIC)
        {
            return;
        }

        final long dataEnd = header.getLong(R7fConstants.PREAMBLE_OFF_DATA_END);
        final String name = path.getFileName().toString();

        if (dataEnd < R7fConstants.PREAMBLE_SIZE || dataEnd > fileSize)
        {
            // Beyond the file, or nonsensical. A data end past the end of the file means the
            // segment lost its tail after being sealed — which is exactly the loss the seal
            // record exists to expose, and which nothing inside the file could show.
            logger.error("Segment {} declares data ending at {} but the file is {} bytes.",
                    name, dataEnd, fileSize);
            integrity.onCorruptRegion(name, Math.min(dataEnd, fileSize), Math.max(0L, dataEnd - fileSize),
                    "declared data end lies outside the file");
            return;
        }

        // Clamping rather than comparing: a resume offset at or past the declared end means
        // the segment has been read out, and the buffer must end there so the caller sees
        // "finished" rather than decoding the pre-allocated tail as a hole.
        buffer.limit((int) Math.max(dataEnd, buffer.position()));
    }

    /**
     * Compares what this reader decoded against what the segment says it holds.
     * <p>
     * A sealed segment records its entry count and last sequence in its preamble, behind a
     * seal magic written last (FORMAT.md 3.2). That turns "did I read all of it?" from
     * something inferred out of not having hit anything unusual into a comparison that either
     * matches or does not.
     * <p>
     * Only the last sequence is checked here, because it needs no state the tailer does not
     * already carry across ticks. The entry count is for a full-scan verifier, which can
     * count what it sees.
     */
    private void verifyAgainstSealRecord(final Path path, final ByteBuffer buffer, final int nextSequence)
    {
        final ByteBuffer header = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        final String name = path.getFileName().toString();

        if (header.limit() < R7fConstants.PREAMBLE_SIZE
                || header.getInt(R7fConstants.PREAMBLE_OFF_SEAL_MAGIC) != R7fConstants.SEAL_MAGIC)
        {
            // Renamed without being sealed, or written by a build that predates the seal
            // record. The entries are still entries, so this is reported rather than acted
            // on — but it means nothing here can be cross-checked.
            integrity.onCorruptRegion(name, 0L, 0L, "sealed segment carries no seal record");
            logger.warn("Segment {} has no seal record; its contents cannot be cross-checked.", name);
            return;
        }

        final int recordedLast = header.getInt(R7fConstants.PREAMBLE_OFF_LAST_SEQUENCE);
        final int decodedLast = nextSequence - 1;

        if (decodedLast != recordedLast)
        {
            final int missing = recordedLast - decodedLast;
            if (missing > 0)
            {
                totalMissingEntries += missing;
                // Stated as the decoder states a gap: the sequence the reader was about to
                // read, and the one it would have found on the far side. Passing the two
                // last-sequence numbers instead made found < expected, so every consumer read
                // a forward gap at the end of a segment as a backward jump — the one shape
                // that means something entirely different (FORMAT.md §6). The arithmetic
                // holds now: found - expected == missing, as at every other call site.
                integrity.onEntriesMissing(name, 0L, decodedLast + 1, recordedLast + 1, missing);
                logger.error("Segment {} says its last entry is #{} but the reader finished at #{} — "
                        + "{} entries at the end of the segment were never read.", name, recordedLast, decodedLast, missing);
            }
            else
            {
                // More than the writer says it wrote: the seal record and the entries
                // disagree in the direction that cannot happen by loss alone.
                integrity.onCorruptRegion(name, 0L, 0L,
                        "seal record says last entry #" + recordedLast + " but #" + decodedLast + " was decoded");
                logger.error("Segment {} decoded past its recorded last entry (#{} > #{}).", name, decodedLast, recordedLast);
            }
        }
    }

    /**
     * Whether the segment's seal record asks for it to be kept after it has been read.
     * <p>
     * Only recovery sets this, and only when its scan stopped on a sequence regression. The
     * entries past Data End are structurally intact but not safe to replay, so recovery
     * hides them from readers instead of destroying them — and this flag is how that decision
     * survives the handover to a different process, which has no other way to know that the
     * file holds more than it was shown.
     */
    private static boolean sealedForRetention(final ByteBuffer buffer)
    {
        final ByteBuffer header = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (header.limit() < R7fConstants.PREAMBLE_SIZE
                || header.getInt(R7fConstants.PREAMBLE_OFF_SEAL_MAGIC) != R7fConstants.SEAL_MAGIC)
        {
            return false;
        }
        return (header.getInt(R7fConstants.PREAMBLE_OFF_SEAL_FLAGS) & R7fConstants.SEAL_FLAG_RETAIN) != 0;
    }

    /**
     * Checks the preamble of a segment the tailer is about to read.
     *
     * @return null when the preamble is a supported r7f header, otherwise what is wrong
     */
    private static String preambleProblem(final ByteBuffer buffer)
    {
        if (buffer.limit() < R7fConstants.PREAMBLE_SIZE)
        {
            return "shorter than the preamble (" + buffer.limit() + " bytes)";
        }

        // The tailer reads the rest of the buffer little-endian for FlatBuffers; the
        // preamble is big-endian like the rest of the framing.
        final ByteBuffer header = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);

        final int magic = header.getInt(R7fConstants.PREAMBLE_OFF_MAGIC);
        if (magic != R7fConstants.MAGIC)
        {
            return String.format("bad file magic 0x%08X (expected 0x%08X)", magic, R7fConstants.MAGIC);
        }

        final short version = header.getShort(R7fConstants.PREAMBLE_OFF_VERSION);
        if (version != R7fConstants.CURRENT_VERSION)
        {
            return "unsupported format version " + version;
        }

        return null;
    }

    /**
     * Sets aside a file the tailer cannot read, rather than decoding its bytes as whatever
     * they resemble and then deleting it.
     */
    private void quarantine(final Path path, final String reason)
    {
        final Path target = nonCollidingQuarantinePath(path);
        try
        {
            // No REPLACE_EXISTING: quarantine exists to preserve what could not be proven
            // good, so it must never be the thing that destroys an earlier copy.
            Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
            integrity.onSegmentQuarantined(path.getFileName().toString(), reason);
            logger.error("Quarantined unreadable segment {} as {}: {}", path.getFileName(), target.getFileName(), reason);
        }
        catch (final IOException e)
        {
            logger.error("Unable to quarantine unreadable segment {}", path.getFileName(), e);
        }
    }

    private void logStats()
    {
        if (totalBytesRead > 0)
        {
            logger.info("Tailer Stats: Processed {}", DiskSpaceUtils.formatBytes(totalBytesRead));
        }
        if (totalMissingEntries > 0 || totalCorruptEntries > 0)
        {
            logger.error("Tailer Stats: {} entries missing and {} corrupt regions skipped this tick.",
                    totalMissingEntries, totalCorruptEntries);
        }
    }

    public long getMissingEntryCount()
    {
        return totalMissingEntries;
    }

    public long getCorruptEntryCount()
    {
        return totalCorruptEntries;
    }

    private void checkDelete(final Path path, final Set<String> fullyProcessedKeys) throws IOException
    {
        final String key = getStableKey(path);
        final boolean processedByThisTailer = fullyProcessedKeys.contains(key);

        if (!processedByThisTailer && ttl == null)
        {
            return;
        }

        final long ageMillis = System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis();

        // Configuring a ttl is what lets more than one tailer follow the same journal
        // directory: each keeps its own checkpoint (see the checkpointDir constructor
        // parameter), and setting a ttl switches *this* tailer's deletion from "as soon as I
        // finished reading it" to "once it is this old, whoever else may still be reading
        // it" - the two are mutually exclusive, or the faster tailer would still race the
        // slower one to delete. A tailer still lagging behind when ttl elapses loses that
        // segment's undelivered entries the same way it already loses ones behind a dropped,
        // segment-less checkpoint (see forgetCheckpointsWithoutSegments): bounded loss,
        // chosen by the operator's ttl, not unbounded retention.
        final boolean delete = ttl != null
                ? ageMillis >= ttl.toMillis()
                : processedByThisTailer && (gracePeriod == null || ageMillis >= gracePeriod.toMillis());

        if (delete)
        {
            try
            {
                final boolean deleted = Files.deleteIfExists(path);
                checkpoints.remove(key);
                if (deleted)
                {
                    logger.info("Deleted {} segment: {}", ttl != null ? "expired" : "completed", path.getFileName());
                }
            }
            catch (final AccessDeniedException e)
            {
                // A tailer that is not the one responsible for retention is meant to be
                // mounted read-only on the journal directory - the deletion above is then
                // refused by the filesystem rather than by this tailer's own logic. The
                // checkpoint must be kept, not forgotten: the segment is still there, so its
                // offset is still valid, and forgetting it now would make the next tick
                // re-read the whole segment from the start and re-deliver every exchange in
                // it. Nothing else here needs to change - the tailer that does own retention
                // still deletes it once its own ttl/grace period is satisfied.
                logger.debug("No permission to delete {} (read-only journal mount?); leaving it for the tailer that owns retention",
                        path.getFileName());
            }
        }
    }

    /**
     * Parses a segment file name.
     * <p>
     * Active segments are named {@code shard-<shardId>-<createdEpochMillis>-<segmentSequence>.flux}.
     * Sealing appends the observed time bounds:
     * {@code shard-<shardId>-<createdEpochMillis>-<segmentSequence>-<firstTs>-<lastTs>.r7f},
     * The two trailing fields are therefore only present once a segment has been sealed.
     */
    private FileMeta parseMeta(final Path path)
    {
        // Every tick parses each name several times over — once per comparison in the sort,
        // once per stable key, once per delete check. The result cannot change while a tick
        // runs, because a rename produces a different Path.
        return metaCache.computeIfAbsent(path, this::parseMetaUncached);
    }

    private FileMeta parseMetaUncached(final Path path)
    {
        final String name = path.getFileName().toString()
                .replace(ACTIVE_FILE_EXTENSION, "")
                .replace(R7F_FILE_EXTENSION, "");
        try
        {
            final String[] parts = name.split("-");
            final int shardId = Integer.parseInt(parts[1]);
            final long createdEpochMillis = Long.parseLong(parts[2]);
            final long segmentSequence = Long.parseLong(parts[3]);
            final long firstEventEpochMillis = parts.length > 4 ? Long.parseLong(parts[4]) : -1L;
            final long lastEventEpochMillis = parts.length > 5 ? Long.parseLong(parts[5]) : -1L;

            return new FileMeta(shardId, createdEpochMillis, segmentSequence, firstEventEpochMillis, lastEventEpochMillis);
        }
        catch (final RuntimeException e)
        {
            // Not warn: the file is not being ignored. It still goes through preamble
            // validation and quarantine like any other, and a name this parser cannot read
            // would otherwise log on every tick, for ever.
            logger.debug("Unrecognized journal file name: {}", path.getFileName());
            return UNPARSED;
        }
    }

    /**
     * Stands in for a name this parser could not read. The negative shard and sequence are
     * what {@link #getStableKey} keys off: an unparsed file has no (shard, sequence)
     * identity, and must not be given one that another file could also hold.
     */
    private static final FileMeta UNPARSED = new FileMeta(-1, 0L, -1L, -1L, -1L);

    /**
     * Identifies a segment across its whole life, active and sealed. The shard id and the
     * monotonic segment sequence are the two fields that never change, so the checkpoint
     * survives the rename.
     */
    /**
     * A {@code .corrupt} name that is not already taken. A repeated quarantine of the same
     * segment — recovery partially succeeding twice, or an operator having restored a copy
     * — must not overwrite what is already set aside.
     */
    static Path nonCollidingQuarantinePath(final Path path)
    {
        final Path first = path.resolveSibling(path.getFileName() + R7fConstants.CORRUPT_FILE_EXTENSION);
        if (!Files.exists(first))
        {
            return first;
        }
        for (int n = 2; n < 1000; n++)
        {
            final Path candidate = path.resolveSibling(
                    path.getFileName() + R7fConstants.CORRUPT_FILE_EXTENSION + "." + n);
            if (!Files.exists(candidate))
            {
                return candidate;
            }
        }
        // Give up distinguishing rather than loop: the move will fail and be logged.
        return first;
    }

    private String getStableKey(final Path path)
    {
        final FileMeta meta = parseMeta(path);
        if (meta.segmentSequence() < 0)
        {
            // Unreachable for anything this tailer processes: hasReadableIdentity sets such
            // a file aside before it reaches the dedup map. Kept distinct per file name
            // rather than collapsing every unparsed file onto one key, so that a future
            // caller which does reach here cannot have two files share a checkpoint.
            return "unparsed-" + path.getFileName();
        }
        return "journal-" + meta.shardId() + "-" + meta.segmentSequence();
    }

    private void loadCheckpoints()
    {
        if (!Files.exists(checkpointPath))
        {
            return;
        }

        try (final InputStream in = Files.newInputStream(checkpointPath))
        {
            final Properties props = new Properties();
            props.load(in);
            props.forEach((k, v) -> {
                final Checkpoint parsed = Checkpoint.parse((String) v);
                if (parsed != null)
                {
                    checkpoints.put((String) k, parsed);
                }
                else
                {
                    logger.warn("Discarding unparsable checkpoint for {}", k);
                }
            });
            logger.info("Restored {} stable checkpoints from {}", checkpoints.size(), checkpointPath.toAbsolutePath());
        }
        catch (final IOException e)
        {
            logger.error("Load failed: {}", e.getMessage());
        }
    }

    private void saveCheckpoints()
    {
        if (checkpoints.isEmpty())
        {
            // Returning here would leave the previous file on disk with every entry it had
            // — which is precisely the state that makes a reused segment sequence
            // dangerous. Once the last segment has been read and deleted there is nothing
            // left to resume, and a stale "journal-0-1 → offset 60000000" would be applied
            // to whatever new segment takes that key next, skipping it or, if the offset is
            // past its data, marking it fully read and deleting it unread.
            try
            {
                Files.deleteIfExists(checkpointPath);
            }
            catch (final IOException e)
            {
                logger.error("Could not remove the obsolete checkpoint file {}: {}", checkpointPath, e.getMessage());
            }
            return;
        }

        final Path tempFile = checkpointPath.resolveSibling(CHECKPOINT_FILE + ".tmp");
        try
        {
            final Properties props = new Properties();
            checkpoints.forEach((k, v) -> props.setProperty(k, v.serialize()));

            try (final OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
            {
                props.store(out, "R7 Tailer Progress");
            }
            Files.move(tempFile, checkpointPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (final IOException e)
        {
            logger.error("Save failed: {}", e.getMessage());
        }
    }

    /**
     * How far into a segment the tailer has read, and which entry sequence it expects
     * next. Stored as {@code offset:sequence}; a bare offset is accepted so checkpoints
     * written before sequence tracking still load.
     */
    private record Checkpoint(long offset, int nextSequence)
    {
        /**
         * A segment nobody has read yet, starting at offset 0 — so the first entry it
         * yields must be {@link R7fConstants#FIRST_ENTRY_SEQUENCE}, because entry
         * sequences restart at 1 in every segment.
         * <p>
         * Using UNKNOWN_SEQUENCE here defeated the detection it was supposed to support:
         * with no expectation, the first entry that survives becomes the baseline, so a
         * segment whose opening entries never reached the device reads back as a shorter
         * but perfectly consistent segment and nothing is reported missing. The loss is at
         * the start of the file, which is exactly where a reader has the information to
         * catch it and nowhere else to get it from.
         */
        static final Checkpoint START = new Checkpoint(0L, R7fConstants.FIRST_ENTRY_SEQUENCE);

        static Checkpoint parse(final String value)
        {
            try
            {
                final int sep = value.indexOf(':');
                if (sep < 0)
                {
                    // A bare offset, written before sequences were tracked. It may point
                    // anywhere inside the segment, so in general there is no sequence to
                    // expect — unlike START, which is known to begin at the first entry.
                    //
                    // Offset 0 is the exception: it means nothing has been read, so the
                    // first entry must be #1 like any fresh segment. Treating it as unknown
                    // gave away the one expectation the reader is entitled to, and — since
                    // the value is re-serialised as "0:-1" — made the legacy carve-out
                    // permanent rather than one-shot.
                    final long legacyOffset = Long.parseLong(value.trim());
                    return new Checkpoint(legacyOffset,
                            legacyOffset == 0L ? R7fConstants.FIRST_ENTRY_SEQUENCE : JournalDecoder.UNKNOWN_SEQUENCE);
                }
                return new Checkpoint(
                        Long.parseLong(value.substring(0, sep).trim()),
                        Integer.parseInt(value.substring(sep + 1).trim()));
            }
            catch (final NumberFormatException e)
            {
                return null;
            }
        }

        String serialize()
        {
            return offset + ":" + nextSequence;
        }
    }

    private record FileMeta(int shardId,
                            long createdEpochMillis,
                            long segmentSequence,
                            long firstEventEpochMillis,
                            long lastEventEpochMillis)
    {
    }
}
