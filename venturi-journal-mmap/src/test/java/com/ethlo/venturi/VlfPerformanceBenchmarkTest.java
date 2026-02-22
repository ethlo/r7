package com.ethlo.venturi;

import static java.nio.ByteBuffer.wrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.chronograph.Chronograph;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.auditing.api.ExchangeCompletionListener;
import com.ethlo.venturi.auditing.api.JournalExchange;
import com.ethlo.venturi.vlf.VenturiTailer;
import com.ethlo.venturi.vlf.VlfDictionary;
import com.ethlo.venturi.vlf.VlfJournal;
import com.ethlo.venturi.vlf.VlfJournalProvider;

public final class VlfPerformanceBenchmarkTest
{
    private static final Logger logger = LoggerFactory.getLogger(VlfPerformanceBenchmarkTest.class);

    @RepeatedTest(10)
    void testPerformance() throws IOException
    {
        final int iterations = 10_000_000;
        final Path tempDir = Files.createTempDirectory("vlf-bench");

        try
        {
            logger.info("Setting up benchmark in {}", tempDir);
            final VlfJournalProvider provider = new VlfJournalProvider(tempDir, 0);
            final Chronograph chronograph = Chronograph.create();

            final GatewayHeaders headers = new SimpleGatewayHeaders();
            headers.set("user-agent", "Mozilla/5.0 (Venturi Bench)");
            headers.set("content-type", "application/json");
            headers.set("connection", "keep-alive");
            for (int i = 0; i < 0; i++)
            {
                headers.set("header" + i, "header-value" + i);
            }

            final ByteBuffer startLine = wrap("GET /test HTTP/1.1".getBytes());
            final ByteBuffer body = wrap("{\"status\":\"ok\"}".getBytes());

            final VlfDictionary dictionary = VlfDictionary.load("/default-dict.properties");

            final AtomicReference<VlfJournal> journalRef = new AtomicReference<>();
            // 1. Measure Encoding Speed (Write)
            chronograph.time("Encode " + iterations, () ->
                    {
                        // Ensure the journal is closed IMMEDIATELY after the loop
                        try (final VlfJournal journal = new VlfJournal(provider, dictionary, Integer.MAX_VALUE, 1024 * 1024 * 500))
                        {
                            journalRef.set(journal);
                            for (int i = 0; i < iterations; i++)
                            {
                                final String id = "req" + i;
                                journal.start(ServerDirection.REQUEST, id, startLine, headers);
                                journal.body(ServerDirection.REQUEST, id, body);
                                journal.end(id, 200, 150, 200, 15);
                            }
                        }
                        catch (Exception e)
                        {
                            throw new RuntimeException(e);
                        }
                    }
            );

            final Path finalPath = journalRef.get().getActivePath();

            // 3. Measure Decoding Speed using the high-water mark
            final ExchangeCompletionListener noopListener = new ExchangeCompletionListener()
            {
                @Override
                public void onComplete(final JournalExchange exchange)
                {

                }
            };

            final VenturiTailer tailer = new VenturiTailer(finalPath.getParent(), Duration.ZERO, noopListener);

            chronograph.time("Decode " + iterations, () -> {
                        try
                        {
                            //tailer.runTick();
                        }
                        catch (Exception e)
                        {
                            throw new RuntimeException(e);
                        }
                    }
            );

            System.out.println(chronograph);
            final long actualDataSize = journalRef.get().getOffset();
            System.out.println("\n--- Results ---");
            final double timeSeconds = chronograph.getTask("Decode " + iterations).getTime().toNanos() / 1_000_000_000.0;
            final double mbPerSec = (actualDataSize / 1024.0 / 1024.0) / timeSeconds;

            final double writeTimeSeconds = chronograph.getTask("Encode " + iterations).getTime().toNanos() / 1_000_000_000.0;
            final double writeMbPerSec = (actualDataSize / 1024.0 / 1024.0) / writeTimeSeconds;

            System.out.println("Actual Data Size: " + String.format("%.2f", actualDataSize / 1024.0 / 1024.0) + " MB");
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