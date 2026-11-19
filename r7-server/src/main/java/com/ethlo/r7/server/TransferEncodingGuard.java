package com.ethlo.r7.server;

import java.util.Iterator;

import com.ethlo.r7.api.GatewayHeaders;

/**
 * Accepts a request {@code Transfer-Encoding} only in the one form every HTTP/1.1 parser reads
 * the same way: absent, or exactly {@code chunked}.
 * <p>
 * Parsers differ on lists: Undertow accepted {@code chunked, identity} and its proxy copied the
 * header to the upstream verbatim; Níma refuses both that and {@code gzip, chunked} itself, so on
 * Níma this guard is a second line that its parser keeps from ever firing. RFC 9112 §6.3 requires a server to reject a request whose final coding
 * is not {@code chunked}; a backend that reads such a list differently from the gateway frames
 * the body differently too, and the difference is where request smuggling lives. No client has
 * a reason to send anything but {@code chunked}, so nothing else is let through to be argued
 * about downstream.
 * <p>
 * Reads the request headers as the server presents them, which must match names ignoring case -
 * every server's request header view does. What a server has already rejected or normalised
 * before this runs is the server's business; whether the combination is safe is what the
 * wire-level security test kit checks, per server.
 */
public final class TransferEncodingGuard
{
    private static final String TRANSFER_ENCODING = "Transfer-Encoding";

    private TransferEncodingGuard()
    {
    }

    public static boolean isAcceptable(final GatewayHeaders headers)
    {
        final Iterator<String> values = headers.getAll(TRANSFER_ENCODING).iterator();
        if (!values.hasNext())
        {
            return true;
        }
        final String first = values.next();
        // A second field-line is a list in all but name, however its lines are spelled.
        return !values.hasNext() && "chunked".equalsIgnoreCase(first.strip());
    }
}
