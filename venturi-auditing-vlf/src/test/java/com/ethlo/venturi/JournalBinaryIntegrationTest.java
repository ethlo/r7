package com.ethlo.venturi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.mmap.Journal;
import com.ethlo.venturi.mmap.JournalAnalyzer;
import com.ethlo.venturi.mmap.JournalProvider;
import com.ethlo.venturi.mmap.ShardedMmapWriter;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;

class JournalBinaryIntegrationTest
{
    private static final Logger logger = LoggerFactory.getLogger(JournalBinaryIntegrationTest.class);

    @TempDir
    Path tempDir;

    @Test
    void testInterleavedConcurrentRequestsWithRotation() throws Exception
    {
        // SETUP: Small segments to force MANY rotations
        int shardCount = 4;
        long segmentSize = 1024 * 64; // 64KB segments
        long indexSize = 1024 * 1024; // 1MB index

        ShardedMmapWriter writer = new ShardedMmapWriter(tempDir, shardCount, segmentSize, indexSize);

        int requestsPerThread = 50;
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threadCount; t++)
        {
            final int threadId = t;
            tasks.add(() -> {
                for (int r = 0; r < requestsPerThread; r++)
                {
                    String reqId = "req-" + threadId + "-" + r;
                    Journal j = writer.getJournalForRequest(reqId);

                    // 1. BEGIN
                    GatewayHeaders headers = new SimpleGatewayHeaders();
                    headers.add("User-Agent", "JUnit");
                    j.writeBegin(ServerDirection.REQUEST, reqId,
                            ByteBuffer.wrap("GET /api/data HTTP/1.1".getBytes()), headers
                    );

                    // 2. INTERLEAVED BODY PARTS
                    // We write a body that is likely to span segments
                    byte[] largeBody = new byte[8192]; // 8KB
                    java.util.Arrays.fill(largeBody, (byte) 'A');

                    // Simulate streaming chunks
                    for (int chunk = 0; chunk < 4; chunk++)
                    {
                        j.writeBodyPart(ServerDirection.REQUEST, reqId, ByteBuffer.wrap(largeBody));
                        // Small sleep to encourage interleaving with other threads
                        Thread.yield();
                    }

                    // 3. END
                    j.writeEnd(reqId, 200, 32768, 512, 150_000);
                }
                return null;
            });
        }

        // Execute all threads
        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) f.get(); // Check for exceptions

        // Close everything to flush .active to .raw
        writer.shutdown();
        executor.shutdown();

        // VERIFICATION
        // 1. Check that files were rotated (we expect more than 'shardCount' files)
        long fileCount = java.nio.file.Files.list(tempDir)
                .filter(p -> p.toString().endsWith(".raw"))
                .count();
        logger.info("Total .raw segments created: {}", fileCount);
        Assertions.assertThat(fileCount).as("Should have rotated multiple times").isGreaterThan(shardCount);

        // 2. Use Analyzer to verify totals
        // Note: Your analyzer must be updated to look for .raw files and handle fragments
        JournalAnalyzer.Stats stats = new JournalAnalyzer(tempDir).analyze();

        int totalRequests = threadCount * requestsPerThread;
        assertThat(stats.completedExchanges).isEqualTo(totalRequests);
    }

    @Test
    void testRequestResponseInterleaving() throws IOException
    {
        JournalProvider provider = new JournalProvider(tempDir, 0);
        Path lastRawFile;

        try (Journal j = new Journal(provider, 1024 * 1024, 1024 * 1024))
        {
            String id = "dual-123";

            j.writeBegin(ServerDirection.REQUEST, id, ByteBuffer.wrap("GET".getBytes()), new SimpleGatewayHeaders());
            j.writeBodyPart(ServerDirection.REQUEST, id, ByteBuffer.wrap("Request chunk".getBytes()));
            j.writeBegin(ServerDirection.RESPONSE, id, ByteBuffer.wrap("HTTP/1.1 200 OK".getBytes()), new SimpleGatewayHeaders());
            j.writeBodyPart(ServerDirection.RESPONSE, id, ByteBuffer.wrap("Response chunk".getBytes()));
            j.writeEnd(id, 200, 100, 100, 500);
        } // Journal.close() moves .active to .raw

        // Find the resulting .raw file
        try (var stream = Files.list(tempDir))
        {
            lastRawFile = stream
                    .filter(p -> p.toString().endsWith(".raw"))
                    .findFirst()
                    .orElse(null);
        }

        try
        {
            Assertions.assertThat(lastRawFile).isNotNull();
            JournalAnalyzer.Stats stats = new JournalAnalyzer(tempDir).analyze();

            assertThat(stats.completedExchanges).isOne();
        }
        catch (Throwable e)
        {
            if (lastRawFile != null && Files.exists(lastRawFile))
            {
                printHexDump(lastRawFile);
            }
            else
            {
                logger.error("No .raw file found in {}", tempDir);
                Files.list(tempDir).forEach(p -> logger.error("File in dir: {}", p));
            }
            throw e;
        }
    }

    private void printHexDump(Path path) throws IOException
    {
        byte[] data = Files.readAllBytes(path);
        logger.error("--- HEX DUMP of {} ({} bytes) ---", path.getFileName(), data.length);
        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();

        for (int i = 0; i < Math.min(data.length, 512); i++)
        {
            int b = data[i] & 0xFF;
            hex.append(String.format("%02X ", b));
            ascii.append((b >= 32 && b <= 126) ? (char) b : '.');

            if ((i + 1) % 16 == 0)
            {
                logger.error("{} | {}", hex, ascii);
                hex.setLength(0);
                ascii.setLength(0);
            }
        }
        logger.error("------------------------------------------------");
    }
}