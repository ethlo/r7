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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.core.proxy.ProxyPoolExhaustedException;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * What the client does besides relaying: moving on from a target that refuses, bounding a whole
 * exchange, bounding how many requests one target gets, and TLS.
 */
class HttpUpstreamClientTest
{
    private static final String OK = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";

    @TempDir
    Path dir;

    @Test
    void aTargetThatRefusesTheConnectionIsSkipped() throws Exception
    {
        try (Upstream live = Upstream.answering(OK))
        {
            final HttpUpstream upstream = new HttpUpstream(UpstreamOptions.defaults());
            upstream.onTargetUp(URI.create("http://127.0.0.1:" + deadPort()));
            upstream.onTargetUp(URI.create(live.url()));
            // Whichever the round robin starts with, both requests end on the live target.
            assertThat(relay(upstream).body()).isEqualTo("ok");
            assertThat(relay(upstream).body()).isEqualTo("ok");
        }
    }

    @Test
    void whenEveryTargetRefusesTheAnswerIs503() throws Exception
    {
        final HttpUpstream upstream = new HttpUpstream(UpstreamOptions.defaults());
        upstream.onTargetUp(URI.create("http://127.0.0.1:" + deadPort()));
        assertThatThrownBy(() -> relay(upstream)).isInstanceOfSatisfying(ProxyFailure.class, f -> assertThat(f.status()).isEqualTo(503));
    }

    @Test
    void anExchangePastMaxRequestTimeIsEndedWith504() throws Exception
    {
        // Answers nothing, for longer than the read timeout would allow for; only the request
        // deadline can end this.
        try (Upstream silent = Upstream.silent())
        {
            final HttpUpstream upstream = new HttpUpstream(UpstreamOptions.defaults().withLimits(10, 10, Duration.ofMillis(500)));
            upstream.onTargetUp(URI.create(silent.url()));
            final long start = System.nanoTime();
            assertThatThrownBy(() -> relay(upstream)).isInstanceOfSatisfying(ProxyFailure.class, f -> assertThat(f.status()).isEqualTo(504));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    void aTargetAtItsLimitWithAFullQueueIsAnswered503() throws Exception
    {
        final CountDownLatch release = new CountDownLatch(1);
        try (Upstream slow = Upstream.answeringAfter(OK, release))
        {
            final HttpUpstream upstream = new HttpUpstream(UpstreamOptions.defaults().withLimits(1, 0, Duration.ofSeconds(10)));
            upstream.onTargetUp(URI.create(slow.url()));
            final CompletableFuture<Captured> first = CompletableFuture.supplyAsync(() -> relayUnchecked(upstream));
            slow.awaitRequests(1);
            assertThatThrownBy(() -> relay(upstream)).isInstanceOfSatisfying(ProxyFailure.class, f ->
            {
                assertThat(f.status()).isEqualTo(503);
                assertThat(f.getCause()).isInstanceOf(ProxyPoolExhaustedException.class);
            });
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).body()).isEqualTo("ok");
        }
    }

