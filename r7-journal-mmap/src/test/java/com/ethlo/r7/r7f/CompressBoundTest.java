package com.ethlo.r7.r7f;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.github.luben.zstd.Zstd;

class CompressBoundTest
{
    /**
     * The writer sizes its scratch buffer and its block-fit decision on this bound; one below
     * zstd's own would let a stream overrun the space it was given.
     */
    @Test
    void compressBoundMatchesZstd()
    {
        for (int length = 0; length <= 300_000; length++)
        {
            assertThat(R7fJournal.compressBound(length)).as("length %d", length).isEqualTo(Zstd.compressBound(length));
        }
        for (final int length : new int[]{1 << 20, 16 << 20, 256 << 20, Integer.MAX_VALUE >> 2})
        {
            assertThat(R7fJournal.compressBound(length)).as("length %d", length).isEqualTo(Zstd.compressBound(length));
        }
    }
}
