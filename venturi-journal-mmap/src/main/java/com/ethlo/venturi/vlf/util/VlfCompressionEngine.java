package com.ethlo.venturi.vlf.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.luben.zstd.ZstdOutputStream;

public class VlfCompressionEngine
{
    private static final Logger log = LoggerFactory.getLogger(VlfCompressionEngine.class);

    // A single thread ensures we never thrash the disk or CPU with parallel compressions
    private final ExecutorService compressionExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
    {
        @Override
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "venturi-vlf-compressor");
            t.setDaemon(true); // Don't block JVM shutdown
            t.setPriority(Thread.MIN_PRIORITY); // Yield to Undertow I/O threads
            return t;
        }
    });

    private final boolean compressionEnabled;
    private final int compressionLevel;

    public VlfCompressionEngine(boolean compressionEnabled, int compressionLevel)
    {
        this.compressionEnabled = compressionEnabled;
        this.compressionLevel = compressionLevel;
    }

    /**
     * Called immediately after the Journal atomically renames .active to .vlf
     */
    public void submitForCompression(Path finalizedVlfPath)
    {
        if (!compressionEnabled)
        {
            return; // Exit hatch for the 200k RPS hyper-scale users
        }

        compressionExecutor.submit(() -> compressAndDelete(finalizedVlfPath));
    }

    private void compressAndDelete(Path source)
    {
        final Path target = source.resolveSibling(source.getFileName() + ".zst");
        final Path tempTarget = source.resolveSibling(source.getFileName() + ".zst.tmp");
        final long start = System.nanoTime();
        try
        {
            final long sourceSize = Files.size(source);

            try (InputStream in = Files.newInputStream(source);
                 OutputStream fos = Files.newOutputStream(tempTarget);
                 ZstdOutputStream zout = new ZstdOutputStream(fos, compressionLevel))
            {
                in.transferTo(zout);
            }

            // Atomic rename to signal completion to external tailers
            Files.move(tempTarget, target, StandardCopyOption.ATOMIC_MOVE);
            final long targetSize = Files.size(target);

            // 3. Delete the raw file
            Files.deleteIfExists(source);

            final long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("Compressed {} ({}) to {} ({}) in {} ms", source.getFileName(), sourceSize, target.getFileName(), targetSize, ms);
        }
        catch (Exception e)
        {
            log.error("Failed to compress journal file: {}", source, e);
            // Cleanup the partial temp file so we don't leak disk space
            try
            {
                Files.deleteIfExists(tempTarget);
            }
            catch (Exception ignored)
            {
            }

            // Note: We DO NOT delete the source file if compression fails. 
            // Better to have a raw 100MB file than lose the audit log.
        }
    }

    public void shutdown()
    {
        compressionExecutor.shutdown();
    }
}