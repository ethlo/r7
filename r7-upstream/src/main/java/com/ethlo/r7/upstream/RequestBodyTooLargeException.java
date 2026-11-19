package com.ethlo.r7.upstream;

import java.io.IOException;

/**
 * Thrown by a server from {@link ProxiedExchange#onRequestBody} when the body crosses the route's
 * limit. An {@link IOException} so it leaves the relay's copy loop the way a failed read does,
 * closing the upstream connection mid-body; the relay answers it with 413 rather than 502.
 */
public final class RequestBodyTooLargeException extends IOException
{
    public RequestBodyTooLargeException()
    {
        super("Request body exceeds the route's limit");
    }
}