    @Test
    void aTargetAtItsLimitQueuesWhenThereIsRoom() throws Exception
    {
        final CountDownLatch release = new CountDownLatch(1);
        try (Upstream slow = Upstream.answeringAfter(OK, release))
        {
            final HttpUpstream upstream = new HttpUpstream(UpstreamOptions.defaults().withLimits(1, 1, Duration.ofSeconds(10)));
            upstream.onTargetUp(URI.create(slow.url()));
            final CompletableFuture<Captured> first = CompletableFuture.supplyAsync(() -> relayUnchecked(upstream));
            slow.awaitRequests(1);
            final CompletableFuture<Captured> queued = CompletableFuture.supplyAsync(() -> relayUnchecked(upstream));
            Thread.sleep(200);
            assertThat(queued).isNotDone();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).body()).isEqualTo("ok");
            assertThat(queued.get(5, TimeUnit.SECONDS).body()).isEqualTo("ok");
        }
    }

    @Test
    void anHttpsTargetIsReachedWithItsNameVerified() throws Exception
    {
        final KeyStore keys = selfSigned("localhost");
        try (Upstream tls = Upstream.tls(OK, serverContext(keys)))
        {
            final HttpUpstream upstream = new HttpUpstream(UpstreamOptions.defaults().withSslContext(clientContext(keys)));
            upstream.onTargetUp(URI.create("https://localhost:" + tls.port()));
            final Captured response = relay(upstream);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
            assertThat(tls.lastRequest()).contains("Host: localhost:" + tls.port());
        }
    }

    @Test
    void anHttpsTargetWhoseCertificateNamesAnotherHostIsRefused() throws Exception
    {
        final KeyStore keys = selfSigned("other.example");
        try (Upstream tls = Upstream.tls(OK, serverContext(keys)))
        {
            final HttpUpstream upstream = new HttpUpstream(UpstreamOptions.defaults().withSslContext(clientContext(keys)));
            upstream.onTargetUp(URI.create("https://localhost:" + tls.port()));
            assertThatThrownBy(() -> relay(upstream)).isInstanceOfSatisfying(ProxyFailure.class, f -> assertThat(f.status()).isEqualTo(502));
        }
    }

    // ============================================================================================

    private KeyStore selfSigned(final String name) throws Exception
    {
        final Path store = this.dir.resolve(name + ".p12");
        final Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "upstream", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + name,
                "-ext", "SAN=dns:" + name, "-validity", "2", "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        final String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(keytool.waitFor()).as(output).isZero();
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store))
        {
            keys.load(in, "changeit".toCharArray());
        }
        return keys;
    }

    private static SSLContext serverContext(final KeyStore keys) throws Exception
    {
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "changeit".toCharArray());
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientContext(final KeyStore keys) throws Exception
    {
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keys);
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    private static int deadPort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        {
            return socket.getLocalPort();
        }
    }

    private static Captured relayUnchecked(final HttpUpstream upstream)
    {
        try
        {
            return relay(upstream);
        }
        catch (final ProxyFailure e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static Captured relay(final HttpUpstream upstream) throws ProxyFailure
    {
        final CapturingExchange exchange = new CapturingExchange();
        UpstreamRelay.relay(upstream, exchange, "test");
        return new Captured(exchange.response.status, exchange.body.toString(StandardCharsets.ISO_8859_1));
    }

    private record Captured(int status, String body)
    {
    }

    /**
     * A test upstream on a plain or TLS server socket, each connection answered by one script.
     */
    private static final class Upstream implements AutoCloseable
    {
        private final ServerSocket server;
        private final String response;
        private final CountDownLatch release;
        private final java.util.concurrent.Semaphore requests = new java.util.concurrent.Semaphore(0);
        private volatile String lastRequest;

        private Upstream(final ServerSocket server, final String response, final CountDownLatch release)
        {
            this.server = server;
            this.response = response;
            this.release = release;
            Thread.ofVirtual().start(this::accept);
        }

        static Upstream answering(final String response) throws IOException
        {
            return new Upstream(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()), response, null);
        }

        static Upstream answeringAfter(final String response, final CountDownLatch release) throws IOException
        {
            return new Upstream(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()), response, release);
        }

        static Upstream silent() throws IOException
        {
            return new Upstream(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()), null, null);
        }

        static Upstream tls(final String response, final SSLContext context) throws IOException
        {
            final SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
            return new Upstream(server, response, null);
        }

        int port()
        {
            return this.server.getLocalPort();
        }

        String url()
        {
            return "http://127.0.0.1:" + port();
        }

        String lastRequest()
        {
            return this.lastRequest;
        }

        void awaitRequests(final int n) throws InterruptedException
        {
            assertThat(this.requests.tryAcquire(n, 5, TimeUnit.SECONDS)).isTrue();
        }

        private void accept()
        {
            while (!this.server.isClosed())
            {
                try
                {
                    final Socket socket = this.server.accept();
                    Thread.ofVirtual().start(() -> serve(socket));
                }
                catch (final IOException e)
                {
                    return;
                }
            }
        }

        private void serve(final Socket socket)
        {
            try (socket)
            {
                final InputStream in = socket.getInputStream();
                final OutputStream out = socket.getOutputStream();
                while (true)
                {
                    final ByteArrayOutputStream head = new ByteArrayOutputStream();
                    int matched = 0;
                    int b;
                    while (matched < 4 && (b = in.read()) != -1)
                    {
                        head.write(b);
                        matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
                    }
                    if (matched < 4)
                    {
                        return;
                    }
                    this.lastRequest = head.toString(StandardCharsets.ISO_8859_1);
                    this.requests.release();
                    if (this.response == null)
                    {
                        Thread.sleep(60_000);
                        return;
                    }
                    if (this.release != null)
                    {
                        this.release.await(10, TimeUnit.SECONDS);
                    }
                    out.write(this.response.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
            }
            catch (final IOException | InterruptedException ignored)
            {
                // The client went away.
            }
        }

        @Override
        public void close() throws IOException
        {
            this.server.close();
        }
    }

    private static final class CapturingResponse implements MutableGatewayResponse
    {
        private final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        private int status;

        @Override
        public MutableGatewayHeaders headers()
        {
            return this.headers;
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
    }

    private static final class CapturingExchange implements ProxiedExchange
    {
        private final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        private final CapturingResponse response = new CapturingResponse();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        CapturingExchange()
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
            return body ? this.body : null;
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
