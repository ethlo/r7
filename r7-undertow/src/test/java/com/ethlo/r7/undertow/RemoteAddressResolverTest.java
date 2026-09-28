package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.util.CidrRange;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;

/**
 * Exercises {@link RemoteAddressResolver} over a real loopback connection, since the
 * behaviour under test is entirely about what {@link HttpServerExchange#getSourceAddress()}
 * and request headers say for an actual TCP peer - a hand-built exchange would not exercise
 * the thing a spoofing attempt actually touches.
 */
class RemoteAddressResolverTest
{
    private Undertow server;
    private int port;
    private final AtomicReference<RemoteAddressResolver.RemoteInfo> lastResult = new AtomicReference<>();
    private RemoteAddressResolver resolver;

    @BeforeEach
    void start()
    {
        this.server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(exchange -> {
                    this.lastResult.set(this.resolver.resolve(exchange));
                    exchange.setStatusCode(204);
                    exchange.endExchange();
                })
                .build();
        this.server.start();
        this.port = ((java.net.InetSocketAddress) this.server.getListenerInfo().get(0).getAddress()).getPort();
    }

    @AfterEach
    void stop()
    {
        this.server.stop();
    }

    private RemoteAddressResolver.RemoteInfo send(final List<CidrRange> trustedProxies, final List<String> headers) throws IOException, InterruptedException
    {
        this.resolver = new RemoteAddressResolver(trustedProxies);
        final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/"));
        for (int i = 0; i < headers.size(); i += 2)
        {
            requestBuilder.header(headers.get(i), headers.get(i + 1));
        }
        HttpClient.newHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
        return this.lastResult.get();
    }

    @Test
    void anUntrustedPeerHasItsForwardedForHeaderIgnored() throws Exception
    {
        // No trusted proxies configured: even though loopback is where the request truly
        // comes from, nothing has vouched for whatever it claims about upstream hops.
        final RemoteAddressResolver.RemoteInfo result = send(List.of(), List.of("X-Forwarded-For", "203.0.113.9"));

        assertThat(result.source()).isEqualTo(IpSource.SOCKET);
        assertThat(result.address()).isEqualTo(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    void aTrustedPeerHasItsForwardedForHeaderHonored() throws Exception
    {
        final RemoteAddressResolver.RemoteInfo result = send(
                List.of(CidrRange.parse("127.0.0.1/32")), List.of("X-Forwarded-For", "203.0.113.9"));

        assertThat(result.source()).isEqualTo(IpSource.X_FORWARDED_FOR);
        assertThat(result.address()).isEqualTo(InetAddress.getByName("203.0.113.9"));
    }

    @Test
    void aMultiHopChainResistsLeftmostSpoofing() throws Exception
    {
        // "forged, actual-client" - only the rightmost entry was actually appended by our
        // trusted peer; the leftmost is an arbitrary claim the original sender could make up.
        final RemoteAddressResolver.RemoteInfo result = send(
                List.of(CidrRange.parse("127.0.0.1/32")),
                List.of("X-Forwarded-For", "198.51.100.1, 203.0.113.9"));

        assertThat(result.source()).isEqualTo(IpSource.X_FORWARDED_FOR);
        assertThat(result.address()).isEqualTo(InetAddress.getByName("203.0.113.9"));
    }

    @Test
    void aChainOfOnlyTrustedProxiesFallsBackToTheLeftmostEntry() throws Exception
    {
        final RemoteAddressResolver.RemoteInfo result = send(
                List.of(CidrRange.parse("127.0.0.1/32")),
                List.of("X-Forwarded-For", "203.0.113.9, 127.0.0.1"));

        assertThat(result.source()).isEqualTo(IpSource.X_FORWARDED_FOR);
        assertThat(result.address()).isEqualTo(InetAddress.getByName("203.0.113.9"));
    }

    @Test
    void aMalformedForwardedForEntryFallsBackSafelyRatherThanBeingSkipped() throws Exception
    {
        final RemoteAddressResolver.RemoteInfo result = send(
                List.of(CidrRange.parse("127.0.0.1/32")),
                List.of("X-Forwarded-For", "not-an-ip, 203.0.113.9"));

        assertThat(result.source()).isEqualTo(IpSource.SOCKET);
        assertThat(result.address()).isEqualTo(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    void aTrustedPeerHasItsRealIpHeaderHonoredWhenNoForwardedForIsPresent() throws Exception
    {
        final RemoteAddressResolver.RemoteInfo result = send(
                List.of(CidrRange.parse("127.0.0.1/32")), List.of("X-Real-IP", "203.0.113.9"));

        assertThat(result.source()).isEqualTo(IpSource.X_REAL_IP);
        assertThat(result.address()).isEqualTo(InetAddress.getByName("203.0.113.9"));
    }
}
