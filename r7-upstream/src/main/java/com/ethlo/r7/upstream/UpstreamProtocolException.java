package com.ethlo.r7.upstream;

import java.io.IOException;

/**
 * The upstream broke HTTP/1.1: a response head or body framing r7 will not guess at. Always
 * answered with 502, and the connection is never pooled again - after a framing error the next
 * byte on it could belong to anything, and reading on is how one response bleeds into the next.
 */
public final class UpstreamProtocolException extends IOException
{
    public UpstreamProtocolException(final String message)
    {
        super(message);
    }
}
