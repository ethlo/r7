package com.ethlo.r7;

/**
 * {@link TailerRestartTest} with compression on, where each journal in it is one batch
 * (FORMAT.md 4.4). An open exchange then starts inside a batch, and a refused entry stalls the
 * tailer inside one, so the replay has to start at a batch's offset with entries before the
 * exchange to skip, and stop at a checkpoint that is a batch's offset and a sequence within it.
 */
class TailerRestartBatchTest extends TailerRestartTest
{
    @Override
    int compressionLevel()
    {
        return 1;
    }
}
