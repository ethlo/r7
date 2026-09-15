package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fConstants;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.r7f.R7fRecoveryManager;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.FastGatewayHeaders;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * End-to-end life cycle of a journal: write, rotate, seal, recover, tail, verify.
 * <p>
 * The point of these tests is that every value written is compared to the value read
 * back. Counting exchanges only proves the framing survived; it says nothing about
 * whether the record is faithful, which is the only property that makes an audit log
 * worth keeping.
 */
class JournalLifecycleTest
{
    private static final int SEGMENT_SIZE = 128 * 1024;

    @TempDir
    Path journalDir;

    /**
     * A single exchange, written and then compared field by field after a full
     * write-seal-tail cycle.
     */
    @Test
    void singleExchangeRoundTripsEveryField() throws IOException
    {
        final String reqId = "req-round-trip";
        final String requestLine = "POST /api/orders?id=42 HTTP/1.1";
        final String responseLine = "HTTP/1.1 201 Created";
        final byte[] requestBody = "the request body".getBytes(StandardCharsets.UTF_8);
        final byte[] responseBody = "the response body, somewhat longer".getBytes(StandardCharsets.UTF_8);
        final InetAddress client = InetAddress.getByName("203.0.113.7");

        final MutableGatewayHeaders requestHeaders = new MutableFastGatewayHeaders();
        requestHeaders.set("Content-Type", "application/json");
        requestHeaders.set("X-Request-Id", reqId);

        final long clientStart = 1_700_000_000_000_000L;
        final long clientEnd = clientStart + 12_345L;
        final long proxyStart = clientStart + 100L;
        final long proxyFirstByte = proxyStart + 2_000L;
        final long proxyEnd = proxyFirstByte + 3_000L;

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            journal.clientRequest(JournalLevel.FULL, reqId, wrap(requestLine), requestHeaders, client, IpSource.SOCKET);
            journal.requestBody(reqId, ByteBuffer.wrap(requestBody));
            journal.clientResponse(JournalLevel.FULL, reqId, 201, wrap(responseLine), new FastGatewayHeaders());
            journal.responseBody(reqId, ByteBuffer.wrap(responseBody));
            journal.endExchange(reqId, new FastGatewayAttributes(),
                    clientStart, clientEnd, 201,
                    111L, requestBody.length, 222L, responseBody.length,
                    proxyStart, proxyFirstByte, proxyEnd,
                    0, 0);
        }

        final CollectingSink sink = tail();

        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        assertThat(sink.completed).containsOnlyKeys(reqId);

        final JournalExchange read = sink.completed.get(reqId);
        assertThat(read.getRequestId()).isEqualTo(reqId);
        assertThat(read.getClientRequestStartLine()).isEqualTo(requestLine);
        assertThat(read.getClientResponseStartLine()).isEqualTo(responseLine);
        assertThat(read.getClientRequestLevel()).isEqualTo(JournalLevel.FULL);
        assertThat(read.getStatus()).isEqualTo(201);
        assertThat(read.remoteAddress()).isEqualTo(client);
        assertThat(read.getRemoteAddressSource()).isEqualTo(IpSource.SOCKET);

        assertThat(read.getClientStartTs()).isEqualTo(clientStart);
        assertThat(read.getClientEndTs()).isEqualTo(clientEnd);
        assertThat(read.getProxyStartTs()).isEqualTo(proxyStart);
        assertThat(read.getProxyFirstByteReceivedTs()).isEqualTo(proxyFirstByte);
        assertThat(read.getProxyEndTs()).isEqualTo(proxyEnd);

        assertThat(read.getRequestHeaderBytes()).isEqualTo(111L);
        assertThat(read.getRequestBodyBytes()).isEqualTo(requestBody.length);
        assertThat(read.getResponseHeaderBytes()).isEqualTo(222L);
        assertThat(read.getResponseBodyBytes()).isEqualTo(responseBody.length);

        assertThat(CollectingSink.toMap(read.getClientRequestHeaders()))
                .containsEntry("Content-Type", "application/json")
                .containsEntry("X-Request-Id", reqId);

