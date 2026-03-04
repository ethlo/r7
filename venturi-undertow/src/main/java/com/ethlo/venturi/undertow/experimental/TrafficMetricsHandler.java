package com.ethlo.venturi.undertow.experimental;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;

import org.xnio.conduits.AbstractStreamSinkConduit;
import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.StreamSourceConduit;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.ConduitFactory;
import io.undertow.util.HeaderValues;
import io.undertow.util.StatusCodes;

public final class TrafficMetricsHandler implements HttpHandler
{
    public static final AttachmentKey<TrafficMetrics> SIZE_METRICS_KEY = AttachmentKey.create(TrafficMetrics.class);
    private final HttpHandler next;

    public TrafficMetricsHandler(final HttpHandler next)
    {
        this.next = next;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception
    {
        final AtomicLong requestBodyBytes = new AtomicLong(0);
        final AtomicLong responseBodyBytes = new AtomicLong(0);
        final AtomicLong responseHeaderBytes = new AtomicLong(0);

        final long requestHeaderBytes = calculateRequestHeaderSize(exchange);

        exchange.addResponseCommitListener((final HttpServerExchange ex) ->
        {
            responseHeaderBytes.set(calculateResponseHeaderSize(ex));
        });

        exchange.putAttachment(SIZE_METRICS_KEY, new TrafficMetrics()
                {
                    @Override
                    public long requestHeaderBytes()
                    {
                        return requestHeaderBytes;
                    }

                    @Override
                    public long requestBodyBytes()
                    {
                        return requestBodyBytes.get();
                    }

                    @Override
                    public long responseHeaderBytes()
                    {
                        return responseHeaderBytes.get();
                    }

                    @Override
                    public long responseBodyBytes()
                    {
                        return responseBodyBytes.get();
                    }

                    @Override
                    public long totalRequestBytes()
                    {
                        return requestHeaderBytes() + requestBodyBytes();
                    }

                    @Override
                    public long totalResponseBytes()
                    {
                        return responseHeaderBytes() + responseBodyBytes();
                    }
                }
        );

        exchange.addRequestWrapper((final ConduitFactory<StreamSourceConduit> factory, final HttpServerExchange ex) ->
        {
            return new CountingSourceConduit(factory.create(), requestBodyBytes);
        });

        exchange.addResponseWrapper((final ConduitFactory<StreamSinkConduit> factory, final HttpServerExchange ex) ->
        {
            return new CountingSinkConduit(factory.create(), responseBodyBytes);
        });

        this.next.handleRequest(exchange);
    }

    private long calculateRequestHeaderSize(final HttpServerExchange exchange)
    {
        long size = 0;

        size += exchange.getRequestMethod().toString().length();
        size += 1;
        size += exchange.getRequestURI().length();

        final String queryString = exchange.getQueryString();
        if (queryString != null && !queryString.isEmpty())
        {
            size += 1;
            size += queryString.length();
        }

        size += 1;
        size += exchange.getProtocol().toString().length();
        size += 2;

        for (final HeaderValues headerValues : exchange.getRequestHeaders())
        {
            final int nameLength = headerValues.getHeaderName().toString().length();
            for (final String value : headerValues)
            {
                size += nameLength;
                size += 2;
                size += value.length();
                size += 2;
            }
        }

        size += 2;
        return size;
    }

    private long calculateResponseHeaderSize(final HttpServerExchange exchange)
    {
        long size = 0;

        size += exchange.getProtocol().toString().length();
        size += 1;
        size += String.valueOf(exchange.getStatusCode()).length();
        size += 1;

        final String reasonPhrase = StatusCodes.getReason(exchange.getStatusCode());
        if (reasonPhrase != null)
        {
            size += reasonPhrase.length();
        }
        else
        {
            size += 2;
        }
        size += 2;

        for (final HeaderValues headerValues : exchange.getResponseHeaders())
        {
            final int nameLength = headerValues.getHeaderName().toString().length();
            for (final String value : headerValues)
            {
                size += nameLength;
                size += 2;
                size += value.length();
                size += 2;
            }
        }

        size += 2;
        return size;
    }

    public interface TrafficMetrics
    {
        long requestHeaderBytes();

        long requestBodyBytes();

        long responseHeaderBytes();

        long responseBodyBytes();

        long totalRequestBytes();

        long totalResponseBytes();
    }

    // --- Internal Conduit Wrappers ---

    private static final class CountingSourceConduit extends AbstractStreamSourceConduit<StreamSourceConduit>
    {
        private final AtomicLong counter;

        CountingSourceConduit(final StreamSourceConduit next, final AtomicLong counter)
        {
            super(next);
            this.counter = counter;
        }

        @Override
        public int read(final ByteBuffer dst) throws IOException
        {
            final int read = this.next.read(dst);
            if (read > 0)
            {
                this.counter.addAndGet(read);
            }
            return read;
        }

        @Override
        public long read(final ByteBuffer[] dsts, final int offs, final int len) throws IOException
        {
            final long read = this.next.read(dsts, offs, len);
            if (read > 0)
            {
                this.counter.addAndGet(read);
            }
            return read;
        }

        @Override
        public long transferTo(final long position, final long count, final FileChannel target) throws IOException
        {
            final long transferred = this.next.transferTo(position, count, target);
            if (transferred > 0)
            {
                this.counter.addAndGet(transferred);
            }
            return transferred;
        }
    }

    private static final class CountingSinkConduit extends AbstractStreamSinkConduit<StreamSinkConduit>
    {
        private final AtomicLong counter;

        CountingSinkConduit(final StreamSinkConduit next, final AtomicLong counter)
        {
            super(next);
            this.counter = counter;
        }

        @Override
        public int write(final ByteBuffer src) throws IOException
        {
            final int written = this.next.write(src);
            if (written > 0)
            {
                this.counter.addAndGet(written);
            }
            return written;
        }

        @Override
        public long write(final ByteBuffer[] srcs, final int offs, final int len) throws IOException
        {
            final long written = this.next.write(srcs, offs, len);
            if (written > 0)
            {
                this.counter.addAndGet(written);
            }
            return written;
        }
    }
}