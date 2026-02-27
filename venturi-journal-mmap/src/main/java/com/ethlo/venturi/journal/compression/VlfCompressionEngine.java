package com.ethlo.venturi.journal.compression;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.ethlo.venturi.vlf.VlfConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.luben.zstd.ZstdOutputStream;

public class VlfCompressionEngine
{
    private static final Logger log = LoggerFactory.getLogger(VlfCompressionEngine.class);

    // Upgraded to a single-threaded scheduled executor
    private final ScheduledExecutorService compressionScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
    {
        @Override
        public Thread newThread(Runnable r)
        {
            final Thread t = new Thread(r, "venturi-vlf-compressor");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    });

    private final int compressionLevel;
    private final long delaySeconds;

    public VlfCompressionEngine(int compressionLevel, long delaySeconds)
    {
        this.compressionLevel = compressionLevel;
        this.delaySeconds = delaySeconds;
    }

    public void submitForCompression(Path finalizedVlfPath)
    {
        log.debug("⏱️ Queueing {} for compression in {} seconds", finalizedVlfPath.getFileName(), delaySeconds);
        compressionScheduler.schedule(() -> compressAndDelete(finalizedVlfPath), delaySeconds, TimeUnit.SECONDS);
    }

    private void compressAndDelete(Path source)
    {
        final Path target = source.resolveSibling(source.getFileName() + VlfConstants.COMPRESSED_FILE_EXTENSION);
        final Path tempTarget = source.resolveSibling(source.getFileName() + ".zst.tmp");

        final long start = System.nanoTime();
        try
        {
            try (InputStream in = Files.newInputStream(source);
                 OutputStream fos = Files.newOutputStream(tempTarget);
                 ZstdOutputStream zout = new ZstdOutputStream(fos, compressionLevel))
            {
                in.transferTo(zout);
            }

            Files.move(tempTarget, target, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(source);

            final long ms = (System.nanoTime() - start) / 1_000_000;
            log.debug("Compressed {} to {} in {}ms", source.getFileName(), target.getFileName(), ms);
        }
        catch (Exception e)
        {
            log.error("Failed to compress journal file: {}", source, e);
            try
            {
                Files.deleteIfExists(tempTarget);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    public void shutdown()
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