package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalExchange;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * Whether the upstream's head was relayed decides what the exchange's response body is: the
 * upstream's, or r7's own answer in its place. A tailer cannot work that out from the statuses,
 * which a response filter may have changed, so it has to survive the journal.
 */
class UpstreamResponseRelayedRoundTripTest
{
    @Test
    void relayedSurvivesTheJournal(@TempDir final Path dir) throws Exception
    {
        final R7fJournalProvider provider = new R7fJournalProvider(dir, 0, 64L * 1024 * 1024, false);
        try (final R7fJournal journal = new R7fJournal(provider))
        {
            write(journal, "relayed", "HTTP/1.1 200 OK", 200, true);
            write(journal, "refused", "HTTP/1.1 101 Switching Protocols", 502, false);
        }

        final Map<String, JournalExchange> read = new HashMap<>();
        new R7Tailer(dir, e -> read.put(e.getRequestId(), e)).runTick();

        assertThat(read.get("relayed").isUpstreamResponseRelayed()).isTrue();
        assertThat(read.get("refused").isUpstreamResponseRelayed()).isFalse();
        assertThat(read.get("refused").getUpstreamResponseStartLine()).isEqualTo("HTTP/1.1 101 Switching Protocols");
    }

    private static void write(final R7fJournal journal, final String id, final String upstreamLine, final int clientStatus, final boolean relayed) throws Exception
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("X-A", "1");
        journal.clientRequest(JournalLevel.HEADERS, id, ascii("GET / HTTP/1.1"), headers, InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.upstreamRequest(JournalLevel.HEADERS, id, ascii("GET / HTTP/1.1"), headers, headers);
        journal.upstreamResponse(JournalLevel.HEADERS, id, 200, ascii(upstreamLine), headers, relayed);
        journal.clientResponse(JournalLevel.HEADERS, id, clientStatus, ascii("HTTP/1.1 " + clientStatus + " X"), headers, headers);
        journal.endExchange(id, new FastGatewayAttributes(), 1L, 2L, clientStatus,
                0, 0, 0, 0, 1L, 1L, 2L,
                BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
    }

    private static ByteBuffer ascii(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }
}
