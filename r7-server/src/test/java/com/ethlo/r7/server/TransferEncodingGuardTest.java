package com.ethlo.r7.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;


class TransferEncodingGuardTest
{
    private static TestHeaders te(final String... values)
    {
        final TestHeaders headers = new TestHeaders();
        for (final String value : values)
        {
            headers.add("Transfer-Encoding", value);
        }
        return headers;
    }

    @Test
    void acceptsAbsentOrExactlyChunked()
    {
        assertThat(TransferEncodingGuard.isAcceptable(new TestHeaders())).isTrue();
        assertThat(TransferEncodingGuard.isAcceptable(te("chunked"))).isTrue();
        assertThat(TransferEncodingGuard.isAcceptable(te("Chunked "))).isTrue();
    }

    @Test
    void readsTheHeaderInAnyCase()
    {
        final TestHeaders headers = TestHeaders.of("transfer-encoding", "chunked, identity");
        assertThat(TransferEncodingGuard.isAcceptable(headers)).isFalse();
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
