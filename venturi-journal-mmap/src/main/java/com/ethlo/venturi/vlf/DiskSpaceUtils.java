package com.ethlo.venturi.vlf;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DiskSpaceUtils
{
    private static final Logger log = LoggerFactory.getLogger(DiskSpaceUtils.class);

    /**
     * Safely retrieves the usable disk space in bytes for a given Path.
     * Returns -1L if the space cannot be determined.
     */
    public static long getSafeUsableSpace(Path path)
    {
        if (path == null)
        {
            return -1L;
        }

        // 1. Find the nearest existing parent to avoid NoSuchFileException
        Path existingPath = path;
        while (existingPath != null && !Files.exists(existingPath))
        {
            existingPath = existingPath.getParent();
        }

        // If no part of the path exists (e.g., completely invalid drive), fail gracefully
        if (existingPath == null)
        {
            return -1L;
        }

        try
        {
            FileStore fileStore = Files.getFileStore(existingPath);

            // getUsableSpace() is preferred over getUnallocatedSpace()
            return fileStore.getUsableSpace();

        }
        catch (IOException | SecurityException e)
        {
            log.info("Unable to read file store for path: {}", existingPath);
            return -1L;
        }
    }

    /**
     * Converts raw bytes into a human-readable format (e.g., 24.5 GB).
     * Returns "Unknown" for negative values (like our -1L error state).
     */
    public static String formatBytes(long bytes)
    {
        if (bytes < 0)
        {
            return "Unknown";
        }
        if (bytes < 1024)
        {
            return bytes + " B";
        }

        String[] units = {"KB", "MB", "GB", "TB", "PB", "EB"};
        int unitIndex = 0;
        double size = bytes;

        // Keep dividing by 1024 until the number is small enough,
        // or we run out of units
        while (size >= 1024 && unitIndex < units.length - 1)
        {
            size /= 1024;
            unitIndex++;
        }

        // Format to 1 decimal place. You can use "%.2f %s" for 2 decimal places.
        // .formatted() is a convenient alternative to String.format() available in modern Java.
        return "%.1f %s".formatted(size, units[unitIndex]);
    }
}