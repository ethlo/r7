package com.ethlo.venturi.mmap;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShardedMmapWriter
{
    private static final Logger logger = LoggerFactory.getLogger(ShardedMmapWriter.class);

    private final Journal[] shards;
    private final int mask;

    public ShardedMmapWriter(Path rootDir, int shardCount, long shardSize, long indexSize)
    {
        if ((shardCount & (shardCount - 1)) != 0)
        {
            throw new IllegalArgumentException("shardCount must be a power of 2");
        }

        this.shards = new Journal[shardCount];
        this.mask = shardCount - 1;

        for (int i = 0; i < shardCount; i++)
        {
            try
            {
                // Each shard gets its own provider to manage its specific file naming
                JournalProvider provider = new JournalProvider(rootDir, i);
                shards[i] = new Journal(provider, shardSize, indexSize);
            }
            catch (IOException e)
            {
                throw new RuntimeException("Failed to initialize shard " + i, e);
            }
        }
    }

    /**
     * Pins a request to a specific shard.
     * Because the Journal is now self-sufficient, we just return the shard.
     */
    public Journal getJournalForRequest(CharSequence reqId)
    {
        // reqId.hashCode() can be negative; using & Integer.MAX_VALUE or
        // ensuring the mask handles the sign bit is safer than Math.abs
        int h = reqId.hashCode();
        int shardIndex = (h ^ (h >>> 16)) & mask;
        return shards[shardIndex];
    }

    /**
     * Closes all shards.
     * In Java 21/Undertow, this ensures all .active files become .raw.
     */
    public void shutdown()
    {
        logger.info("Shutting down ShardedMmapWriter, closing {} shards", shards.length);
        for (Journal journal : shards)
        {
            try
            {
                // Synchronized inside Journal.close() handles thread safety
                journal.close();
            }
            catch (IOException e)
            {
                logger.error("Error closing journal shard", e);
            }
        }
    }
}