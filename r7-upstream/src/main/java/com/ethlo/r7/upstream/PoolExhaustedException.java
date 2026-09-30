package com.ethlo.r7.upstream;

import java.io.IOException;

/**
 * No in-flight slot to the target: its queue was full, or none came free before the request's
 * deadline. Answered 503, like Undertow's client when its pool is exhausted.
 */
final class PoolExhaustedException extends IOException
{
    PoolExhaustedException(final String message)
    {
        super(message);
    }
}
