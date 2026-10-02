package com.ethlo.r7.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.config.JournalDirectionConfig;
import com.ethlo.r7.config.RouteJournalConfig;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.FastGatewayAttributes;

class StatefulJournalTest
{
    /**
     * Invariant #2 from the writer's side: a body the reader cannot attach to a request is
     * discarded as an orphan, so the anchoring client request has to reach the journal first.
     * The response body is teed while the response is being sent, before the completion
     * listener journals the rest, which is exactly the window where a request level below FULL
     * used to leave it unanchored.
     */
    @ParameterizedTest
    @EnumSource(value = JournalLevel.class, names = {"NONE", "METADATA", "HEADERS", "FULL"})
    void aResponseBodyIsNeverJournaledAheadOfItsClientRequest(final JournalLevel requestLevel)
    {
        final List<String> events = new ArrayList<>();
        final StatefulJournal journal = new StatefulJournal(recording(events),
                new RouteJournalConfig(new JournalDirectionConfig(requestLevel, null), new JournalDirectionConfig(JournalLevel.FULL, null)),
                exchangeWithStatus(200));

        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("Host", "example.com");

        // The order the pipeline drives it in: request at ingress, response body while it
        // streams, the rest from the completion listener.
        journal.clientRequest(requestLevel, "r1", latin1("GET / HTTP/1.1"), headers, InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.responseBody("r1", latin1("hello "));
        journal.responseBody("r1", latin1("world"));
        journal.upstreamRequest(requestLevel, "r1", latin1("GET / HTTP/1.1"), headers, null);
        journal.upstreamResponse(JournalLevel.FULL, "r1", 200, latin1("HTTP/1.1 200 OK"), headers);
        journal.clientResponse(JournalLevel.FULL, "r1", 200, latin1("HTTP/1.1 200 OK"), headers, null);
        journal.endExchange("r1", new FastGatewayAttributes(), 1, 2, 200, 0, 0, 0, 11, 0, 0, 0, BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);

        assertThat(events.indexOf("clientRequest"))
                .as("events journaled: %s", events)
                .isNotNegative()
                .isLessThan(events.indexOf("responseBody"));
        assertThat(events).filteredOn("clientRequest"::equals).hasSize(1);
        assertThat(events).filteredOn("upstreamRequest"::equals).hasSize(1);
        assertThat(events).last().isEqualTo("endExchange");
    }

    /**
     * The query is part of the request line, which every level records, so its values are
     * redacted at METADATA too - not only at the levels that journal headers.
     */
    @ParameterizedTest
    @EnumSource(value = JournalLevel.class, names = {"METADATA", "HEADERS", "FULL"})
    void queryValuesInBothRequestLinesAreRedactedAtEveryLevel(final JournalLevel level)
    {
        final List<String> lines = new ArrayList<>();
        final HeaderFingerprint fingerprint = HeaderFingerprint.UNKEYED;
        final StatefulJournal journal = new StatefulJournal(recording(new ArrayList<>(), lines),
                new RouteJournalConfig(new JournalDirectionConfig(level, null), new JournalDirectionConfig(level, null)),
                exchangeWithStatus(200), JournalSecurity.SAFE_REQUEST_HEADERS, JournalSecurity.SAFE_RESPONSE_HEADERS,
                QueryParameterNameSet.of(List.of("page"), false), fingerprint);

        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        journal.clientRequest(level, "r1", latin1("GET /items?page=2&api_key=s3cret HTTP/1.1"), headers, InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.upstreamRequest(level, "r1", latin1("GET /v1/items?page=2&api_key=s3cret&added=x HTTP/1.1"), headers, null);
        journal.endExchange("r1", new FastGatewayAttributes(), 1, 2, 200, 0, 0, 0, 0, 0, 0, 0, BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);

        assertThat(lines).containsExactly(
                "GET /items?page=2&api_key=" + fingerprint.fingerprint("s3cret") + " HTTP/1.1",
                "GET /v1/items?page=2&api_key=" + fingerprint.fingerprint("s3cret") + "&added=" + fingerprint.fingerprint("x") + " HTTP/1.1");
    }

    @Test
    void aRequestLineWithoutAQueryIsJournaledAsIs()
    {
        final List<String> lines = new ArrayList<>();
        final StatefulJournal journal = new StatefulJournal(recording(new ArrayList<>(), lines),
                new RouteJournalConfig(new JournalDirectionConfig(JournalLevel.METADATA, null), new JournalDirectionConfig(JournalLevel.METADATA, null)),
                exchangeWithStatus(200));

        journal.clientRequest(JournalLevel.METADATA, "r1", latin1("GET /items HTTP/1.1"), new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.endExchange("r1", new FastGatewayAttributes(), 1, 2, 200, 0, 0, 0, 0, 0, 0, 0, BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);

        assertThat(lines).containsExactly("GET /items HTTP/1.1");
    }

    private static CompletedGatewayExchange exchangeWithStatus(final int status)
    {
        final GatewayResponse response = mock(GatewayResponse.class);
        when(response.status()).thenReturn(status);
        final CompletedGatewayExchange exchange = mock(CompletedGatewayExchange.class);
        when(exchange.clientResponse()).thenReturn(response);
        return exchange;
    }

    private static ByteBuffer latin1(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Journal recording(final List<String> events)
    {
        return recording(events, new ArrayList<>());
    }

    private static String latin1(final ByteBuffer buffer)
    {
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static Journal recording(final List<String> events, final List<String> requestLines)
    {
        return new Journal()
        {
            @Override
            public int clientRequest(final JournalLevel level, final String reqId, final ByteBuffer startLine, final GatewayHeaders headers, final InetAddress remoteAddress, final IpSource ipSource)
            {
                events.add("clientRequest");
                requestLines.add(latin1(startLine));
                return 1;
            }

            @Override
            public int upstreamRequest(final JournalLevel level, final String reqId, final ByteBuffer startLine, final GatewayHeaders headers, final GatewayHeaders base)
            {
                events.add("upstreamRequest");
                requestLines.add(latin1(startLine));
                return 1;
            }

            @Override
            public int upstreamResponse(final JournalLevel level, final String reqId, final int status, final ByteBuffer startLine, final GatewayHeaders headers)
            {
                events.add("upstreamResponse");
                return 1;
            }

            @Override
            public int clientResponse(final JournalLevel level, final String reqId, final int status, final ByteBuffer startLine, final GatewayHeaders headers, final GatewayHeaders base)
            {
                events.add("clientResponse");
                return 1;
            }

            @Override
            public int requestBody(final String reqId, final ByteBuffer data)
            {
                events.add("requestBody");
                return 1;
            }

            @Override
            public int responseBody(final String reqId, final ByteBuffer data)
            {
                events.add("responseBody");
                return 1;
            }

            @Override
            public int endExchange(final String reqId, final GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, final int statusCode, final long requestHeaderBytes, final long requestBodyBytes, final long responseHeaderBytes, final long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final BodyChecksum requestChecksum, final BodyChecksum responseChecksum)
            {
                events.add("endExchange");
                return 1;
            }

            @Override
            public void close()
            {
            }
        };
    }
}
