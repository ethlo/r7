package com.ethlo.r7.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.Fingerprint;
import com.ethlo.r7.util.TestFingerprints;

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
                JournalPolicy.of(requestLevel, JournalLevel.FULL),
                JournalSecurity.SAFE_REQUEST_HEADERS, JournalSecurity.SAFE_RESPONSE_HEADERS,
                QueryParameterNameSet.NONE, TestFingerprints.FINGERPRINT);

        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("Host", "example.com");

        // The order the pipeline drives it in: request at ingress, response body while it
        // streams, the rest from the completion listener.
        journal.clientRequest(requestLevel, "r1", latin1("GET / HTTP/1.1"), headers, InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.responseBody("r1", latin1("hello "));
        journal.responseBody("r1", latin1("world"));
        journal.upstreamRequest(requestLevel, "r1", latin1("GET / HTTP/1.1"), headers, null);
        journal.upstreamResponse(JournalLevel.FULL, "r1", 200, latin1("HTTP/1.1 200 OK"), headers, true);
        journal.clientResponse(JournalLevel.FULL, "r1", 200, latin1("HTTP/1.1 200 OK"), headers, null);
        journal.endExchange("r1", new FastGatewayAttributes(), 1, 2, 200, 0, 0, 0, 11, 0, 0, 0, BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED, false, false);

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
        final Fingerprint fingerprint = TestFingerprints.FINGERPRINT;
        final StatefulJournal journal = new StatefulJournal(recording(new ArrayList<>(), lines),
                JournalPolicy.of(level, level),
                JournalSecurity.SAFE_REQUEST_HEADERS, JournalSecurity.SAFE_RESPONSE_HEADERS,
                QueryParameterNameSet.of(List.of("page"), false), fingerprint);

        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        journal.clientRequest(level, "r1", latin1("GET /items?page=2&api_key=s3cret HTTP/1.1"), headers, InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.upstreamRequest(level, "r1", latin1("GET /v1/items?page=2&api_key=s3cret&added=x HTTP/1.1"), headers, null);
        journal.endExchange("r1", new FastGatewayAttributes(), 1, 2, 200, 0, 0, 0, 0, 0, 0, 0, BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED, false, false);

        assertThat(lines).containsExactly(
                "GET /items?page=2&api_key=" + fingerprint.fingerprint("s3cret") + " HTTP/1.1",
                "GET /v1/items?page=2&api_key=" + fingerprint.fingerprint("s3cret") + "&added=" + fingerprint.fingerprint("x") + " HTTP/1.1");
    }

    @Test
    void aRequestLineWithoutAQueryIsJournaledAsIs()
    {
        final List<String> lines = new ArrayList<>();
        final StatefulJournal journal = new StatefulJournal(recording(new ArrayList<>(), lines),
                JournalPolicy.of(JournalLevel.METADATA, JournalLevel.METADATA),
                JournalSecurity.SAFE_REQUEST_HEADERS, JournalSecurity.SAFE_RESPONSE_HEADERS,
                QueryParameterNameSet.NONE, TestFingerprints.FINGERPRINT);

        journal.clientRequest(JournalLevel.METADATA, "r1", latin1("GET /items HTTP/1.1"), new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.endExchange("r1", new FastGatewayAttributes(), 1, 2, 200, 0, 0, 0, 0, 0, 0, 0, BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED, false, false);

        assertThat(lines).containsExactly("GET /items HTTP/1.1");
    }

    /**
     * A body exactly at the cap was journaled whole, so it is not flagged; one byte over, the
     * journal keeps the cap's worth, its checksum covers that prefix, and the end record says
     * it stopped short. The caller's buffers are left as they were: they are what streams on.
     */
    @ParameterizedTest
    @EnumSource(value = BodyKind.class)
    void aBodyIsCappedAndFlaggedOnlyWhenItExceedsTruncateBodyAt(final BodyKind kind)
    {
        final Capture exact = this.capture(kind, 8, "0123", "4567");
        assertThat(exact.body).isEqualTo("01234567");
        assertThat(exact.truncated).isFalse();
        assertThat(exact.checksum).isEqualTo(crc32c("01234567"));

        final Capture over = this.capture(kind, 8, "0123", "45678");
        assertThat(over.body).isEqualTo("01234567");
        assertThat(over.truncated).isTrue();
        assertThat(over.checksum).isEqualTo(crc32c("01234567"));

        final Capture after = this.capture(kind, 4, "0123", "4", "5678");
        assertThat(after.body).as("fragments after the cap was reached are not journaled").isEqualTo("0123");
        assertThat(after.truncated).isTrue();
    }

    @Test
    void withoutTruncateBodyAtTheWholeBodyIsJournaled()
    {
        final Capture whole = this.capture(BodyKind.RESPONSE, JournalPolicy.NO_CAP, "0123", "45678");
        assertThat(whole.body).isEqualTo("012345678");
        assertThat(whole.truncated).isFalse();
    }

    private enum BodyKind
    {
        REQUEST, RESPONSE
    }

    private static final class Capture
    {
        private final StringBuilder bodyBuilder = new StringBuilder();
        private String body;
        private boolean truncated;
        private BodyChecksum checksum;
    }

    private Capture capture(final BodyKind kind, final long truncateAt, final String... fragments)
    {
        final Capture capture = new Capture();
        final Journal delegate = new RecordingJournal(new ArrayList<>(), new ArrayList<>())
        {
            @Override
            public int requestBody(final String reqId, final ByteBuffer data)
            {
                capture.bodyBuilder.append(latin1(data));
                return data.remaining();
            }

            @Override
            public int responseBody(final String reqId, final ByteBuffer data)
            {
                capture.bodyBuilder.append(latin1(data));
                return data.remaining();
            }

            @Override
            public int endExchange(final String reqId, final GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, final int statusCode, final long requestHeaderBytes, final long requestBodyBytes, final long responseHeaderBytes, final long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final BodyChecksum requestChecksum, final BodyChecksum responseChecksum, final boolean requestBodyTruncated, final boolean responseBodyTruncated)
            {
                capture.truncated = kind == BodyKind.REQUEST ? requestBodyTruncated : responseBodyTruncated;
                capture.checksum = kind == BodyKind.REQUEST ? requestChecksum : responseChecksum;
                return 1;
            }
        };
        final JournalPolicy policy = new JournalPolicy(JournalLevel.FULL, JournalLevel.FULL,
                kind == BodyKind.REQUEST ? truncateAt : JournalPolicy.NO_CAP,
                kind == BodyKind.RESPONSE ? truncateAt : JournalPolicy.NO_CAP,
                List.of(), List.of());
        final StatefulJournal journal = new StatefulJournal(delegate, policy,
                JournalSecurity.SAFE_REQUEST_HEADERS, JournalSecurity.SAFE_RESPONSE_HEADERS,
                QueryParameterNameSet.NONE, TestFingerprints.FINGERPRINT);

        journal.clientRequest(JournalLevel.FULL, "r1", latin1("POST / HTTP/1.1"), new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        for (final String fragment : fragments)
        {
            final ByteBuffer sent = latin1(fragment);
            if (kind == BodyKind.REQUEST)
            {
                journal.requestBody("r1", sent);
            }
            else
            {
                journal.responseBody("r1", sent);
            }
            assertThat(sent.position()).as("the buffer being sent on is not moved").isZero();
            assertThat(sent.remaining()).isEqualTo(fragment.length());
        }
        journal.endExchange("r1", new FastGatewayAttributes(), 1, 2, 200, 0, 0, 0, 0, 0, 0, 0, BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED, false, false);
        capture.body = capture.bodyBuilder.toString();
        return capture;
    }

    private static BodyChecksum crc32c(final String body)
    {
        final CRC32C crc = new CRC32C();
        crc.update(body.getBytes(StandardCharsets.ISO_8859_1));
        return BodyChecksum.of(crc);
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
        return new RecordingJournal(events, requestLines);
    }

    private static class RecordingJournal implements Journal
    {
        private final List<String> events;
        private final List<String> requestLines;

        RecordingJournal(final List<String> events, final List<String> requestLines)
        {
            this.events = events;
            this.requestLines = requestLines;
        }

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
        public int upstreamResponse(final JournalLevel level, final String reqId, final int status, final ByteBuffer startLine, final GatewayHeaders headers, final boolean relayed)
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
        public int endExchange(final String reqId, final GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, final int statusCode, final long requestHeaderBytes, final long requestBodyBytes, final long responseHeaderBytes, final long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final BodyChecksum requestChecksum, final BodyChecksum responseChecksum, final boolean requestBodyTruncated, final boolean responseBodyTruncated)
        {
            events.add("endExchange");
            return 1;
        }

        @Override
        public void close()
        {
        }
    }
}
