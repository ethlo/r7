package com.ethlo.r7;

import static java.nio.ByteBuffer.wrap;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.ethlo.r7.api.IpSource;

import org.junit.jupiter.api.RepeatedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.chronograph.Chronograph;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.r7f.R7fJournal;
import com.ethlo.r7.r7f.R7fJournalProvider;

public final class R7fPerformanceBenchmarkTest
{
    private static final Logger logger = LoggerFactory.getLogger(R7fPerformanceBenchmarkTest.class);

    @RepeatedTest(3)
    void testPerformance() throws IOException
    {
        final int iterations = 2_000_000;
        final Path tempDir = Files.createTempDirectory("r7f-bench");

        final InetAddress localhost = InetAddress.getLocalHost();
        try
        {
            logger.info("Setting up benchmark in {}", tempDir);
            final R7fJournalProvider provider = new R7fJournalProvider(tempDir, 0, Integer.MAX_VALUE, true);
            final Chronograph chronograph = Chronograph.create();

            final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
            headers.set("user-agent", "Mozilla/5.0 (R7 Bench)");
            headers.set("content-type", "application/json");
            headers.set("connection", "keep-alive");
            for (int i = 0; i < 2; i++)
            {
                headers.set("header" + i, "header-value" + i);
            }

            final ByteBuffer startLine = wrap("GET /test HTTP/1.1".getBytes());
            final ByteBuffer requestBody = wrap("{\"ping\":\"2022-05-17T00:12:19:22.965Z\"}".getBytes());
            final ByteBuffer responseBody = wrap("{\"status\":\"ok\"}".getBytes());

            final AtomicReference<R7fJournal> journalRef = new AtomicReference<>();
            try (final R7fJournal journal = new R7fJournal(provider))
            {
                journalRef.set(journal);
                chronograph.time("Encode " + iterations, () ->
                        {
                            for (int i = 0; i < iterations; i++)
                            {
                                final String id = "req" + i;
                                journal.clientRequest(JournalLevel.FULL, id, startLine, headers, localhost, IpSource.SOCKET);
                                journal.upstreamRequest(JournalLevel.FULL, id, startLine, headers);
                                journal.requestBody(id, requestBody.clear());
                                journal.responseBody(id, responseBody.clear());
                                journal.upstreamResponse(JournalLevel.FULL, id, 200, startLine, headers);
                                journal.clientResponse(JournalLevel.FULL, id, 200, startLine, headers);

                                final long requestStartTs = Instant.now().toEpochMilli() * 1000L;
                                final int statusCode = 201;
                                final long proxyStartTs = requestStartTs + 120_000;
                                final long proxyFirstByteReceivedTs = proxyStartTs + 212_000_000;
                                final long proxyEndTs = proxyFirstByteReceivedTs + 260_000_000;
                                final long requestEndTs = proxyEndTs + 60_000L;
                                journal.endExchange(id, new FastGatewayAttributes(), requestStartTs, requestEndTs, statusCode, 100, 123, 22, 44, proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs, 0, 0);
                            }
                        }
                );
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }

            final Path finalPath = journalRef.get().getActivePath();

            // 3. Measure Decoding Speed using the high-water mark
            final AtomicLong totalReceived = new AtomicLong();
            final ExchangeCompletionListener noopListener = exchange -> totalReceived.addAndGet(exchange.getRequestTotalBytes());
            final R7Tailer tailer = new R7Tailer(finalPath.getParent(), Duration.ZERO, noopListener);
            final long totalBytesRead = chronograph.time("Decode " + iterations, () ->
                    {
                        try
                        {
                            return tailer.runTick();
                        }
                        catch (Exception e)
                        {
                            throw new RuntimeException(e);
                        }
                    }
            );

            System.out.println(chronograph);
            System.out.println("Total received (fake values): " + totalReceived.get());
            final double timeSeconds = chronograph.getTask("Decode " + iterations).getTime().toNanos() / 1_000_000_000.0;
            final double mbPerSec = (totalBytesRead / 1024.0 / 1024.0) / timeSeconds;

            final double writeTimeSeconds = chronograph.getTask("Encode " + iterations).getTime().toNanos() / 1_000_000_000.0;
            final double writeMbPerSec = (totalBytesRead / 1024.0 / 1024.0) / writeTimeSeconds;

            System.out.println("Actual Data Size: " + String.format("%.2f", totalBytesRead / 1024.0 / 1024.0) + " MB");
            System.out.println("Write rate:        " + String.format("%.2f", writeMbPerSec) + " MB/s");
            System.out.println("Read rate:        " + String.format("%.2f", mbPerSec) + " MB/s");

        } finally
        {
            cleanup(tempDir);
        }
    }

    private void cleanup(Path tempDir) throws IOException
    {
        if (Files.exists(tempDir))
        {
            try (Stream<Path> walk = Files.walk(tempDir))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try
                    {
                        Files.delete(p);
                    }
                    catch (IOException ignore)
                    {
                    }
                });
            }
        }
    }
}