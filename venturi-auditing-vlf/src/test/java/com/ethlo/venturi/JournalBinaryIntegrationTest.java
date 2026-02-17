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

import com.ethlo.venturi.vlf.VlfDictionary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.vlf.JournalAnalyzer;
import com.ethlo.venturi.vlf.VlfJournal;
import com.ethlo.venturi.vlf.VlfJournalProvider;

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
        int mask = shardCount - 1;
        long segmentSize = 1024 * 64; // 64KB segments
        long indexSize = 1024 * 1024; // 1MB index

        // Manual shard array to avoid dependency on ShardedJournalWriter in 'core'
        VlfJournal[] journals = new VlfJournal[shardCount];
        for (int i = 0; i < shardCount; i++)
        {
            journals[i] = new VlfJournal(new VlfJournalProvider(tempDir, i), segmentSize, indexSize);
        }

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

                    // Equivalent to your sharding logic
                    int h = reqId.hashCode();
                    VlfJournal j = journals[(h ^ (h >>> 16)) & mask];

                    // 1. BEGIN (Matches new signature)
                    GatewayHeaders headers = new SimpleGatewayHeaders();
                    headers.add("User-Agent", "JUnit");
                    j.start(ServerDirection.REQUEST, reqId,
                            ByteBuffer.wrap("GET /api/data HTTP/1.1".getBytes()), headers
                    );

                    // 2. INTERLEAVED BODY PARTS
                    byte[] largeBody = new byte[8192];
                    java.util.Arrays.fill(largeBody, (byte) 'A');

                    for (int chunk = 0; chunk < 4; chunk++)
                    {
                        j.body(ServerDirection.REQUEST, reqId, ByteBuffer.wrap(largeBody));
                        Thread.yield();
                    }

                    // 3. END (Matches new signature: no ServerDirection)
                    j.end(reqId, 200, 32768, 512, 150_000);
                }
                return null;
            });
        }

        try
        {
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> f : futures) f.get();
        } finally
        {
            // Always close journals to flush .active to .raw
            for (VlfJournal j : journals) j.close();
            executor.shutdown();
        }

        // VERIFICATION
        long fileCount = Files.list(tempDir)
                .filter(p -> p.toString().endsWith(".raw"))
                .count();

        logger.info("Total .raw segments created: {}", fileCount);
        assertThat(fileCount).as("Should have rotated multiple times").isGreaterThan(shardCount);

        JournalAnalyzer.Stats stats = new JournalAnalyzer(tempDir).analyze();

        int totalRequests = threadCount * requestsPerThread;
        assertThat(stats.completedExchanges).isEqualTo(totalRequests);
    }

    @Test
    void testRequestResponseInterleaving() throws IOException
    {
        VlfJournalProvider provider = new VlfJournalProvider(tempDir, 0);

        try (VlfJournal j = new VlfJournal(provider, 1024 * 1024, 1024 * 1024))
        {
            String id = "dual-123";

            j.start(ServerDirection.REQUEST, id, ByteBuffer.wrap("GET".getBytes()), new SimpleGatewayHeaders());
            j.body(ServerDirection.REQUEST, id, ByteBuffer.wrap("Request chunk".getBytes()));

            // New signature for response BEGIN
            j.start(ServerDirection.RESPONSE, id, ByteBuffer.wrap("HTTP/1.1 200 OK".getBytes()), new SimpleGatewayHeaders());
            j.body(ServerDirection.RESPONSE, id, ByteBuffer.wrap("Response chunk".getBytes()));

            // New signature for END
            j.end(id, 200, 100, 100, 500);
        }

        try
        {
            JournalAnalyzer.Stats stats = new JournalAnalyzer(tempDir).analyze();
            assertThat(stats.completedExchanges).isOne();
        }
        catch (Throwable e)
        {
            // Locate the .raw file for debugging if it fails
            Files.list(tempDir)
                    .filter(p -> p.toString().endsWith(".raw"))
                    .findFirst()
                    .ifPresent(p -> {
                        try
                        {
                            printHexDump(p);
                        }
                        catch (IOException ignore)
                        {
                        }
                    });
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
    }
}