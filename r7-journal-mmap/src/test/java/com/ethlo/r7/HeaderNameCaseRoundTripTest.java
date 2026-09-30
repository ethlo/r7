package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.GatewayHeaders;
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
 * A tailer consumer looks headers up by name, and it cannot know the casing the client or the
 * upstream happened to send. Header names are case-insensitive on the wire, so they must be in
 * what the journal hands back too - in the sets written in full and in the ones rebuilt from a
 * delta - while iteration still shows the casing that was written.
 */
class HeaderNameCaseRoundTripTest
{
    @Test
    void consumerFindsHeadersInAnyCasing(@TempDir final Path dir) throws Exception
    {
        final MutableFastGatewayHeaders clientRequest = new MutableFastGatewayHeaders();
        clientRequest.add("Content-Type", "application/json");
        clientRequest.add("X-Trace", "t1");
        clientRequest.add("x-trace", "t2");

        final MutableFastGatewayHeaders upstreamRequest = new MutableFastGatewayHeaders();
        clientRequest.forEach(upstreamRequest::add);
        upstreamRequest.add("X-Forwarded-For", "127.0.0.1");

        final MutableFastGatewayHeaders upstreamResponse = new MutableFastGatewayHeaders();
        upstreamResponse.add("ETag", "\"v1\"");

        final MutableFastGatewayHeaders clientResponse = new MutableFastGatewayHeaders();
        upstreamResponse.forEach(clientResponse::add);
        clientResponse.add("X-R7-Route", "r1");

        final R7fJournalProvider provider = new R7fJournalProvider(dir, 0, 64L * 1024 * 1024, false);
        try (final R7fJournal journal = new R7fJournal(provider))
        {
            final InetAddress client = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            final String id = "case-1";
            journal.clientRequest(JournalLevel.HEADERS, id, ascii("GET / HTTP/1.1"), clientRequest, client, IpSource.SOCKET);
            journal.upstreamRequest(JournalLevel.HEADERS, id, ascii("GET / HTTP/1.1"), upstreamRequest, clientRequest);
            journal.upstreamResponse(JournalLevel.HEADERS, id, 200, ascii("HTTP/1.1 200 OK"), upstreamResponse);
            journal.clientResponse(JournalLevel.HEADERS, id, 200, ascii("HTTP/1.1 200 OK"), clientResponse, upstreamResponse);
            journal.endExchange(id, new FastGatewayAttributes(), 1L, 2L, 200,
                    0, 0, 0, 0, 1L, 1L, 2L,
                    BodyChecksum.NOT_RECORDED, BodyChecksum.NOT_RECORDED);
        }

        final AtomicReference<JournalExchange> read = new AtomicReference<>();
        new R7Tailer(dir, read::set).runTick();
        final JournalExchange exchange = read.get();
        assertThat(exchange).isNotNull();

        final GatewayHeaders readClientRequest = exchange.getClientRequestHeaders();
        assertThat(readClientRequest.getFirst("content-type")).isEqualTo("application/json");
        assertThat(readClientRequest.contains("CONTENT-TYPE")).isTrue();
        assertThat(values(readClientRequest.getAll("X-TRACE"))).containsExactly("t1", "t2");
        assertThat(entries(readClientRequest)).containsExactly(
                "Content-Type=application/json", "X-Trace=t1", "x-trace=t2");

        // Rebuilt from a delta against the client request.
        final GatewayHeaders readUpstreamRequest = exchange.getUpstreamRequestHeaders();
        assertThat(readUpstreamRequest.getFirst("x-forwarded-for")).isEqualTo("127.0.0.1");
        assertThat(readUpstreamRequest.getFirst("CONTENT-type")).isEqualTo("application/json");

        assertThat(exchange.getUpstreamResponseHeaders().getFirst("etag")).isEqualTo("\"v1\"");

        // Rebuilt from a delta against the upstream response.
        final GatewayHeaders readClientResponse = exchange.getClientResponseHeaders();
        assertThat(readClientResponse.getFirst("x-r7-route")).isEqualTo("r1");
        assertThat(readClientResponse.getFirst("ETAG")).isEqualTo("\"v1\"");
    }

    private static ByteBuffer ascii(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static List<String> values(final Iterable<String> iterable)
    {
        final List<String> out = new ArrayList<>();
        iterable.forEach(out::add);
        return out;
    }

    private static List<String> entries(final GatewayHeaders headers)
    {
        final List<String> out = new ArrayList<>();
        headers.forEach((name, value) -> out.add(name + "=" + value));
        return out;
    }
}
