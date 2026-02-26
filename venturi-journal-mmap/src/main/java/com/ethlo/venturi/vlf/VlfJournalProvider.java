package com.ethlo.venturi.vlf;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class VlfJournalProvider
{
    private final Path tempDir;
    private final int shardId;
    private final AtomicInteger rotationCount = new AtomicInteger(0);

    public VlfJournalProvider(Path tempDir, int shardId)
    {
        this.tempDir = tempDir;
        this.shardId = shardId;
    }

    public Path getNextPath()
    {
        String name = String.format("shard-%d-%d-%d" + VlfConstants.ACTIVE_FILE_EXTENSION,
                shardId, System.currentTimeMillis(), rotationCount.incrementAndGet()
        );
        return tempDir.resolve(name);
    }

    public int getRotationCount()
    {
        return rotationCount.get();
    }
}