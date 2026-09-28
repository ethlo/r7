package com.ethlo.r7.undertow;

import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;

/**
 * Accepts a request {@code Transfer-Encoding} only in the one form every HTTP/1.1 parser reads
 * the same way: absent, or exactly {@code chunked}.
 * <p>
 * Undertow accepts a list such as {@code chunked, identity} and the proxy copies the header to
 * the upstream verbatim. RFC 9112 §6.3 requires a server to reject a request whose final coding
 * is not {@code chunked}; a backend that reads such a list differently from the gateway frames
 * the body differently too, and the difference is where request smuggling lives. No client has
 * a reason to send anything but {@code chunked}, so nothing else is let through to be argued
 * about downstream.
 */
public final class TransferEncodingGuard
{
    private TransferEncodingGuard()
    {
    }

    public static boolean isAcceptable(final HeaderMap headers)
    {
        final HeaderValues values = headers.get(Headers.TRANSFER_ENCODING);
        if (values == null || values.isEmpty())
        {
            return true;
        }
        return values.size() == 1 && Headers.CHUNKED.toString().equalsIgnoreCase(values.getFirst().strip());
    }
}
