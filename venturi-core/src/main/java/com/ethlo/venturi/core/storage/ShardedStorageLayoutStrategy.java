package com.ethlo.venturi.core.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ShardedStorageLayoutStrategy implements StorageLayoutStrategy
{
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final Set<Path> verifiedDirs = ConcurrentHashMap.newKeySet();
    private volatile String currentDatePart;
    private volatile Path currentDateDir;

    @Override
    public Path resolveAndPrepare(Path baseDir, String id)
    {
        final String today = DATE_FORMATTER.format(LocalDate.now());
        if (!today.equals(currentDatePart))
        {
            currentDatePart = today;
            currentDateDir = baseDir.resolve(today);
            verifiedDirs.clear();
        }

        final int len = id.length();
        if (len < 6)
        {
            // Fallback for short IDs
            return currentDateDir.resolve("short");
        }

        // SCATTER STRATEGY:
        // We pick characters that change most frequently.
        // Usually, the end of an ID has more entropy than the start.
        final String s1 = id.substring(len - 2);         // Last 2 chars
        final String s2 = id.substring(len - 4, len - 2); // Previous 2 chars
        final String s3 = id.substring(len - 6, len - 4); // Previous 2 chars

        final Path shardDir = currentDateDir
                .resolve(s1)
                .resolve(s2)
                .resolve(s3);

        if (!verifiedDirs.contains(shardDir))
        {
            prepareShard(shardDir);
        }

        return shardDir;
    }

    private void prepareShard(Path shardDir)
    {
        try
        {
            Files.createDirectories(shardDir);
            verifiedDirs.add(shardDir);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }
}