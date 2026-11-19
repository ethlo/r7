package com.ethlo.r7.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * What the relay tells the exchange about whose fault a failure after the response head was:
 * only a failure reading the upstream is the upstream's, because a circuit breaker judges the
 * upstream by it.
 */
class UpstreamRelayOutcomeTest
{
    private static final String SHORT_BODY = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc";

    private static Exchange relay(final String response, final Exchange exchange) throws Exception
    {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
        {
            final Thread thread = Thread.ofVirtual().start(() ->
            {
                try (Socket socket = server.accept())
                {
                    socket.getInputStream().read(new byte[4096]);
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                }
                catch (final IOException ignored)
                {
                    // The relay went away.
                }
            });
            final HttpUpstream upstream = new HttpUpstream(UpstreamOptions.defaults());
            upstream.onTargetUp(URI.create("http://127.0.0.1:" + server.getLocalPort()));
            try
            {
                UpstreamRelay.relay(upstream, exchange, "test");
            }
            catch (final ProxyFailure failure)
            {
                exchange.failure = failure;
            }
            thread.join();
        }
        return exchange;
    }

    @Test
    void aBodyThatBreaksOffIsTheUpstreamsFailure() throws Exception
    {
        final Exchange exchange = relay(SHORT_BODY, new Exchange());
        assertThat(exchange.bodyFailed).isTrue();
        assertThat(exchange.clientAborted).isFalse();
        assertThat(exchange.failure).isNotNull();
    }

    @Test
    void aClientThatCanNotBeWrittenToIsNotTheUpstreamsFailure() throws Exception
    {
        final Exchange exchange = new Exchange();
        exchange.failWrites = true;
        relay("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc", exchange);
        assertThat(exchange.clientAborted).isTrue();
        assertThat(exchange.bodyFailed).isFalse();
        assertThat(exchange.failure).isNotNull();
        assertThat(exchange.failure.getCause()).as("not logged as an upstream error").isNull();
    }

    @Test
    void aFailureInR7sOwnTeeIsNotTheUpstreamsFailure() throws Exception
    {
        final Exchange exchange = new Exchange();
        exchange.failTee = true;
        assertThatThrownBy(() -> relay("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc", exchange)).isInstanceOf(IllegalStateException.class);
        assertThat(exchange.bodyFailed).isFalse();
        assertThat(exchange.clientAborted).isFalse();
    }

    private static final class Exchange implements ProxiedExchange
    {
        private final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        private final MutableGatewayResponse response = new MutableGatewayResponse()
        {
            private final MutableFastGatewayHeaders responseHeaders = new MutableFastGatewayHeaders();
            private int status;

            @Override
            public MutableGatewayHeaders headers()
            {
                return this.responseHeaders;
            }

            @Override
            public void status(final int status)
            {
                this.status = status;
            }

            @Override
            public int status()
            {
                return this.status;
            }
        };
        boolean bodyFailed;
        boolean clientAborted;
        boolean failWrites;
        boolean failTee;
        ProxyFailure failure;

        Exchange()
        {
            this.headers.add("Host", "gateway.example");
        }

        @Override
        public String forwardMethod()
        {
            return "GET";
        }

        @Override
        public String forwardTarget()
        {
            return "/";
        }

        @Override
        public GatewayHeaders forwardHeaders()
        {
            return this.headers;
        }

        @Override
        public String forwardedFor()
        {
            return "127.0.0.1";
        }

        @Override
        public String forwardedProto()
        {
            return "http";
        }

        @Override
        public InputStream openRequestBody()
        {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public MutableGatewayResponse clientResponse()
        {
            return this.response;
        }

        @Override
        public OutputStream commit(final boolean body)
        {
            return new OutputStream()
            {
                @Override
                public void write(final int b) throws IOException
                {
                    if (Exchange.this.failWrites)
                    {
                        throw new IOException("Broken pipe");
                    }
                }
            };
        }

        @Override
        public void onResponseBody(final byte[] buffer, final int offset, final int length)
        {
            if (this.failTee)
            {
                throw new IllegalStateException("journal tee failed");
            }
        }

        @Override
        public void abortResponse()
        {
        }

        @Override
        public void attemptedTarget(final URI target)
        {
        }

        @Override
        public void onClientAborted()
        {
            this.clientAborted = true;
        }

        @Override
        public void onUpstreamBodyFailed()
        {
            this.bodyFailed = true;
        }
    }
}
