package com.ethlo.r7.journal;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.ethlo.r7.util.Fingerprint;

/**
 * Applies the query parameter safe list to a journaled request line: a parameter whose name is
 * on the list keeps its value, every other value is replaced by its fingerprint. The same rule,
 * and the same fingerprint, as {@link RedactingHeaders} applies to header values.
 * <p>
 * Names stay readable, as header names do, and the query keeps its shape - order, repeats and
 * the raw spelling of each name - so a journal reader still sees which parameters a request
 * carried. Each occurrence of a repeated parameter is judged on its own; a safe name is safe
 * every time it occurs.
 * <p>
 * A name is matched after percent-decoding, with the decoding the server applies when filters
 * and predicates read parameters ({@code +} is a space, malformed escapes stay as sent). Matching
 * the raw spelling instead would let {@code p%61ge} past a list that names {@code page} only by
 * fingerprinting it - safe, but wrong - and would let an encoded spelling of a safe name read
 * differently from the parameter the upstream actually received.
 * <p>
 * Values are fingerprinted decoded, for the same reason: a filter re-encodes the query when it
 * changes it, so the client's and the upstream's spelling of one value can differ, and the
 * fingerprint should not.
 */
public final class RedactingQuery
{
    private RedactingQuery()
    {
    }

    /**
     * @param rawQuery the query as sent, without the {@code ?}
     * @return the query with every value outside {@code safeNames} fingerprinted
     */
    public static String redact(final String rawQuery, final QueryParameterNameSet safeNames, final Fingerprint fingerprint)
    {
        final StringBuilder out = new StringBuilder(rawQuery.length() + 16);
        int start = 0;
        while (true)
        {
            final int end = rawQuery.indexOf('&', start);
            redactPair(rawQuery, start, end < 0 ? rawQuery.length() : end, safeNames, fingerprint, out);
            if (end < 0)
            {
                return out.toString();
            }
            out.append('&');
            start = end + 1;
        }
    }

    private static void redactPair(final String query, final int start, final int end, final QueryParameterNameSet safeNames,
                                   final Fingerprint fingerprint, final StringBuilder out)
    {
        if (start == end)
        {
            // An empty pair ("a=1&&b=2") carries nothing to hide.
            return;
        }

        final int eq = query.indexOf('=', start);
        final boolean hasValue = eq >= 0 && eq < end;
        final String rawName = query.substring(start, hasValue ? eq : end);

        if (safeNames.contains(decode(rawName)))
        {
            out.append(query, start, end);
        }
        else if (!hasValue)
        {
            // A bare "?token" is a name and a value at once, and only the safe list could say it
            // is harmless. Read as a name it would be journaled verbatim; read as a value it is
            // hidden. When in doubt, hide.
            out.append(fingerprint.fingerprint(decode(rawName)));
        }
        else if (eq + 1 == end)
        {
            // "name=" has an empty value. A fingerprint of nothing would only look like a secret.
            out.append(query, start, end);
        }
        else
        {
            out.append(rawName).append('=').append(fingerprint.fingerprint(decode(query.substring(eq + 1, end))));
        }
    }

    /**
     * Redacts the query of a request line, {@code METHOD SP target SP PROTOCOL}.
     *
     * @return {@code line} itself, unread, when the target has no query; otherwise a new buffer
     */
    public static ByteBuffer redactRequestLine(final ByteBuffer line, final QueryParameterNameSet safeNames, final Fingerprint fingerprint)
    {
        final int from = line.position();
        final int to = line.limit();
        int question = -1;
        int lastSpace = -1;
        for (int i = from; i < to; i++)
        {
            final byte b = line.get(i);
            if (b == '?' && question < 0)
            {
                question = i;
            }
            else if (b == ' ')
            {
                lastSpace = i;
            }
        }
        // A '?' after the last space is not in the target, and a line without two spaces has no
        // protocol to tell the target's end by: either way there is no query here to redact.
        if (question < 0 || lastSpace < question)
        {
            return line;
        }

        // ISO-8859-1 maps each byte to one char and back, so everything outside the redacted
        // values is written back exactly as it was read.
        final String query = isoString(line, question + 1, lastSpace);
        final String redacted = redact(query, safeNames, fingerprint);
        final byte[] replacement = redacted.getBytes(StandardCharsets.ISO_8859_1);

        final ByteBuffer out = ByteBuffer.allocate((question + 1 - from) + replacement.length + (to - lastSpace));
        out.put(line.duplicate().position(from).limit(question + 1));
        out.put(replacement);
        out.put(line.duplicate().position(lastSpace).limit(to));
        return out.flip();
    }

    private static String isoString(final ByteBuffer buffer, final int from, final int to)
    {
        final byte[] bytes = new byte[to - from];
        buffer.get(from, bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String decode(final String s)
    {
        try
        {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        }
        catch (final IllegalArgumentException e)
        {
            return s;
        }
    }
}
