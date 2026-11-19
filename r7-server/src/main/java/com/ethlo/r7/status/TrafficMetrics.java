package com.ethlo.r7.status;

/**
 * Byte counts for one exchange as it crossed the wire, read by the journal's end record and by
 * route metrics. Counted by the server, which is the only layer that sees the bytes.
 */
public interface TrafficMetrics
{
    long requestHeaderBytes();

    long requestBodyBytes();

    long responseHeaderBytes();

    long responseBodyBytes();

    long totalRequestBytes();

    long totalResponseBytes();
}
