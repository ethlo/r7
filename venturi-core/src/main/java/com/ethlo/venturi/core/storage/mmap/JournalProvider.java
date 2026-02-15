package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private synchronized void rotate()
    {
        Journal journalToClose = activeJournal;
        Path oldPath = journalToClose.getPath();

        // 2. Define the hand-off path (Tailer ONLY watches .raw)
        String newName = oldPath.getFileName().toString().replace(".active", ".raw");
        Path readyPath = oldPath.resolveSibling(newName);

        try
        {
            // 3. Swap the active reference first so new requests get a fresh buffer
            this.activeJournal = createNewJournal();

            // 4. Safely move the old one for the Tailer to find
            if (Files.exists(oldPath))
            {
                Files.move(oldPath, readyPath, StandardCopyOption.ATOMIC_MOVE);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException("Critical: Rotation failed for shard " + shardId, e);
        }
    }

    private Journal createNewJournal()
    {
        // Filename reflects the shard ID, making it easy to track in the sidecar
        final String name = String.format("shard-%d-%d-%d.active",
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