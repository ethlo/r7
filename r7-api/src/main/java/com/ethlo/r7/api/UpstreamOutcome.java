package com.ethlo.r7.api;

/**
 * How an exchange fared with its upstream, for a filter that judges the upstream's health once
 * the exchange is over. Not the status the client saw: a filter can answer in the upstream's
 * place, and a response that began well can still break.
 *
 * @param kind      what happened
 * @param headNanos how long after r7 started the upstream exchange the response head arrived, in
 *                  nanoseconds; {@code -1} when no upstream sent one
 */
public record UpstreamOutcome(Kind kind, long headNanos)
{
    public static final UpstreamOutcome NOT_ASKED = new UpstreamOutcome(Kind.NOT_ASKED, -1);
    public static final UpstreamOutcome NO_RESPONSE = new UpstreamOutcome(Kind.NO_RESPONSE, -1);

    public enum Kind
    {
        /**
         * No upstream was asked, or the request was refused as the client's doing: a filter
         * answered, r7 served a file, a request was malformed or too large.
         */
        NOT_ASKED,

        /**
         * The upstream sent a response head and its body, if any, was read to the end. The status
         * is {@link CompletedGatewayExchange#upstreamResponse()}'s.
         */
        ANSWERED,

        /**
         * An upstream was asked and sent no valid response head: a refused or timed out connect, a
         * timeout waiting for the head, a protocol error. The client got r7's own 5xx.
         */
        NO_RESPONSE,

        /**
         * The upstream sent a head, then its body timed out or broke its framing.
         */
        BODY_FAILED,

        /**
         * The upstream sent a head, and the client went away before the response was delivered, so
         * how the body would have ended is not known.
         */
        CLIENT_ABORTED
    }
}
