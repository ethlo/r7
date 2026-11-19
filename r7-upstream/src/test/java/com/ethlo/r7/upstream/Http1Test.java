package com.ethlo.r7.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class Http1Test
{
    // --- Chunk sizes ------------------------------------------------------------------------------

    @Test
    void chunkSizesAreHexWithOptionalExtensions() throws Exception
    {
        assertThat(Http1.parseChunkSize("0")).isZero();
        assertThat(Http1.parseChunkSize("5")).isEqualTo(5);
        assertThat(Http1.parseChunkSize("1f")).isEqualTo(31);
        assertThat(Http1.parseChunkSize("1F")).isEqualTo(31);
        assertThat(Http1.parseChunkSize("a;name=value")).isEqualTo(10);
        assertThat(Http1.parseChunkSize("a ;name")).isEqualTo(10);
        assertThat(Http1.parseChunkSize("fffffffffffffff")).isEqualTo(0xfffffffffffffffL);
    }

    /**
     * Each is read as some size by a lenient parser, and as a different size (or an error) by
     * another: the disagreement a smuggled response rides on.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "+5", "-1", "0x5", " 5", "5x", "5 x", "٣", "ffffffffffffffff", "1ffffffffffffffff", ";5"})
    void chunkSizesAnyParserCouldReadDifferentlyAreRefused(final String line)
    {
        assertThatThrownBy(() -> Http1.parseChunkSize(line)).isInstanceOf(UpstreamProtocolException.class);
    }

    // --- Content-Length -----------------------------------------------------------------------------

    @Test
    void repeatedEqualContentLengthsMerge() throws Exception
    {
        assertThat(Http1.mergeContentLength(-1, "5")).isEqualTo(5);
        assertThat(Http1.mergeContentLength(-1, "5, 5")).isEqualTo(5);
        assertThat(Http1.mergeContentLength(5, "5")).isEqualTo(5);
        assertThat(Http1.mergeContentLength(-1, "0")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "+5", "-5", "5,", "5, 6", "0x5", "5 5", "1234567890123456789", "٥"})
    void contentLengthsThatAreNotOneDecimalAreRefused(final String value)
    {
        assertThatThrownBy(() -> Http1.mergeContentLength(-1, value)).isInstanceOf(UpstreamProtocolException.class);
    }

    @Test
    void aContentLengthDifferentFromAnEarlierOneIsRefused()
    {
        assertThatThrownBy(() -> Http1.mergeContentLength(5, "6")).isInstanceOf(UpstreamProtocolException.class);
    }

    // --- Status line ------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1 200 OK", "HTTP/1.1 200 ", "HTTP/1.1 200", "HTTP/1.0 404 Not Found", "HTTP/1.1 599 x y z"})
    void validStatusLines(final String line) throws Exception
    {
        final Http1.ResponseHead head = new Http1.ResponseHead();
        Http1.parseStatusLine(line, head);
        assertThat(head.status).isBetween(200, 599);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP/1.1  200 OK", "HTTP/2 200 OK", "HTTP/1.2 200 OK", "HTTP/1.1 20 OK", "HTTP/1.1 2000 OK", "http/1.1 200 OK",
            "HTTP/1.1 099 x", "ICY 200 OK", "HTTP/1.1 +20 OK"})
    void malformedStatusLinesAreRefused(final String line)
    {
        assertThatThrownBy(() -> Http1.parseStatusLine(line, new Http1.ResponseHead())).isInstanceOf(UpstreamProtocolException.class);
    }

    // --- Response heads ------------------------------------------------------------------------------------

    @Test
    void aPlainHeadIsPersistentAndKeepsItsHeaders() throws Exception
    {
        final Http1.ResponseHead head = parse("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nX-A: 1\r\nSet-Cookie: a\r\nSet-Cookie: b\r\n\r\n");
        assertThat(head.status).isEqualTo(200);
        assertThat(head.contentLength).isEqualTo(2);
        assertThat(head.close).isFalse();
        assertThat(head.relayed).containsExactly("X-A", "1", "Set-Cookie", "a", "Set-Cookie", "b");
    }

    @Test
    void chunkedWithContentLengthIsChunkedAndCloses() throws Exception
    {
        final Http1.ResponseHead head = parse("HTTP/1.1 200 OK\r\nContent-Length: 9\r\nTransfer-Encoding: chunked\r\n\r\n");
        assertThat(head.chunked).isTrue();
        assertThat(head.contentLength).isEqualTo(-1);
        assertThat(head.close).isTrue();
    }

    @Test
    void chunkedAloneStaysPersistent() throws Exception
    {
        final Http1.ResponseHead head = parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: Chunked\r\n\r\n");
        assertThat(head.chunked).isTrue();
        assertThat(head.close).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "gzip, chunked", "chunked, chunked", "chunked, gzip", "identity"})
    void transferCodingsOtherThanALoneChunkedAreRefused(final String codings)
    {
        assertThatThrownBy(() -> parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: " + codings + "\r\n\r\n")).isInstanceOf(UpstreamProtocolException.class);
    }

    @Test
    void connectionTokensAreReadAsAListAndTheirHeadersStripped() throws Exception
    {
        final Http1.ResponseHead head = parse("HTTP/1.1 200 OK\r\nConnection: keep-alive, X-Hop, CLOSE\r\nx-hop: 1\r\nX-Keep: 2\r\nContent-Length: 0\r\n\r\n");
        assertThat(head.close).isTrue();
        assertThat(head.relayed).containsExactly("X-Keep", "2");
    }

    @Test
    void http10IsPersistentOnlyWithKeepAlive() throws Exception
    {
        assertThat(parse("HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n").close).isTrue();
        assertThat(parse("HTTP/1.0 200 OK\r\nConnection: Keep-Alive\r\nContent-Length: 0\r\n\r\n").close).isFalse();
    }

    @Test
    void interimResponsesAreSkipped() throws Exception
    {
        final Http1.ResponseHead head = parse("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\nLink: </a>\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertThat(head.status).isEqualTo(200);
        assertThat(head.relayed).isEmpty();
    }

    @Test
    void anEndlessStreamOfInterimResponsesIsRefused()
    {
        assertThatThrownBy(() -> parse("HTTP/1.1 100 Continue\r\n\r\n".repeat(50))).isInstanceOf(UpstreamProtocolException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "X-A: 1\r\n folded",            // obs-fold
            "Content-Length : 2",           // whitespace before the colon
            "X A: 1",                        // space in the name
            ": no-name",
            "no-colon",
            "X-A: a\u0000b",                // NUL in the value
            "X-A: a\rb",                     // bare CR in the value
    })
    void malformedHeaderLinesAreRefused(final String line)
    {
        assertThatThrownBy(() -> parse("HTTP/1.1 200 OK\r\n" + line + "\r\nContent-Length: 0\r\n\r\n")).isInstanceOf(UpstreamProtocolException.class);
    }

    @Test
    void theHeadIsBoundedInBytesAndInCount()
    {
        assertThatThrownBy(() -> parse("HTTP/1.1 200 OK\r\nX-Long: " + "a".repeat(2000) + "\r\n\r\n", 1024, 100))
                .isInstanceOf(UpstreamProtocolException.class);
        assertThatThrownBy(() -> parse("HTTP/1.1 200 OK\r\n" + "X: 1\r\n".repeat(101) + "\r\n", 64 * 1024, 100))
                .isInstanceOf(UpstreamProtocolException.class);
        // Many short lines add up against the byte budget too.
        assertThatThrownBy(() -> parse("HTTP/1.1 200 OK\r\n" + "X-Header: 1234567890\r\n".repeat(90) + "\r\n", 1024, 100))
                .isInstanceOf(UpstreamProtocolException.class);
    }

    @Test
    void aConnectionClosedBeforeAnyResponseIsNull() throws Exception
    {
        assertThat(parse("")).isNull();
    }

    @Test
    void aConnectionClosedInsideAHeadIsRefused()
    {
        assertThatThrownBy(() -> parse("HTTP/1.1 200 OK\r\nX-A: 1\r\n")).isInstanceOf(UpstreamProtocolException.class);
    }

    // --- Request side ---------------------------------------------------------------------------------------

    @Test
    void theForwardedPortIsTheHostHeadersOrTheSchemesDefault()
    {
        assertThat(Http1.portOf("example.com:8443", "https")).isEqualTo(8443);
        assertThat(Http1.portOf("example.com", "https")).isEqualTo(443);
        assertThat(Http1.portOf("example.com", "http")).isEqualTo(80);
        assertThat(Http1.portOf(null, "http")).isEqualTo(80);
        assertThat(Http1.portOf("[::1]:9000", "http")).isEqualTo(9000);
        assertThat(Http1.portOf("[::1]", "http")).isEqualTo(80);
        assertThat(Http1.portOf("example.com:", "http")).isEqualTo(80);
        assertThat(Http1.portOf("example.com:99999", "http")).isEqualTo(80);
        assertThat(Http1.portOf("example.com:8x", "http")).isEqualTo(80);
    }

    @Test
    void theForwardedServerIsTheHostHeaderWithoutItsPort()
    {
        assertThat(Http1.hostNameOf("example.com:8443")).isEqualTo("example.com");
        assertThat(Http1.hostNameOf("example.com")).isEqualTo("example.com");
        assertThat(Http1.hostNameOf("[::1]:9000")).isEqualTo("[::1]");
        assertThat(Http1.hostNameOf("[::1]")).isEqualTo("[::1]");
        assertThat(Http1.hostNameOf(null)).isEqualTo("localhost");
    }

    @Test
    void forwardedFieldsMustBeExpressibleInHttp11()
    {
        assertThat(Http1.isToken("X-Request-Id")).isTrue();
        assertThat(Http1.isToken("X Bad")).isFalse();
        assertThat(Http1.isToken("")).isFalse();
        assertThat(Http1.isToken("X:Bad")).isFalse();
        assertThat(Http1.isFieldValue("a\tb c")).isTrue();
        assertThat(Http1.isFieldValue("caf\u00e9")).isTrue();
        assertThat(Http1.isFieldValue("a\r\nb")).isFalse();
        assertThat(Http1.isFieldValue("a\nb")).isFalse();
        assertThat(Http1.isFieldValue("a\u0000b")).isFalse();
        assertThat(Http1.isFieldValue("a\u007fb")).isFalse();
        assertThat(Http1.isFieldValue("\u20ac")).isFalse();
    }

    @Test
    void onlyMethodsThatAreSafeToRepeatAreIdempotent()
    {
        assertThat(Http1.isIdempotent("GET")).isTrue();
        assertThat(Http1.isIdempotent("PUT")).isTrue();
        assertThat(Http1.isIdempotent("DELETE")).isTrue();
        assertThat(Http1.isIdempotent("POST")).isFalse();
        assertThat(Http1.isIdempotent("PATCH")).isFalse();
        assertThat(Http1.isIdempotent("get")).isFalse();
    }

    private static Http1.ResponseHead parse(final String bytes) throws IOException
    {
        return parse(bytes, 64 * 1024, 200);
    }

    private static Http1.ResponseHead parse(final String bytes, final int maxHeadBytes, final int maxHeaderCount) throws IOException
    {
        final HttpUpstream.LineReader reader = new HttpUpstream.LineReader(new ByteArrayInputStream(bytes.getBytes(StandardCharsets.ISO_8859_1)));
        return Http1.readResponseHead(reader, maxHeadBytes, maxHeaderCount);
    }
}
