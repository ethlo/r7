package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;

class TransferEncodingGuardTest
{
    private static HeaderMap te(final String... values)
    {
        final HeaderMap map = new HeaderMap();
        for (final String value : values)
        {
            map.add(Headers.TRANSFER_ENCODING, value);
        }
        return map;
    }

    @Test
    void acceptsAbsentOrExactlyChunked()
    {
        assertThat(TransferEncodingGuard.isAcceptable(new HeaderMap())).isTrue();
        assertThat(TransferEncodingGuard.isAcceptable(te("chunked"))).isTrue();
        assertThat(TransferEncodingGuard.isAcceptable(te("Chunked "))).isTrue();
    }

    @Test
    void rejectsListsOtherCodingsAndRepeatedFieldLines()
    {
        assertThat(TransferEncodingGuard.isAcceptable(te("chunked, identity"))).isFalse();
        assertThat(TransferEncodingGuard.isAcceptable(te("gzip, chunked"))).isFalse();
        assertThat(TransferEncodingGuard.isAcceptable(te("identity"))).isFalse();
        assertThat(TransferEncodingGuard.isAcceptable(te("chunked", "chunked"))).isFalse();
        assertThat(TransferEncodingGuard.isAcceptable(te(""))).isFalse();
    }
}
