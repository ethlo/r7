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
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.JournalAnalyzer;
import com.ethlo.r7.r7f.R7fConstants;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.FastGatewayHeaders;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.MediaTypes;

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

                    // Hashed as it is written, exactly as the gateway does. Passing a literal
                    // 0 here — which this test did until the reader started verifying — is not
                    // "no checksum": zero is a legitimate CRC32C value, so it claims the body
                    // hashes to zero and every exchange reads back as a mismatch.
                    final CRC32C requestCrc = new CRC32C();
                    for (int chunk = 0; chunk < 4; chunk++)
                    {
                        journal.requestBody(reqId, ByteBuffer.wrap(largeBody));
                        requestCrc.update(largeBody);
                        Thread.yield();
                    }

                    // 3. END
                    final long requestStartTs = Instant.now().toEpochMilli() * 1000L;
                    final int statusCode = 201;
                    final long proxyStartTs = requestStartTs + 120_000;
                    final long proxyFirstByteReceivedTs = proxyStartTs + 212_000_000;
                    final long proxyEndTs = proxyFirstByteReceivedTs + 260_000_000;
                    final long requestEndTs = proxyEndTs + 60_000L;
                    // No response body was journaled, so there is nothing to claim about one.
                    journal.endExchange(reqId, new FastGatewayAttributes(), requestStartTs, requestEndTs, statusCode, 100, 123, 223, 17, proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs,
                            BodyChecksum.of(requestCrc), BodyChecksum.NOT_RECORDED);
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
        // The bodies were interleaved across four shards and many rotations, so this is the
        // assertion that says they came back as the same bytes rather than merely the same
        // count. Its absence is why this test logged 800 mismatches and still passed.
        assertThat(stats.checksumMismatches)
                .as("every body must read back as the bytes that were written")
                .isZero();

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
            final byte[] requestBody = "Request chunk".getBytes();
            final byte[] responseBody = "Response chunk".getBytes();

            journal.clientRequest(JournalLevel.FULL, id, ByteBuffer.wrap("GET".getBytes()), headers, InetAddress.getLocalHost(), IpSource.SOCKET);
            journal.requestBody(id, ByteBuffer.wrap(requestBody));

            journal.clientResponse(JournalLevel.FULL, id, 200, ByteBuffer.wrap("HTTP/1.1 200 OK".getBytes()), new FastGatewayHeaders());
            journal.responseBody(id, ByteBuffer.wrap(responseBody));

            final long requestStartTs = Instant.now().toEpochMilli() * 1000L;
            final int statusCode = 201;
            final long proxyStartTs = requestStartTs + 120_000;
            final long proxyFirstByteReceivedTs = proxyStartTs + 212_000_000;
            final long proxyEndTs = proxyFirstByteReceivedTs + 260_000_000;
            final long requestEndTs = proxyEndTs + 60_000L;
            journal.endExchange(id, new FastGatewayAttributes(), requestStartTs, requestEndTs, statusCode, 100, 123, 321, 2, proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs,
                    crc32c(requestBody), crc32c(responseBody));
        }

        try
        {
            JournalAnalyzer.Stats stats = new JournalAnalyzer(tempDir).analyze();
            assertThat(stats).isNotNull();
            assertThat(stats.completedExchanges).isOne();
            assertThat(stats.checksumMismatches)
                    .as("both bodies must read back as the bytes that were written")
                    .isZero();
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

    /**
     * The CRC32C of a body, as the gateway records it: built through the accumulator rather
     * than from a number, so the test cannot express a checksum the production path could
     * not produce.
     */
    private static BodyChecksum crc32c(final byte[] body)
    {
        final CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        return BodyChecksum.of(crc);
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