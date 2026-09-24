package com.ethlo.r7.warc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.ethlo.r7.api.GatewayHeaders;

/**
 * Builds the content block of a {@code request}/{@code response}/{@code revisit} WARC record:
 * an {@code application/http} message — start-line, header lines, a blank line, and (unless
 * omitted for a revisit) the entity-body.
 * <p>
 * Encoded as ISO-8859-1, matching the rest of the codebase's invariant that journal text
 * (header names/values, start lines) is ISO-8859-1 — see {@code TextValues}.
 */
final class HttpMessageBlock
{
    private HttpMessageBlock()
    {
    }

    static byte[] withBody(final String startLine, final GatewayHeaders headers, final List<ByteBuffer> body)
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        writeHeadPart(out, startLine, headers);
        if (body != null)
        {
            for (final ByteBuffer fragment : body)
            {
                final ByteBuffer dup = fragment.duplicate();
                final byte[] chunk = new byte[dup.remaining()];
                dup.get(chunk);
                out.writeBytes(chunk);
            }
        }
        return out.toByteArray();
    }

    /**
     * The head only, no entity-body — used for revisit records, and for records at a journal
     * level that never captured a body in the first place.
     */
    static byte[] headersOnly(final String startLine, final GatewayHeaders headers)
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        writeHeadPart(out, startLine, headers);
        return out.toByteArray();
    }

    private static void writeHeadPart(final ByteArrayOutputStream out, final String startLine, final GatewayHeaders headers)
    {
        writeLine(out, startLine != null ? startLine : "");
        if (headers != null)
        {
            headers.forEach((name, value) -> writeLine(out, name + ": " + value));
        }
        writeLine(out, "");
    }

    private static void writeLine(final ByteArrayOutputStream out, final String line)
    {
        out.writeBytes(line.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[]{'\r', '\n'});
    }
}
