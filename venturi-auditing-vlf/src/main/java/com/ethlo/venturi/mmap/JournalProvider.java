package com.ethlo.venturi.mmap;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class JournalProvider
{
    private final Path tempDir;
    private final int shardId;
    private final AtomicInteger rotationCount = new AtomicInteger(0);

    public JournalProvider(Path tempDir, int shardId)
    {
        this.tempDir = tempDir;
        this.shardId = shardId;
    }

    public Path getNextPath()
    {
        String name = String.format("shard-%d-%d-%d.active",
                shardId, System.currentTimeMillis(), rotationCount.incrementAndGet()
        );
        return tempDir.resolve(name);
    }

    public int getRotationCount()
    {
        return rotationCount.get();
    }
}