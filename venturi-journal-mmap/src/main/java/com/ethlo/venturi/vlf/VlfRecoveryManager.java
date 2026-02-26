package com.ethlo.venturi.vlf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VlfRecoveryManager
{
    private static final Logger logger = LoggerFactory.getLogger(VlfRecoveryManager.class);

    /**
     * Scans the given directory for .active files, truncates them at the last
     * valid entry, and renames them to the finalized VLF extension.
     */
    private static void recoverActiveSegments(Path journalDirectory) throws IOException
    {
        if (!Files.exists(journalDirectory))
        {
            logger.warn("Journal directory does not exist, skipping recovery: {}", journalDirectory.toAbsolutePath());
            return;
        }

        try (Stream<Path> stream = Files.list(journalDirectory))
        {
            final List<Path> activeFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(VlfConstants.ACTIVE_FILE_EXTENSION))
                    .toList();

            if (activeFiles.isEmpty())
            {
                logger.info("Found 0 matching " + VlfConstants.ACTIVE_FILE_EXTENSION + " files. No recovery needed.");
                return;
            }

            logger.info("Found {} matching " + VlfConstants.ACTIVE_FILE_EXTENSION + " files. Starting recovery...", activeFiles.size());

            long totalRecordsRecovered = 0;
            int successfulFiles = 0;

            for (Path activeFile : activeFiles)
            {
                logger.debug("Attempting to recover: {}", activeFile.getFileName());
                try
                {
                    final long recordsFound = recoverFile(activeFile);
                    totalRecordsRecovered += recordsFound;
                    successfulFiles++;
                    if (recordsFound > 0)
                    {
                        logger.info("Successfully recovered: {} ({} valid records)", activeFile.getFileName(), recordsFound);
                    }
                }
                catch (Exception e)
                {
                    logger.error("Fatal error during recovery of {}", activeFile.getFileName(), e);
                }
            }

            logger.info("Recovery Complete: Successfully recovered {}/{} files containing {} total records.",
                    successfulFiles, activeFiles.size(), totalRecordsRecovered
            );
        }
        logger.info("--------------------------------");
    }

    private static long recoverFile(Path file) throws IOException
    {
        long lastValidPosition = VlfConstants.PREAMBLE_SIZE; // 1024
        long recordCount = 0;
        boolean isEmpty = false;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE))
        {
            final long size = channel.size();
            // Map the file read-only to scan the bounds and CRCs quickly
            final MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.position(VlfConstants.PREAMBLE_SIZE);

            while (buffer.remaining() >= 16) // Minimum bytes needed for a header
            {
                final int startPos = buffer.position();
                final byte marker = buffer.get(startPos);

                // If we hit the pre-faulted zeros, we have reached the end of written data
                if (marker == 0)
                {
                    break;
                }

                final int magic = buffer.getInt();
                if (magic != VlfConstants.MAGIC)
                {
                    logger.warn("Corrupt header magic found at position {}, truncating file.", startPos);
                    break;
                }

                final int payloadLen = buffer.getInt();
                final int fbLen = buffer.getInt();
                final int rawLen = buffer.getInt();

                // Bounds check to ensure a partial write didn't truncate the entry
                final int dataLen = fbLen + rawLen;
                if (payloadLen != (Integer.BYTES * 2 + dataLen) || buffer.remaining() < dataLen + Integer.BYTES)
                {
                    logger.warn("Incomplete entry written before crash at position {}, truncating file.", startPos);
                    break;
                }

                // Compute CRC to verify the payload is actually intact
                final CRC32C crc = new CRC32C();
                updateInt(crc, payloadLen);
                updateInt(crc, fbLen);
                updateInt(crc, rawLen);

                final ByteBuffer dataSlice = buffer.slice();
                dataSlice.limit(dataLen);
                crc.update(dataSlice);
                buffer.position(buffer.position() + dataLen); // Advance past payload

                final int storedCrc = buffer.getInt();
                if ((int) crc.getValue() != storedCrc)
                {
                    logger.warn("Checksum mismatch at position {}! The crash happened while writing this exact payload. Truncating file.", startPos);
                    break;
                }

                // Entry is fully valid. Mark this position as safe and increment count.
                lastValidPosition = buffer.position();
                recordCount++;
            }
        }

        if (lastValidPosition == VlfConstants.PREAMBLE_SIZE && recordCount == 0)
        {
            logger.info("Removing empty file {}", file);
            Files.delete(file);
        }
        else
        {
            // Rename the truncated file to the finalized extension
            final String newName = file.getFileName().toString().replace(VlfConstants.ACTIVE_FILE_EXTENSION, VlfConstants.VLF_FILE_EXTENSION);
            final Path recoveredFile = file.resolveSibling(newName);
            Files.move(file, recoveredFile, StandardCopyOption.ATOMIC_MOVE);
        }

        return recordCount;
    }

    private static void updateInt(CRC32C crc, int value)
    {
        crc.update((value >>> 24) & 0xFF);
        crc.update((value >>> 16) & 0xFF);
        crc.update((value >>> 8) & 0xFF);
        crc.update(value & 0xFF);
    }

    public static List<Path> cleanAndRecover(Path journalDirectory) throws IOException
    {
        if (!Files.exists(journalDirectory))
        {
            return List.of();
        }

        // 1. Delete orphaned partial compressions
        try (Stream<Path> stream = Files.list(journalDirectory))
        {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".zst.tmp"))
                    .forEach(p -> {
                        try
                        {
                            Files.deleteIfExists(p);
                            logger.info("Deleted orphaned temp file: {}", p.getFileName());
                        }
                        catch (Exception ignored)
                        {
                        }
                    });
        }

        // 2. Recover the active files
        recoverActiveSegments(journalDirectory);


        // 3. Collect ALL .vlf files for the compression queue
        try (Stream<Path> stream = Files.list(journalDirectory))
        {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(VlfConstants.VLF_FILE_EXTENSION))
                    .toList();
        }
    }
}