        assertThat(CollectingSink.concat(read.getRequestBodyFragments())).isEqualTo(requestBody);
        assertThat(CollectingSink.concat(read.getResponseBodyFragments())).isEqualTo(responseBody);
    }

    /**
     * Header values arrive as latin-1 bytes and must come back byte-identical. Decoding
     * them as UTF-8 or ASCII turns every byte above 127 into U+FFFD, silently rewriting
     * the record.
     */
    @Test
    void latin1HeaderValuesRoundTrip() throws IOException
    {
        final String reqId = "req-latin1";
        final String value = "Ærlig søknad - naïve café";
        final String startLine = "GET /vare/blåbærsyltetøy HTTP/1.1";

        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.set("X-Subject", value);

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            journal.clientRequest(JournalLevel.HEADERS, reqId, wrap(startLine), headers,
                    InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            endOf(journal, reqId, 200);
        }

        final CollectingSink sink = tail();

        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        final JournalExchange read = sink.completed.get(reqId);
        assertThat(read).isNotNull();
        assertThat(CollectingSink.toMap(read.getClientRequestHeaders())).containsEntry("X-Subject", value);
        assertThat(read.getClientRequestStartLine()).isEqualTo(startLine);
    }

    /**
     * Many exchanges across many forced rotations, with every body distinct, so that a
     * lost or misattributed fragment cannot pass unnoticed.
     */
    @Test
    void manyExchangesSurviveRotationIntact() throws IOException
    {
        final int exchangeCount = 400;
        final Random random = new Random(20260915L);
        final Map<String, byte[]> expectedBodies = new LinkedHashMap<>();

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            for (int i = 0; i < exchangeCount; i++)
            {
                final String reqId = "req-" + i;
                final byte[] body = new byte[1 + random.nextInt(4096)];
                random.nextBytes(body);
                expectedBodies.put(reqId, body);

                final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
                headers.set("X-Seq", Integer.toString(i));

                journal.clientRequest(JournalLevel.FULL, reqId, wrap("GET /item/" + i + " HTTP/1.1"), headers,
                        InetAddress.getLoopbackAddress(), IpSource.SOCKET);
                journal.requestBody(reqId, ByteBuffer.wrap(body));
                endOf(journal, reqId, 200 + (i % 3));
            }
        }

        // More than one segment must have been produced, or the test is not exercising
        // rotation at all.
        assertThat(sealedSegments()).hasSizeGreaterThan(1);

        final CollectingSink sink = tail();

        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        assertThat(sink.completed).hasSize(exchangeCount);

        expectedBodies.forEach((reqId, body) -> {
            final JournalExchange read = sink.completed.get(reqId);
            assertThat(read).as("exchange %s", reqId).isNotNull();
            assertThat(CollectingSink.concat(read.getRequestBodyFragments()))
                    .as("body of %s", reqId)
                    .isEqualTo(body);
            assertThat(CollectingSink.toMap(read.getClientRequestHeaders()))
                    .containsEntry("X-Seq", reqId.substring("req-".length()));
        });
    }

    /**
     * An exchange whose events straddle a segment boundary must still be reassembled.
     * This is the case that fails if the reader keeps slices of a buffer it later reuses.
     */
    @Test
    void exchangeSpanningSegmentsIsReassembled() throws IOException
    {
        final String reqId = "req-spanning";
        final byte[] chunk = new byte[8192];
        java.util.Arrays.fill(chunk, (byte) 'x');

        try (R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true)))
        {
            journal.clientRequest(JournalLevel.FULL, reqId, wrap("PUT /upload HTTP/1.1"), new MutableFastGatewayHeaders(),
                    InetAddress.getLoopbackAddress(), IpSource.SOCKET);

            // Enough body to push well past one segment, so the end lands in a later file.
            for (int i = 0; i < 40; i++)
            {
                journal.requestBody(reqId, ByteBuffer.wrap(chunk));
            }
            endOf(journal, reqId, 204);
        }

        assertThat(sealedSegments()).hasSizeGreaterThan(1);

        final CollectingSink sink = tail();

        assertThat(sink.isClean()).as("sink: %s", sink).isTrue();
        final JournalExchange read = sink.completed.get(reqId);
        assertThat(read).isNotNull();
        assertThat(read.getRequestBodyFragments()).hasSize(40);
        assertThat(CollectingSink.concat(read.getRequestBodyFragments())).hasSize(40 * chunk.length);
        assertThat(CollectingSink.concat(read.getRequestBodyFragments())).containsOnly((byte) 'x');
    }

    /**
     * A journal abandoned without close leaves an active segment behind. Recovery must
     * seal it, cut it back to the last intact entry, and lose nothing that was written.
     */
    @Test
    void recoverySealsActiveSegmentWithoutLoss() throws IOException
    {
        final int exchangeCount = 20;

        // Deliberately not closed: this is what an unclean stop leaves on disk.
        final R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true));
        for (int i = 0; i < exchangeCount; i++)
        {
            final String reqId = "crash-" + i;
            journal.clientRequest(JournalLevel.METADATA, reqId, wrap("GET /x/" + i + " HTTP/1.1"),
                    new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            endOf(journal, reqId, 200);
        }

        // The warmer keeps the next segment mapped and pre-allocated, so an unclean stop
        // normally leaves two active files behind: the one being written, and an untouched
        // spare that was never handed to the writer.
        final List<Path> activeBefore = segmentsWithSuffix(R7fConstants.ACTIVE_FILE_EXTENSION);
        assertThat(activeBefore).isNotEmpty();
        assertThat(activeBefore).allSatisfy(p ->
                assertThat(Files.size(p))
                        .as("segments are pre-allocated at full size before recovery")
                        .isEqualTo(SEGMENT_SIZE));

        final CollectingSink sink = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, sink);

        assertThat(segmentsWithSuffix(R7fConstants.ACTIVE_FILE_EXTENSION)).isEmpty();
        assertThat(segmentsWithSuffix(R7fConstants.CORRUPT_FILE_EXTENSION))
                .as("an untouched pre-allocated spare is not damage and must not be quarantined")
                .isEmpty();
        assertThat(sink.quarantined).isEmpty();
        assertThat(sink.truncatedSegments).hasSize(1);

        final List<Path> sealed = sealedSegments();
        assertThat(sealed).hasSize(1);
        assertThat(Files.size(sealed.get(0)))
                .as("recovery must cut the pre-allocated tail, not leave it in place")
                .isLessThan(SEGMENT_SIZE);
        assertThat(Files.size(sealed.get(0))).isGreaterThan(R7fConstants.PREAMBLE_SIZE);

        final CollectingSink tailed = tail();
        assertThat(tailed.isClean()).as("sink: %s", tailed).isTrue();
        assertThat(tailed.completed).hasSize(exchangeCount);
    }

    /**
     * Recovery must be repeatable: running it twice does the same thing as running it
     * once. A boot loop otherwise turns a recoverable segment into a moving target.
     */
    @Test
    void recoveryIsIdempotent() throws IOException
    {
        final R7fJournal journal = new R7fJournal(new R7fJournalProvider(journalDir, 0, SEGMENT_SIZE, true));
        for (int i = 0; i < 5; i++)
        {
            final String reqId = "idem-" + i;
            journal.clientRequest(JournalLevel.METADATA, reqId, wrap("GET /i HTTP/1.1"),
                    new MutableFastGatewayHeaders(), InetAddress.getLoopbackAddress(), IpSource.SOCKET);
            endOf(journal, reqId, 200);
        }

        R7fRecoveryManager.cleanAndRecover(journalDir, new CollectingSink());
        final Map<String, Long> afterFirst = segmentSizes();

        final CollectingSink second = new CollectingSink();
        R7fRecoveryManager.cleanAndRecover(journalDir, second);

        assertThat(segmentSizes()).isEqualTo(afterFirst);
        assertThat(second.truncatedSegments).as("nothing left to recover on the second pass").isEmpty();
    }

    /* ---------- helpers ---------- */

    private CollectingSink tail() throws IOException
    {
        final CollectingSink sink = new CollectingSink();
        // A large minAge keeps the tailer from deleting segments once it has read them,
        // so assertions can still inspect the files. A null minAge would delete them
        // immediately. maxAge is generous so nothing ages out mid-test.
        new R7Tailer(journalDir, Duration.ofHours(1), sink, sink,
                ReassemblyOptions.DEFAULTS.withMaxAge(Duration.ofHours(1))).runTick();
        return sink;
    }

    private static ByteBuffer wrap(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void endOf(final R7fJournal journal, final String reqId, final int status)
    {
        journal.endExchange(reqId, new FastGatewayAttributes(),
                1_700_000_000_000L, 1_700_000_000_123L, status,
                0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0, 0);
    }

    private List<Path> sealedSegments() throws IOException
    {
        return segmentsWithSuffix(R7fConstants.R7F_FILE_EXTENSION);
    }

    private List<Path> segmentsWithSuffix(final String suffix) throws IOException
    {
        try (Stream<Path> s = Files.list(journalDir))
        {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    private Map<String, Long> segmentSizes() throws IOException
    {
        final Map<String, Long> sizes = new LinkedHashMap<>();
        final List<Path> all = new ArrayList<>(sealedSegments());
        all.addAll(segmentsWithSuffix(R7fConstants.ACTIVE_FILE_EXTENSION));
        for (final Path p : all)
        {
            sizes.put(p.getFileName().toString(), Files.size(p));
        }
        return sizes;
    }

    /**
     * Compile-time reminder that the sink really is an {@link ExchangeCompletionListener};
     * if the interface gains an abstract method this fails here rather than in every test.
     */
    @SuppressWarnings("unused")
    private static final ExchangeCompletionListener TYPE_CHECK = new CollectingSink();
}
