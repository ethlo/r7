package com.ethlo.r7.upstream;

/**
 * Why a relay did not complete, and what the client should be answered if its response has not
 * started yet. The server answers with {@link #status()} and {@link #message()}, then reports
 * {@link #getCause()} to the pipeline when there is one; a request body over the limit is the
 * client's doing and has none.
 */
public final class ProxyFailure extends Exception
{
    private final int status;

    ProxyFailure(final int status, final String message, final Exception cause)
    {
        super(message, cause, false, false);
        this.status = status;
    }

    public int status()
    {
        return this.status;
    }

    public String message()
    {
        return getMessage();
    }

    /**
     * The failure to report, or null when there is nothing to report.
     */
    @Override
    public Exception getCause()
    {
        return (Exception) super.getCause();
    }
}
