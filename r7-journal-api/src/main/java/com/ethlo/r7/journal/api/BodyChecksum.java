package com.ethlo.r7.journal.api;

import java.util.zip.CRC32C;

/**
 * A body checksum, or the explicit statement that none was computed.
 * <p>
 * The distinction cannot be carried by a number. Every 32-bit value, zero included, is the
 * legitimate CRC32C of some input, so "no checksum" has to live outside the value domain —
 * and while it lived there as a {@code long} sentinel, three call sites passed a literal
 * {@code 0} meaning "none" and thereby claimed that a 32&nbsp;KB body hashes to zero. Each
 * compiled, ran, and produced a mismatch on every exchange it wrote. That is the whole
 * reason this type exists: {@link #NOT_RECORDED} is the only way to say "none", and it is
 * not a number, so it cannot be typed by accident.
 * <p>
 * There is deliberately no encoding here — no {@code toWire}, no sentinel to read back.
 * A storage format needs one, because a file has no types, but that value is a fact about
 * that format and belongs with its other constants; putting it here would hand every
 * consumer a total, never-throwing accessor that returns a number for
 * {@code NOT_RECORDED}, which is the same hole in a new place. Ask {@link #isRecorded()}
 * and then {@link #value()}. There is no third option.
 * <p>
 * Immutable and shared: {@code NOT_RECORDED} is a singleton, so the common path — a journal
 * level below {@code FULL}, a request with no body, a protocol such as WebSocket that never
 * installs the body tees — allocates nothing at all. A direction that did checksum a body
 * allocates one small object per exchange, against a request that has already copied the
 * body itself.
 */
public final class BodyChecksum
{
    /**
     * No checksum was computed for this direction.
     * <p>
     * This is a statement, not a missing value: the writer is saying it passed no body bytes
     * to the journal, so there is nothing for a reader to verify. A reader must skip
     * verification rather than treat it as a checksum that failed to match.
     */
    public static final BodyChecksum NOT_RECORDED = new BodyChecksum(0L, false);

    /**
     * Meaningful only when {@link #recorded} is true. Held alongside a flag rather than as a
     * reserved value, so that this class has no sentinel of its own to be confused with a
     * storage format's.
     */
    private final long value;
    private final boolean recorded;

    private BodyChecksum(final long value, final boolean recorded)
    {
        this.value = value;
        this.recorded = recorded;
    }

    /**
     * The checksum an accumulator holds, or {@link #NOT_RECORDED} for a {@code null}
     * accumulator.
     * <p>
     * Null is the honest input here rather than an oversight: a writer creates the
     * accumulator on the first body fragment it journals, so its absence is exactly the
     * statement "no body was journaled in this direction". Creating it eagerly would answer
     * the CRC32C of nothing for both cases and lose the distinction.
     * <p>
     * Never take {@code (int) crc.getValue()} and widen it yourself. Narrowing and
     * re-widening sign-extends every checksum with the high bit set into a value no reader
     * can ever compute.
     */
    public static BodyChecksum of(final CRC32C accumulator)
    {
        return accumulator == null ? NOT_RECORDED : new BodyChecksum(accumulator.getValue(), true);
    }

    /**
     * A checksum already computed elsewhere, as the unsigned 32-bit value.
     * <p>
     * Spelled out rather than called {@code of} so that it cannot be reached for absently:
     * {@code ofUnsigned32(0)} plainly asserts that the body hashes to zero, which is a thing
     * a caller may legitimately mean and is never what a caller means by "there is no
     * checksum". That is {@link #NOT_RECORDED}.
     *
     * @throws IllegalArgumentException if the value is not in {@code [0, 0xFFFFFFFF]}. A
     *                                  negative number here is almost always a CRC32C that
     *                                  was narrowed to {@code int} somewhere upstream, and
     *                                  recording it would guarantee a mismatch against an
     *                                  intact body. A reader decoding a stored field that
     *                                  fails this check is looking at a damaged payload and
     *                                  should treat it as one.
     */
    public static BodyChecksum ofUnsigned32(final long unsignedCrc32c)
    {
        if (unsignedCrc32c < 0L || unsignedCrc32c > 0xFFFFFFFFL)
        {
            throw new IllegalArgumentException("Not an unsigned 32-bit CRC32C value: " + unsignedCrc32c
                    + ". A negative value usually means the checksum was narrowed to int and re-widened;"
                    + " use BodyChecksum.NOT_RECORDED to say that no checksum was computed.");
        }
        return new BodyChecksum(unsignedCrc32c, true);
    }

    /**
     * Whether a checksum was computed at all. A reader verifies only when this is true.
     */
    public boolean isRecorded()
    {
        return recorded;
    }

    /**
     * The unsigned 32-bit checksum.
     *
     * @throws IllegalStateException if none was recorded — ask {@link #isRecorded()} first.
     *                               Returning a placeholder would put a sentinel back into
     *                               arithmetic, which is what this type exists to prevent.
     */
    public long value()
    {
        if (!recorded)
        {
            throw new IllegalStateException("No checksum was recorded for this body");
        }
        return value;
    }

    /**
     * Value equality, with {@link #NOT_RECORDED} equal only to itself.
     * <p>
     * This is what makes verification a single comparison: a recorded checksum against an
     * observed {@code NOT_RECORDED} — a body the writer hashed and the reader never saw — is
     * unequal, and so reports a mismatch rather than quietly passing.
     */
    @Override
    public boolean equals(final Object o)
    {
        return o instanceof BodyChecksum other && other.recorded == recorded && other.value == value;
    }

    @Override
    public int hashCode()
    {
        return recorded ? Long.hashCode(value) : 0;
    }

    @Override
    public String toString()
    {
        return recorded ? Long.toString(value) : "not recorded";
    }
}
