package com.ethlo.r7.undertow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.api.JournalExchange;

/**
 * The body checksums the gateway records in EndExchange.
 * <p>
 * These existed as fields long before anything computed them: the handler passed a
 * hardcoded {@code -1} for both directions, so the reader's verification — once it was
 * added — reported a mismatch on every exchange that carried a body. The bug was invisible
 * in review and in the test suite, and only showed up as a wall of ERROR lines under load.
 * <p>
 * So these tests assert the two things that make the field mean something: a direction that
 * journaled bytes reports the CRC32C of exactly those bytes, and a direction that journaled
 * nothing reports {@link JournalExchange#CHECKSUM_NOT_RECORDED} rather than the checksum of
 * an empty input. The reader cannot tell those apart on its own — every 32-bit value is a
 * legitimate CRC32C of something, the CRC32C of nothing included.
 */
class UndertowGatewayExchangeChecksumTest
{
    private static UndertowGatewayExchange newExchange()
    {
        // Only the checksum state is under test; the collaborators are never touched.
        return new UndertowGatewayExchange(null, "req-1", null, null, null, null, null, null);
    }

    private static ByteBuffer slice(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static int crc32c(final String s)
    {
        final CRC32C crc = new CRC32C();
        crc.update(s.getBytes(StandardCharsets.ISO_8859_1));
        return (int) crc.getValue();
    }

    @Test
    @DisplayName("A direction with no journaled body records the sentinel, not a checksum")
    void noBodyRecordsSentinel()
    {
        final UndertowGatewayExchange exchange = newExchange();

        assertEquals(JournalExchange.CHECKSUM_NOT_RECORDED, exchange.requestBodyChecksum());
        assertEquals(JournalExchange.CHECKSUM_NOT_RECORDED, exchange.responseBodyChecksum());
    }

    @Test
    @DisplayName("An empty body is not the same as no body")
    void emptyBodyIsDistinctFromNoBody()
    {
        final UndertowGatewayExchange exchange = newExchange();
        exchange.updateRequestBodyChecksum(slice(""));

        // CRC32C of the empty input is 0, which the reader must be able to tell apart from
        // "nothing was checksummed". A conduit that fires with an empty slice has still
        // seen the direction.
        assertEquals(0, exchange.requestBodyChecksum());
        assertNotEquals(JournalExchange.CHECKSUM_NOT_RECORDED, exchange.requestBodyChecksum());
    }

    @Test
    @DisplayName("Fragments accumulate into the checksum of the concatenation")
    void fragmentsAccumulate()
    {
        final UndertowGatewayExchange exchange = newExchange();
        exchange.updateRequestBodyChecksum(slice("hello, "));
        exchange.updateRequestBodyChecksum(slice("world"));

        // The journal stores fragments; the reader checksums what it reassembles. The two
        // only agree if the writer treats the body as one stream, not per-fragment.
        assertEquals(crc32c("hello, world"), exchange.requestBodyChecksum());
    }

    @Test
    @DisplayName("The two directions are independent")
    void directionsAreIndependent()
    {
        final UndertowGatewayExchange exchange = newExchange();
        exchange.updateResponseBodyChecksum(slice("body"));

        assertEquals(JournalExchange.CHECKSUM_NOT_RECORDED, exchange.requestBodyChecksum());
        assertEquals(crc32c("body"), exchange.responseBodyChecksum());
    }

    @Test
    @DisplayName("Checksumming leaves the slice intact for the journal")
    void sliceIsNotConsumed()
    {
        final UndertowGatewayExchange exchange = newExchange();
        final ByteBuffer buffer = slice("payload");
        final int before = buffer.remaining();

        exchange.updateRequestBodyChecksum(buffer);

        // The handler checksums before handing the slice to the journal, which consumes it.
        // If the checksum consumed it too, the journal would store an empty body — and the
        // checksum would then describe bytes that were never written.
        assertEquals(before, buffer.remaining());
        assertEquals(0, buffer.position());
    }

    @Test
    @DisplayName("Reading the checksum twice does not change it")
    void readIsIdempotent()
    {
        final UndertowGatewayExchange exchange = newExchange();
        exchange.updateRequestBodyChecksum(slice("payload"));

        assertEquals(exchange.requestBodyChecksum(), exchange.requestBodyChecksum());
    }
}
