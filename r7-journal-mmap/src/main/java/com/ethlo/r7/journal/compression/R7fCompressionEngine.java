package com.ethlo.r7.journal.compression;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.r7f.R7fConstants;
import com.github.luben.zstd.Zstd;

/**
 * Compresses sealed journal segments in the background.
 * <p>
 * The source file is an audit record, so it is only deleted once the compressed copy has
 * been written in full, flushed, moved into place, and verified to decompress back to a
 * byte-identical file.
 */
public class R7fCompressionEngine implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(R7fCompressionEngine.class);

    private final ScheduledExecutorService compressionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "r7-journal-compressor");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final int compressionLevel;
    private final long delaySeconds;

    // Reused off-heap buffers, only ever touched by the single compressor thread.
    private ByteBuffer compressionBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024);
    private ByteBuffer verificationBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024);

    public R7fCompressionEngine(int compressionLevel, long delaySeconds)
    {
        this.compressionLevel = compressionLevel;
        this.delaySeconds = delaySeconds;
    }

    public void submitForCompression(Path finalizedPath)
    {
        log.debug("Queueing {} for compression in {} seconds", finalizedPath.getFileName(), delaySeconds);
        compressionScheduler.schedule(() -> compressAndDelete(finalizedPath), delaySeconds, TimeUnit.SECONDS);
    }

    private void compressAndDelete(final Path source)
    {
        final Path target = source.resolveSibling(source.getFileName() + R7fConstants.COMPRESSED_FILE_EXTENSION);
        final Path tempTarget = source.resolveSibling(source.getFileName() + ".zst.tmp");

        final long start = System.nanoTime();

        try
        {
            final long fileSize = Files.size(source);
            if (fileSize <= 0)
            {
                log.warn("Refusing to compress empty segment {}", source.getFileName());
                return;
            }
            if (fileSize > Integer.MAX_VALUE)
            {
                log.error("Segment {} is {} bytes, which exceeds the {} byte limit this compressor can address; leaving it uncompressed.",
                        source.getFileName(), fileSize, Integer.MAX_VALUE);
                return;
            }

            final long sourceCrc;

            try (Arena arena = Arena.ofConfined();
                 FileChannel srcChannel = FileChannel.open(source, StandardOpenOption.READ);
                 FileChannel destChannel = FileChannel.open(tempTarget, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
            {
                final ByteBuffer srcMapped = srcChannel
                        .map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena)
                        .asByteBuffer();

                final CRC32C crc = new CRC32C();
                crc.update(srcMapped.duplicate());
                sourceCrc = crc.getValue();

                // Zstd requires knowing the maximum possible compressed size to prevent buffer overflows
                final long maxCompressedSize = Zstd.compressBound(fileSize);
                if (maxCompressedSize > Integer.MAX_VALUE)
                {
                    log.error("Compress bound for {} is {} bytes, too large to buffer; leaving it uncompressed.",
                            source.getFileName(), maxCompressedSize);
                    return;
                }

                compressionBuffer = ensureCapacity(compressionBuffer, (int) maxCompressedSize);
                compressionBuffer.clear();
                compressionBuffer.limit((int) maxCompressedSize);

                Zstd.compress(compressionBuffer, srcMapped.duplicate(), compressionLevel);
                compressionBuffer.flip();

                writeFully(destChannel, compressionBuffer);
                destChannel.force(true);
            }

            if (!verify(tempTarget, fileSize, sourceCrc))
            {
                log.error("Compressed copy of {} did not verify; keeping the original and discarding the copy.", source.getFileName());
                Files.deleteIfExists(tempTarget);
                return;
            }

            // Safely hand over to the tailer, then drop the original.
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

    /**
     * Decompresses what we just wrote and checks it against the source length and CRC.
     * The original is an audit record: we do not delete it on the strength of a write
     * that returned without throwing.
     */
    private boolean verify(final Path compressed, final long expectedSize, final long expectedCrc) throws IOException
    {
        try (Arena arena = Arena.ofConfined();
             FileChannel channel = FileChannel.open(compressed, StandardOpenOption.READ))
        {
            final long compressedSize = Files.size(compressed);
            if (compressedSize <= 0 || compressedSize > Integer.MAX_VALUE)
            {
                log.error("Compressed file {} has implausible size {}", compressed.getFileName(), compressedSize);
                return false;
            }

            final ByteBuffer mapped = channel
                    .map(FileChannel.MapMode.READ_ONLY, 0, compressedSize, arena)
                    .asByteBuffer();

            final long frameSize = Zstd.getDirectByteBufferFrameContentSize(mapped, 0, (int) compressedSize);
            if (frameSize != expectedSize)
            {
                log.error("Compressed file {} declares {} bytes of content, expected {}",
                        compressed.getFileName(), frameSize, expectedSize);
                return false;
            }

            verificationBuffer = ensureCapacity(verificationBuffer, (int) expectedSize);
            final ByteBuffer out = verificationBuffer.slice(0, (int) expectedSize);

            Zstd.decompress(out, mapped);
            out.flip();

            if (out.remaining() != expectedSize)
            {
                log.error("Decompressed {} to {} bytes, expected {}", compressed.getFileName(), out.remaining(), expectedSize);
                return false;
            }

            final CRC32C crc = new CRC32C();
            crc.update(out);
            if (crc.getValue() != expectedCrc)
            {
                log.error("Decompressed {} does not match the source checksum", compressed.getFileName());
                return false;
            }

            return true;
        }
    }

    /**
     * {@link FileChannel#write(ByteBuffer)} is permitted to write fewer bytes than
     * requested. Looping is the difference between a complete audit segment and a
     * silently truncated one.
     */
    private static void writeFully(final FileChannel channel, final ByteBuffer buffer) throws IOException
    {
        while (buffer.hasRemaining())
        {
            final int written = channel.write(buffer);
            if (written <= 0)
            {
                throw new IOException("Channel accepted no bytes with " + buffer.remaining() + " remaining");
            }
        }
    }

    private static ByteBuffer ensureCapacity(final ByteBuffer current, final int required)
    {
        if (current.capacity() >= required)
        {
            return current;
        }
        return ByteBuffer.allocateDirect(required);
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
