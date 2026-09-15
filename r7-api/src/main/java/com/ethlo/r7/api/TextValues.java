package com.ethlo.r7.api;

/**
 * The text a header or attribute is allowed to carry.
 * <p>
 * HTTP/1.1 header field values are ISO-8859-1 (latin-1) on the wire, and the journal
 * stores them as the same bytes, so a value from a client always round-trips unchanged.
 * A value set <em>programmatically</em> by a filter is a Java string and can contain
 * anything — and anything outside latin-1 cannot be represented. The journal writer
 * truncates each character to its low byte, so {@code U+2013} would be stored as
 * {@code 0x13} and the original would be unrecoverable.
 * <p>
 * The gateway therefore refuses such a value at the point the filter sets it, rather than
 * silently storing a different one. Rejecting here rather than at journal-write time is
 * deliberate:
 * <ul>
 *   <li>the error names the filter that produced the value, instead of surfacing much
 *       later as mojibake in an audit record;</li>
 *   <li>it is consistent with the gateway being fail-closed — a request whose metadata
 *       cannot be recorded faithfully is not quietly recorded wrongly;</li>
 *   <li>it keeps the check out of the journal write path.</li>
 * </ul>
 * A filter that needs to carry arbitrary Unicode through an attribute should encode it
 * explicitly, for example base64 or percent-encoding, and decode it in the consumer. The
 * gateway will not do that silently, because a re-encoded value is indistinguishable from
 * a corrupted one to whoever reads the audit trail.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9110#section-5.5">RFC 9110 §5.5</a>
 */
public final class TextValues
{
    /**
     * Highest code point that can be stored. Everything up to and including this is one
     * byte in ISO-8859-1.
     */
    public static final char MAX_STORABLE = 0x00FF;

    private TextValues()
    {
    }

    /**
     * Index reported when the value is absent altogether rather than containing a
     * character that cannot be stored.
     */
    public static final int ABSENT = -1;

    /**
     * Index of the first character that cannot be stored, or {@code -1} if the whole
     * string can be. A {@code null} value reports {@code -1} here; use
     * {@link #requireStorable(String, String)} to reject it.
     * <p>
     * A plain scan with no allocation. Header values are short, and this runs on the
     * request path only when a filter actually sets one.
     */
    public static int firstUnstorableIndex(final String value)
    {
        if (value == null)
        {
            return -1;
        }
        for (int i = 0, len = value.length(); i < len; i++)
        {
            if (value.charAt(i) > MAX_STORABLE)
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether the value can be stored. {@code null} cannot.
     */
    public static boolean isStorable(final String value)
    {
        return value != null && firstUnstorableIndex(value) < 0;
    }

    /**
     * Returns the value unchanged, or throws if it cannot be represented.
     *
     * @param field the header or attribute name, used in the error message
     * @throws InvalidTextValueException if the value is {@code null}, or if any character
     *                                   is above {@link #MAX_STORABLE}
     */
    public static String requireStorable(final String field, final String value)
    {
        if (value == null)
        {
            // A null would reach the journal writer as a null and fail there, far from
            // the filter that set it. Refuse it at the same place as any other value the
            // gateway cannot record.
            throw new InvalidTextValueException(
                    "Cannot set '" + field + "' to null. Remove the entry instead if it should not be present.",
                    field, ABSENT);
        }

        final int index = firstUnstorableIndex(value);
        if (index < 0)
        {
            return value;
        }

        throw new InvalidTextValueException(String.format(
                "Cannot set '%s': character U+%04X at index %d is outside ISO-8859-1 and cannot be represented. "
                        + "Encode the value (for example base64) if it must carry arbitrary Unicode.",
                field, (int) value.charAt(index), index), field, index);
    }

    /**
     * As {@link #requireStorable(String, String)}, for the name itself.
     */
    public static String requireStorableName(final String name)
    {
        if (name == null)
        {
            throw new InvalidTextValueException("Header or attribute name must not be null.", null, ABSENT);
        }

        final int index = firstUnstorableIndex(name);
        if (index < 0)
        {
            return name;
        }

        throw new InvalidTextValueException(String.format(
                "Invalid name '%s': character U+%04X at index %d is outside ISO-8859-1.",
                name, (int) name.charAt(index), index), name, index);
    }
}
