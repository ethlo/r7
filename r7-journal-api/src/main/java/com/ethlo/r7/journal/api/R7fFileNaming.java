package com.ethlo.r7.journal.api;

/**
 * The on-disk naming contract for r7f journal segments: shared by every process that has to
 * recognize a segment's lifecycle stage from its filename alone, without decoding the file
 * itself. This is deliberately in the contracts module rather than {@code r7-journal-mmap} -
 * a segment reaper (or any other file-system-level tool) needs these three suffixes and
 * nothing else, and must not have to pull in the mmap engine just to tell an active segment
 * from a sealed one.
 * <p>
 * See {@code r7-journal-mmap}'s {@code R7fConstants}, which re-exposes these same values for
 * code that already depends on the engine, and {@code FORMAT.md} for the full segment
 * lifecycle these suffixes encode.
 */
public final class R7fFileNaming
{
    /**
     * A segment the writer has finished with and sealed; safe for any reader, and the only
     * stage a retention tool may ever consider for deletion.
     */
    public static final String SEALED_FILE_EXTENSION = ".r7f";

    /**
     * A segment still being written to. Belongs exclusively to the gateway process that
     * created it - nothing else may rename, delete, or otherwise treat it as finished.
     */
    public static final String ACTIVE_FILE_EXTENSION = ".flux";

    /**
     * A segment a reader could not prove readable, set aside under its original name plus
     * this suffix rather than decoded as whatever it resembles. See {@code R7Tailer}'s
     * quarantine handling.
     */
    public static final String CORRUPT_FILE_EXTENSION = ".corrupt";

    /**
     * The filename prefix every segment (active, sealed, or quarantined) starts with; see
     * {@code R7fJournalProvider} for how the rest of the name is built.
     */
    public static final String SHARD_FILE_PREFIX = "shard-";

    private R7fFileNaming()
    {
    }
}
