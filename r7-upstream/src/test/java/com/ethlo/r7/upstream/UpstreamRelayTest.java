package com.ethlo.r7.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.util.FastGatewayHeaders;

/**
 * A request an HTTP/2 client can express but HTTP/1.1 cannot must not reach the upstream as an
 * HTTP/1.1 request that means something else: the downgrade that request smuggling through an
 * h2 front end rides on. Whatever the server in front let through, the relay refuses it with
 * 400, and the upstream receives nothing that could be read as a second request.
 */
class UpstreamRelayTest
{
    private ServerSocket upstreamSocket;
    private CompletableFuture<byte[]> received;
    private HttpUpstream upstream;

    @BeforeEach
    void start() throws IOException
    {
        this.upstreamSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.received = CompletableFuture.supplyAsync(() ->
        {
            try (Socket socket = this.upstreamSocket.accept())
            {
                socket.setSoTimeout(5_000);
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                socket.getInputStream().transferTo(bytes);
                return bytes.toByteArray();
            }
            catch (final IOException e)
            {
                return new byte[0];
            }
        });
        this.upstream = new HttpUpstream(UpstreamOptions.defaults());
        this.upstream.onTargetUp(URI.create("http://127.0.0.1:" + this.upstreamSocket.getLocalPort()));
    }

    @AfterEach
    void stop() throws IOException
    {
        this.upstreamSocket.close();
    }

    @Test
    void aBodyLongerThanItsContentLengthIsRefusedAndTheExcessNeverSent() throws Exception
    {
        final FakeExchange exchange = new FakeExchange("POST", "hello" + "GET /admin HTTP/1.1\r\nHost: x\r\n\r\n");
        exchange.headers.add("Content-Length", "5");
        assertRefused(exchange);
        assertThat(upstreamReceived()).doesNotContain("/admin");
    }

    @Test
    void aBodyShorterThanItsContentLengthIsRefused() throws Exception
    {
        final FakeExchange exchange = new FakeExchange("POST", "hello");
        exchange.headers.add("Content-Length", "10");
        assertRefused(exchange);
    }

    @Test
    void aHeaderValueWithALineBreakIsRefusedBeforeAnythingIsSent() throws Exception
    {
        final FakeExchange exchange = new FakeExchange("GET", "");
        exchange.headers.add("X-Note", "a\r\nX-Injected: 1");
        assertRefused(exchange);
        assertThat(upstreamReceived()).isEmpty();
    }

    @Test
    void aHeaderNameThatIsNotATokenIsRefused() throws Exception
    {
        final FakeExchange exchange = new FakeExchange("GET", "");
        exchange.headers.add("X Bad", "1");
        assertRefused(exchange);
        assertThat(upstreamReceived()).isEmpty();
    }

    @Test
    void aTargetWithAControlCharacterIsRefused() throws Exception
    {
        final FakeExchange exchange = new FakeExchange("GET", "");
        exchange.target = "/a\r\nX-Injected: 1";
        assertRefused(exchange);
        assertThat(upstreamReceived()).isEmpty();
    }

    @Test
    void anInvalidContentLengthIsRefused() throws Exception
    {
        final FakeExchange exchange = new FakeExchange("POST", "hello");
        exchange.headers.add("Content-Length", "5, 6");
        assertRefused(exchange);
        assertThat(upstreamReceived()).isEmpty();
    }

    @Test
    void connectingNeverOutlastsMaxRequestTime() throws Exception
    {
        // An https target that accepts and then says nothing: the TLS handshake waits on it,
        // bounded by the connect timeout, which here is far longer than max_request_time
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
        {
            final UpstreamOptions d = UpstreamOptions.defaults();
            final HttpUpstream slow = new HttpUpstream(new UpstreamOptions(d.readTimeout(), Duration.ofSeconds(30), d.idleTtl(), d.maxHeadBytes(),
                    d.maxHeaderCount(), d.maxConnectionsPerTarget(), d.maxQueuePerTarget(), Duration.ofMillis(300), null));
            slow.onTargetUp(URI.create("https://127.0.0.1:" + silent.getLocalPort()));

            final long start = System.nanoTime();
            assertThatThrownBy(() -> UpstreamRelay.relay(slow, new FakeExchange("GET", ""), "test"))
                    .isInstanceOfSatisfying(ProxyFailure.class, f -> assertThat(f.status()).isEqualTo(504));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    private void assertRefused(final FakeExchange exchange)
    {
        assertThatThrownBy(() -> UpstreamRelay.relay(this.upstream, exchange, "test"))
                .isInstanceOfSatisfying(ProxyFailure.class, f -> assertThat(f.status()).isEqualTo(400));
    }

    private String upstreamReceived() throws Exception
    {
        // The relay closed its connection, or never opened one: unblock the accept either way,
        // with a connection that ends at once.
        new Socket(InetAddress.getLoopbackAddress(), this.upstreamSocket.getLocalPort()).close();
        return new String(this.received.get(5, TimeUnit.SECONDS), StandardCharsets.ISO_8859_1);
    }

    /**
     * Headers as a server copies them off the wire, unvalidated - which is what reaches the relay
     * when the server in front let something through.
     */
    private static final class RawHeaders extends FastGatewayHeaders
    {
        void add(final String name, final String value)
        {
            addInternal(name, value);
        }
    }

    private static final class FakeExchange implements ProxiedExchange
    {
        private final String method;
        private final byte[] body;
        private final RawHeaders headers = new RawHeaders();
        private String target = "/";

        FakeExchange(final String method, final String body)
        {
            this.method = method;
            this.body = body.getBytes(StandardCharsets.ISO_8859_1);
            this.headers.add("Host", "gateway.example");
        }

        @Override
        public String forwardMethod()
        {
            return this.method;
        }

        @Override
        public String forwardTarget()
        {
            return this.target;
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
            return new ByteArrayInputStream(this.body);
        }

        @Override
        public MutableGatewayResponse clientResponse()
        {
            throw new AssertionError("A refused request must not reach the response");
        }

        @Override
        public OutputStream commit(final boolean body)
        {
            throw new AssertionError("A refused request must not reach the response");
        }

        @Override
        public void abortResponse()
        {
        }

        @Override
        public void attemptedTarget(final URI target)
        {
        }
    }
}
