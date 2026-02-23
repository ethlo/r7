package com.ethlo.venturi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import com.ethlo.venturi.vlf.AsyncSegmentProvider;
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
        int segmentSize = 1024 * 64; // 64KB segments
        long indexSize = 1024 * 1024; // 1MB index

        // Manual shard array to avoid dependency on ShardedJournalWriter in 'core'
        VlfJournal[] journals = new VlfJournal[shardCount];
        for (int i = 0; i < shardCount; i++)
        {
            journals[i] = new VlfJournal(new AsyncSegmentProvider(segmentSize, new VlfJournalProvider(tempDir, i), 1));
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
                    GatewayHeaders headers = new SimpleGatewayHeaders();
                    headers.add("User-Agent", "JUnit");
                    journal.start(ServerDirection.REQUEST, reqId, ByteBuffer.wrap("GET /api/data HTTP/1.1".getBytes()), headers);

                    // 2. INTERLEAVED BODY PARTS
                    byte[] largeBody = new byte[8192];
                    java.util.Arrays.fill(largeBody, (byte) 'A');

                    for (int chunk = 0; chunk < 4; chunk++)
                    {
                        journal.body(ServerDirection.REQUEST, reqId, ByteBuffer.wrap(largeBody));
                        Thread.yield();
                    }

                    // 3. END
                    journal.end(reqId, 200, 32768, 512, 150_000);
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
        VlfJournalProvider provider = new VlfJournalProvider(tempDir, 0);

        final int segmentSize = 1024 * 1024;
        try (VlfJournal j = new VlfJournal(new AsyncSegmentProvider(segmentSize, provider, 1)))
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
            assertThat(stats).isNotNull();
            assertThat(stats.completedExchanges).isOne();

            // Verify the actual payload made it to the file properly
            List<Path> rawFiles;
            try (Stream<Path> stream = Files.walk(tempDir))
            {
                rawFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".raw"))
                        .toList();
            }

            assertThat(rawFiles).isNotEmpty();

            for (Path rawFile : rawFiles)
            {
                byte[] fileBytes = Files.readAllBytes(rawFile);
                assertThat(fileBytes).isNotEmpty();

                // Convert with ISO_8859_1 to safely avoid malformed utf-8 exceptions from binary framing
                String fileContent = new String(fileBytes, StandardCharsets.ISO_8859_1);

                assertThat(fileContent)
                        .as("Journal binary output should contain the raw plaintext chunks")
                        .contains("GET")
                        .contains("Request chunk")
                        .contains("HTTP/1.1 200 OK")
                        .contains("Response chunk");
            }
        }
        catch (Throwable e)
        {
            // Locate the .raw file for debugging if it fails
            try (Stream<Path> stream = Files.walk(tempDir))
            {
                stream.filter(Files::isRegularFile)
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
    }
}