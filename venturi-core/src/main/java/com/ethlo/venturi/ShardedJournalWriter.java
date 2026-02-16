package com.ethlo.venturi;

import java.util.function.IntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.auditing.api.Journal;

public final class ShardedJournalWriter<T extends com.ethlo.venturi.auditing.api.Journal>
{
    private static final Logger logger = LoggerFactory.getLogger(ShardedJournalWriter.class);

    private final T[] shards;
    private final int mask;

    @SuppressWarnings("unchecked")
    public ShardedJournalWriter(int shardCount, IntFunction<T> shardFactory)
    {
        if ((shardCount & (shardCount - 1)) != 0)
        {
            throw new IllegalArgumentException("shardCount must be a power of 2");
        }

        this.shards = (T[]) new Journal[shardCount];
        this.mask = shardCount - 1;

        for (int i = 0; i < shardCount; i++)
        {
            // The factory handles implementation details (VLF, Mmap, etc.)
            shards[i] = shardFactory.apply(i);
        }
    }

    public T getJournal(CharSequence reqId)
    {
        final int h = reqId.hashCode();
        final int shardIndex = (h ^ (h >>> 16)) & mask;
        return shards[shardIndex];
    }

    public void shutdown()
    {
        logger.info("Shutting down ShardedJournalWriter, closing {} shards", shards.length);
        for (T journal : shards)
        {
            try
            {
                journal.close();
            }
            catch (Exception e)
            {
                logger.error("Error closing journal shard", e);
            }
        }
    }
}