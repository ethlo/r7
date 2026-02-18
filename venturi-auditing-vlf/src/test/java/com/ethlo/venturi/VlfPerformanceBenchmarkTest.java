package com.ethlo.venturi;

import static java.nio.ByteBuffer.wrap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.RepeatedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.chronograph.Chronograph;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.vlf.JournalDecoder;
import com.ethlo.venturi.vlf.JournalEventListener;
import com.ethlo.venturi.vlf.VlfConstants;
import com.ethlo.venturi.vlf.VlfDictionary;
import com.ethlo.venturi.vlf.VlfJournal;
import com.ethlo.venturi.vlf.VlfJournalProvider;

public final class VlfPerformanceBenchmarkTest
{
    private static final Logger logger = LoggerFactory.getLogger(VlfPerformanceBenchmarkTest.class);

    @RepeatedTest(10)
    void testPerformance() throws IOException
    {
        final int iterations = 2_000_000;
        final Path tempDir = Files.createTempDirectory("vlf-bench");

        try
        {
            logger.info("Creating {} requests", iterations);
            final VlfJournalProvider provider = new VlfJournalProvider(tempDir, 0);
            final Chronograph chronograph = Chronograph.create();
            chronograph.time("Encode " + iterations, () ->
                    {
                        try (VlfJournal journal = new VlfJournal(provider, 512 * 1024 * 1024, 100 * 1024 * 1024))
                        {
                            final GatewayHeaders headers = new SimpleGatewayHeaders();
                            headers.set("user-agent", "Mozilla/5.0 (Venturi Bench)");
                            headers.set("content-type", "application/json");
                            headers.set("connection", "keep-alive");

                            final ByteBuffer body = wrap("{\"status\":\"ok\"}".getBytes());

                            for (int i = 0; i < iterations; i++)
                            {
                                final String id = "req-" + i;
                                journal.start(ServerDirection.REQUEST, id, wrap("GET /test HTTP/1.1".getBytes()), headers);
                                journal.body(ServerDirection.REQUEST, id, body.duplicate());
                                journal.end(id, 200, 150, 200, 15);
                            }
                        }
                        catch (IOException e)
                        {
                            throw new UncheckedIOException(e);
                        }
                    }
            );

            // 3. Find the resulting .raw file
            final Path finalPath;
            try (Stream<Path> files = Files.list(tempDir))
            {
                finalPath = files.filter(p -> p.toString().endsWith(".raw"))
                        .findFirst()
                        .orElseThrow(() -> new FileNotFoundException("No .raw shard found in " + tempDir));
            }

            // 4. Measure Decoding Speed
            logger.info("Starting decode benchmark on: {}", finalPath.getFileName());
            try (RandomAccessFile raf = new RandomAccessFile(finalPath.toFile(), "r"))
            {
                final long fileSize = raf.length();
                final MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
                buffer.order(ByteOrder.BIG_ENDIAN);

                final int[] counters = new int[]{0,0,0};
                final JournalEventListener noopListener = new JournalEventListener()
                {
                    @Override
                    public void onBegin(ServerDirection d, String id, String line, Map<String, String> h)
                    {
                        counters[0]++;
                    }

                    @Override
                    public void onBody(ServerDirection d, String id, java.nio.ByteBuffer data)
                    {
                        counters[1]++;
                    }

                    @Override
                    public void onEnd(String id, long ts, int status, long sent, long recv, long dur)
                    {
                        counters[2]++;
                    }
                };

                // 1. Peek at preamble (This moves position from 0 to wherever the dict ends)
                VlfDictionary fileEmbeddedDict = JournalDecoder.readDictionaryFromPreamble(buffer);

                // 2. REWIND / POSITION is the key
                // The preamble read left the position somewhere in the middle of the first 4KB.
                // We MUST jump to exactly 4096 to start the decode loop.
                buffer.position(VlfConstants.PREAMBLE_SIZE);

                logger.info("Decoding from position: {} with {} bytes left", buffer.position(), buffer.remaining());

                final long bytes = chronograph.time("Decode " + iterations, () -> JournalDecoder.decode(buffer, fileEmbeddedDict, noopListener));
                logger.info("Bytes read: {}", bytes);

                System.out.println("\n--- Results ---");
                final double mbPerSec = fileSize / (chronograph.getTask("Decode " + iterations).getTime().toNanos() / 1_000D);
                System.out.println("Read rate:  " + String.format("%.2f", mbPerSec) + " MB/s");
                System.out.println("Begin: " + counters[0]);
                System.out.println("Body: " + counters[1]);
                System.out.println("End: " + counters[2]);
                System.out.println(chronograph);

                VlfJournal.unmap(buffer);
            }
        } finally
        {
            // Cleanup temp directory
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
}