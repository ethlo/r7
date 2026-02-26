package com.ethlo.venturi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.util.FastGatewayAttributes;
import com.ethlo.venturi.util.FastGatewayHeaders;
import com.ethlo.venturi.util.constants.HttpHeaders;
import com.ethlo.venturi.util.constants.MediaTypes;
import com.ethlo.venturi.vlf.JournalAnalyzer;
import com.ethlo.venturi.vlf.VlfConstants;
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
        int segmentSize = 1024 * 64; // 64KB segments

        // Manual shard array to avoid dependency on ShardedJournalWriter in 'core'
        VlfJournal[] journals = new VlfJournal[shardCount];
        for (int i = 0; i < shardCount; i++)
        {
            journals[i] = new VlfJournal(new VlfJournalProvider(tempDir, i, segmentSize), segmentSize);
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
                    final int h = reqId.hashCode();
                    VlfJournal journal = journals[(h ^ (h >>> 16)) & mask];

                    // 1. BEGIN
                    GatewayHeaders headers = new FastGatewayHeaders();
                    headers.add("User-Agent", "JUnit");
                    journal.start(ServerDirection.REQUEST, JournalLevel.HEADERS, reqId, ByteBuffer.wrap("GET /api/data HTTP/1.1".getBytes()), headers);

                    // 2. INTERLEAVED BODY PARTS
                    byte[] largeBody = new byte[8192];
                    Arrays.fill(largeBody, (byte) 'A');

                    for (int chunk = 0; chunk < 4; chunk++)
                    {
                        journal.body(ServerDirection.REQUEST, reqId, ByteBuffer.wrap(largeBody));
                        Thread.yield();
                    }

                    // 3. END
                    journal.end(reqId, new FastGatewayAttributes(), 200, 32768, 512, 150_000);
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
            // Always close journals to flush .active to .vlf
            for (VlfJournal j : journals)
            {
                j.close();
            }
            executor.shutdown();
        }

        // VERIFICATION
        JournalAnalyzer.Stats stats = new JournalAnalyzer(tempDir).analyze();

        int totalRequests = threadCount * requestsPerThread;
        assertThat(stats).isNotNull();
        assertThat(stats.completedExchanges).isEqualTo(totalRequests);

        // Assert physical files are written and meet size expectations
        List<Path> journalFiles;
        try (Stream<Path> stream = Files.walk(tempDir))
        {
            journalFiles = stream.filter(Files::isRegularFile).toList();
        }

        assertThat(journalFiles).isNotEmpty();

        long totalFilesSize = 0L;
        for (Path file : journalFiles)
        {
            assertThat(file).isReadable();
            long size = Files.size(file);
            assertThat(size).isGreaterThan(0L);
            totalFilesSize += size;
        }

        // Each request writes roughly 32KB of body payload alone.
        long expectedMinimumBodyBytes = totalRequests * 32768L;
        assertThat(totalFilesSize)
                .as("Total size of written files should reflect the interleaved payload")
                .isGreaterThanOrEqualTo(expectedMinimumBodyBytes);
    }

    @Test
    void testRequestResponseInterleaving() throws IOException
    {
        final int segmentSize = 1024 * 1024;
        VlfJournalProvider provider = new VlfJournalProvider(tempDir, 0, segmentSize);

        try (VlfJournal j = new VlfJournal(provider, segmentSize))
        {
            String id = "dual-123";

            j.start(ServerDirection.REQUEST, JournalLevel.FULL, id, ByteBuffer.wrap("GET".getBytes()), FastGatewayHeaders.of(Map.of(
                            HttpHeaders.X_REQUEST_ID, "akdalskmdalsmdasmda",
                            HttpHeaders.CONTENT_TYPE, MediaTypes.APPLICATION_JSON,
                            HttpHeaders.CACHE_CONTROL, "no-cache"
                    ))
            );
            j.body(ServerDirection.REQUEST, id, ByteBuffer.wrap("Request chunk".getBytes()));

            j.start(ServerDirection.RESPONSE, JournalLevel.FULL, id, ByteBuffer.wrap("HTTP/1.1 200 OK".getBytes()), new FastGatewayHeaders());
            j.body(ServerDirection.RESPONSE, id, ByteBuffer.wrap("Response chunk".getBytes()));

            j.end(id, new FastGatewayAttributes(), 200, 100, 100, 500);
        }

        try
        {
            JournalAnalyzer.Stats stats = new JournalAnalyzer(tempDir).analyze();
            assertThat(stats).isNotNull();
            assertThat(stats.completedExchanges).isOne();
        }
        catch (Throwable e)
        {
            // Locate the .vlf file for debugging if it fails
            try (Stream<Path> stream = Files.walk(tempDir))
            {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(VlfConstants.VLF_FILE_EXTENSION))
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

        for (int i = 0; i < Math.min(data.length, 6000); i++)
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