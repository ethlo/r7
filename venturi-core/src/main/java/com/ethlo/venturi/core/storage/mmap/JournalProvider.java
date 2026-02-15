package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class JournalProvider
{
    private final Path tempDir;
    private final long journalSize;
    private final int shardId; // Fixed ID for this shard, not dynamic threadId
    private final AtomicInteger rotationCount = new AtomicInteger(0);

    private Journal activeJournal;

    public JournalProvider(Path tempDir, int shardId, long journalSize)
    {
        this.tempDir = tempDir;
        this.shardId = shardId;
        this.journalSize = journalSize;
        this.activeJournal = createNewJournal();
    }

    /**
     * This is now called within a synchronized block in ShardedMmapWriter,
     * so we don't need additional locking here.
     */
    public synchronized Journal getActiveJournal()
    {
        // If the current buffer is 95% full, rotate to a new file
        if (!activeJournal.hasSpace((int) (journalSize * 0.05)))
        {
            rotate();
        }
        return activeJournal;
    }

    private void rotate()
    {
        Journal journalToClose = activeJournal;
        Path oldPath = journalToClose.getPath();
        Path readyPath = oldPath.resolveSibling(oldPath.getFileName() + ".tmp");

        try
        {
            // 1. Swap the reference first to a new journal
            // This ensures other threads immediately start using the new one
            this.activeJournal = createNewJournal();

            // 2. Now move the old one
            Files.move(oldPath, readyPath);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Critical: Rotation failed for shard " + shardId, e);
        }
    }

    private Journal createNewJournal()
    {
        // Filename now reflects the shard ID, making it easy to track in the sidecar
        String name = String.format("io-%d-%d-%d.raw",
                shardId,
                System.currentTimeMillis(),
                rotationCount.getAndIncrement()
        );

        try
        {
            return new Journal(tempDir.resolve(name), journalSize);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to initialize journal shard " + shardId, e);
        }
    }
}