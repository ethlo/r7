package com.ethlo.r7.journal.compression;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.r7f.R7fConstants;
import com.github.luben.zstd.Zstd;

public class R7fCompressionEngine implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(R7fCompressionEngine.class);

    // Upgraded to a single-threaded scheduled executor
    private final ScheduledExecutorService compressionScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
    {
        @Override
        public Thread newThread(Runnable r)
        {
            final Thread t = new Thread(r, "r7-journal-compressor");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    });

    private final int compressionLevel;
    private final long delaySeconds;

    public R7fCompressionEngine(int compressionLevel, long delaySeconds)
    {
        this.compressionLevel = compressionLevel;
        this.delaySeconds = delaySeconds;
    }

    public void submitForCompression(Path finalizedPath)
    {
        log.debug("⏱️ Queueing {} for compression in {} seconds", finalizedPath.getFileName(), delaySeconds);
        compressionScheduler.schedule(() -> compressAndDelete(finalizedPath), delaySeconds, TimeUnit.SECONDS);
    }

    private void compressAndDelete(final Path source)
    {
        final Path target = source.resolveSibling(source.getFileName() + R7fConstants.COMPRESSED_FILE_EXTENSION);
        final Path tempTarget = source.resolveSibling(source.getFileName() + ".zst.tmp");

        final long start = System.nanoTime();
        try
        {
            // Read the entire source file into memory
            final byte[] srcData = Files.readAllBytes(source);

            // Zstd.compress includes the Content Size header by default when given a byte array
            final byte[] compressedData = Zstd.compress(srcData, compressionLevel);

            // Write the compressed result to the temp file
            Files.write(tempTarget, compressedData);

            Files.move(tempTarget, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(source);

            final long ms = (System.nanoTime() - start) / 1_000_000;
            log.debug("Compressed {} to {} in {}ms", source.getFileName(), target.getFileName(), ms);
        }
        catch (final Exception e)
        {
            log.error("Failed to compress journal file: {}", source, e);
            try
            {
                Files.deleteIfExists(tempTarget);
            }
            catch (final Exception ignored)
            {
                // Ignore secondary failure
            }
        }
    }

    @Override
    public void close()
    {
        compressionScheduler.shutdown();
        try
        {
            // Give it a brief window to finish any active compression before the JVM dies
            if (!compressionScheduler.awaitTermination(3, TimeUnit.SECONDS))
            {
                compressionScheduler.shutdownNow();
            }
        }
        catch (InterruptedException e)
        {
            compressionScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}