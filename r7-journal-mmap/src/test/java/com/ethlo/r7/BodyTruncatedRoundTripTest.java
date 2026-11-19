package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32C;

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
 * A capped body and a body that ended early hold the same bytes; only the writer knows which it
 * was, so the flags travel in the end record and must survive it.
 */
class BodyTruncatedRoundTripTest
{
    @Test
    void truncationFlagsSurviveTheJournal(@TempDir final Path dir) throws Exception
    {
        final R7fJournalProvider provider = new R7fJournalProvider(dir, 0, 64L * 1024 * 1024, false);
        try (final R7fJournal journal = new R7fJournal(provider))
        {
            write(journal, "whole", false, false);
            write(journal, "request-capped", true, false);
            write(journal, "response-capped", false, true);
        }

        final Map<String, JournalExchange> read = new HashMap<>();
        new R7Tailer(dir, e -> read.put(e.getRequestId(), e)).runTick();

        assertThat(read.get("whole").isRequestBodyTruncated()).isFalse();
        assertThat(read.get("whole").isResponseBodyTruncated()).isFalse();
        assertThat(read.get("request-capped").isRequestBodyTruncated()).isTrue();
        assertThat(read.get("request-capped").isResponseBodyTruncated()).isFalse();
        assertThat(read.get("response-capped").isRequestBodyTruncated()).isFalse();
        assertThat(read.get("response-capped").isResponseBodyTruncated()).isTrue();
        assertThat(read.get("response-capped").getJournaledResponseChecksum())
                .as("the checksum covers the journaled prefix, so the reader verifies it as intact")
                .isEqualTo(read.get("response-capped").getObservedResponseChecksum());
    }

    private static void write(final R7fJournal journal, final String id, final boolean requestTruncated, final boolean responseTruncated) throws Exception
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        journal.clientRequest(JournalLevel.FULL, id, ascii("POST / HTTP/1.1"), headers, InetAddress.getLoopbackAddress(), IpSource.SOCKET);
        journal.requestBody(id, ascii("prefix"));
        journal.clientResponse(JournalLevel.FULL, id, 200, ascii("HTTP/1.1 200 OK"), headers, null);
        journal.responseBody(id, ascii("head"));
        journal.endExchange(id, new FastGatewayAttributes(), 1L, 2L, 200,
                0, 1000, 0, 1000, -1L, -1L, -1L,
                crc32c("prefix"), crc32c("head"), requestTruncated, responseTruncated);
    }

    private static BodyChecksum crc32c(final String body)
    {
        final CRC32C crc = new CRC32C();
        crc.update(body.getBytes(StandardCharsets.US_ASCII));
        return BodyChecksum.of(crc);
    }

    private static ByteBuffer ascii(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }
}
