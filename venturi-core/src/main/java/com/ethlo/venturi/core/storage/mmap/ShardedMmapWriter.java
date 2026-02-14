package com.ethlo.venturi.core.storage.mmap;

import java.nio.file.Path;

public class ShardedMmapWriter
{
    private final JournalProvider[] shards;
    private final int mask;

    public ShardedMmapWriter(Path rootDir, int shardCount, long shardSize)
    {
        this.shards = new JournalProvider[shardCount];
        this.mask = shardCount - 1; // Requirement: shardCount must be a power of 2
        for (int i = 0; i < shardCount; i++)
        {
            shards[i] = new JournalProvider(rootDir, i, shardSize);
        }
    }

    /**
     * Pins a request to a specific shard.
     * This is called ONCE per request at the beginning.
     */
    public Journal getJournalForRequest(CharSequence reqId)
    {
        // Use a bitwise AND for performance (requires shardCount to be power of 2)
        // Using Math.abs to handle negative hash codes safely
        int h = reqId.toString().hashCode();
        int shardIndex = Math.abs(h & mask);
        return shards[shardIndex].getActiveJournal();
    }

    /**
     * Flushes all active shards to disk.
     * Call this from your Undertow shutdown hook.
     */
    public void shutdown()
    {
        for (JournalProvider provider : shards)
        {
            // We get the current active journal for each shard and sync it
            Journal j = provider.getActiveJournal();
            synchronized (j)
            {
                j.force();
            }
        }
    }
}