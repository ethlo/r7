package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.FastGatewayHeaders;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.r7f.JournalAnalyzer;
import com.ethlo.r7.r7f.R7fConstants;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
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
        R7fJournal[] journals = new R7fJournal[shardCount];
        for (int i = 0; i < shardCount; i++)
        {
            journals[i] = new R7fJournal(new R7fJournalProvider(tempDir, i, segmentSize, true));
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
                    R7fJournal journal = journals[(h ^ (h >>> 16)) & mask];

                    // 1. BEGIN
                    MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
                    headers.add("User-Agent", "JUnit");
                    journal.clientRequest(JournalLevel.HEADERS, reqId, ByteBuffer.wrap("GET /api/data HTTP/1.1".getBytes()), headers, InetAddress.getLocalHost(), IpSource.SOCKET);

                    // 2. INTERLEAVED BODY PARTS
                    byte[] largeBody = new byte[8192];
                    Arrays.fill(largeBody, (byte) 'A');

                    for (int chunk = 0; chunk < 4; chunk++)
                    {
                        journal.requestBody(reqId, ByteBuffer.wrap(largeBody));
                        Thread.yield();
                    }

                    // 3. END
                    final long requestStartTs = Instant.now().toEpochMilli() * 1000L;
                    final int statusCode = 201;
                    final long proxyStartTs = requestStartTs + 120_000;
                    final long proxyFirstByteReceivedTs = proxyStartTs + 212_000_000;
                    final long proxyEndTs = proxyFirstByteReceivedTs + 260_000_000;
                    final long requestEndTs = proxyEndTs + 60_000L;
                    journal.endExchange(reqId, new FastGatewayAttributes(), requestStartTs, requestEndTs, statusCode, 100, 123, 223, 17, proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs, 0, 0);
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
            // Always close journals
            for (R7fJournal j : journals)
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
        R7fJournalProvider provider = new R7fJournalProvider(tempDir, 0, segmentSize, true);

        try (R7fJournal journal = new R7fJournal(provider))
        {
            String id = "dual-123";

            final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
            headers.set(HttpHeaders.X_REQUEST_ID, "akdalskmdalsmdasmda");
            headers.set(HttpHeaders.CONTENT_TYPE, MediaTypes.APPLICATION_JSON);
            headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
            journal.clientRequest(JournalLevel.FULL, id, ByteBuffer.wrap("GET".getBytes()), headers, InetAddress.getLocalHost(), IpSource.SOCKET);
            journal.requestBody(id, ByteBuffer.wrap("Request chunk".getBytes()));

            journal.clientResponse(JournalLevel.FULL, id, 200, ByteBuffer.wrap("HTTP/1.1 200 OK".getBytes()), new FastGatewayHeaders());
            journal.responseBody(id, ByteBuffer.wrap("Response chunk".getBytes()));

            final long requestStartTs = Instant.now().toEpochMilli() * 1000L;
            final int statusCode = 201;
            final long proxyStartTs = requestStartTs + 120_000;
            final long proxyFirstByteReceivedTs = proxyStartTs + 212_000_000;
            final long proxyEndTs = proxyFirstByteReceivedTs + 260_000_000;
            final long requestEndTs = proxyEndTs + 60_000L;
            journal.endExchange(id, new FastGatewayAttributes(), requestStartTs, requestEndTs, statusCode, 100, 123, 321, 2, proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs, 0, 0);
        }

        try
        {
            JournalAnalyzer.Stats stats = new JournalAnalyzer(tempDir).analyze();
            assertThat(stats).isNotNull();
            assertThat(stats.completedExchanges).isOne();
        }
        catch (Throwable e)
        {
            // Locate the file for debugging if it fails
            try (Stream<Path> stream = Files.walk(tempDir))
            {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(R7fConstants.R7F_FILE_EXTENSION))
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