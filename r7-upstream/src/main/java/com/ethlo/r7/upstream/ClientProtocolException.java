package com.ethlo.r7.upstream;

import java.io.IOException;

/**
 * The client's request cannot be forwarded as HTTP/1.1 as it stands: a header that is not a
 * token or carries a line break, or a body that does not match its Content-Length. Over
 * HTTP/1.1 the server's parser refuses these first; over HTTP/2 they can be expressed - a header
 * value with a CR LF in it, a DATA stream longer than content-length - and rewriting such a
 * request into HTTP/1.1 is how a request is smuggled into the upstream connection. Answered 400.
 */
final class ClientProtocolException extends IOException
{
    ClientProtocolException(final String message)
    {
        super(message);
    }
}
