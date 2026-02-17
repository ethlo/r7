package com.ethlo.venturi;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.ServerDirection;
import com.ethlo.venturi.vlf.JournalDecoder;
import com.ethlo.venturi.vlf.JournalEventListener;
import com.ethlo.venturi.vlf.VlfConstants;
import com.ethlo.venturi.vlf.VlfDictionary;
import com.ethlo.venturi.vlf.VlfJournal;
import com.ethlo.venturi.vlf.VlfJournalProvider;

public final class VlfPerformanceBenchmark
{
    public static void main(String[] args) throws IOException
    {
        final int iterations = 1_000_000;
        final Path tempDir = Files.createTempDirectory("vlf-bench");

        try
        {
            // 1. Setup Dictionary
            final Properties props = new Properties();
            props.setProperty("1", "GET");
            props.setProperty("32", "user-agent");
            props.setProperty("91", "application/json");
            props.setProperty("151", "keep-alive");
            final VlfDictionary dictionary = new VlfDictionary(props);

            // 2. Generate Data
            System.out.println("Generating 3M events...");
            final VlfJournalProvider provider = new VlfJournalProvider(tempDir, 0);

            // Increased index size (100MB) to prevent mid-run rotations
            try (VlfJournal journal = new VlfJournal(provider, 512 * 1024 * 1024, 100 * 1024 * 1024))
            {
                final GatewayHeaders headers = new SimpleGatewayHeaders();
                headers.set("user-agent", "Mozilla/5.0 (Venturi Bench)");
                headers.set("content-type", "application/json");
                headers.set("connection", "keep-alive");

                final java.nio.ByteBuffer body = java.nio.ByteBuffer.wrap("{\"status\":\"ok\"}".getBytes());

                for (int i = 0; i < iterations; i++)
                {
                    final String id = "req-" + i;
                    journal.start(ServerDirection.REQUEST, id, java.nio.ByteBuffer.wrap("GET /test HTTP/1.1".getBytes()), headers);
                    journal.body(ServerDirection.REQUEST, id, body.duplicate());
                    journal.end(id, 200, 150, 200, 15);
                }
            }

            // 3. Find the resulting .raw file (since .active was renamed on close)
            final Path finalPath;
            try (Stream<Path> files = Files.list(tempDir))
            {
                finalPath = files.filter(p -> p.toString().endsWith(".raw"))
                        .findFirst()
                        .orElseThrow(() -> new FileNotFoundException("No .raw shard found in " + tempDir));
            }

            // 4. Measure Decoding Speed
            System.out.println("Starting decode benchmark on: " + finalPath.getFileName());
            try (RandomAccessFile raf = new RandomAccessFile(finalPath.toFile(), "r"))
            {
                final long fileSize = raf.length();
                final MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
                buffer.order(ByteOrder.BIG_ENDIAN);

                final JournalEventListener noopListener = new JournalEventListener()
                {
                    @Override public void onBegin(ServerDirection d, String id, String line, Map<String, String> h) {}
                    @Override public void onBody(ServerDirection d, String id, java.nio.ByteBuffer data) {}
                    @Override public void onEnd(String id, long ts, int status, long sent, long recv, long dur) {}
                };

                // 1. Peek at preamble (This moves position from 0 to wherever the dict ends)
                VlfDictionary dict = JournalDecoder.readDictionaryFromPreamble(buffer);

                // 2. REWIND / POSITION is the key
                // The preamble read left the position somewhere in the middle of the first 4KB.
                // We MUST jump to exactly 4096 to start the decode loop.
                buffer.position(VlfConstants.PREAMBLE_SIZE);

                System.out.println("Decoding from position: " + buffer.position() + " with " + buffer.remaining() + " bytes left.");

                final long start = System.nanoTime();
                final long bytes = JournalDecoder.decode(buffer, dictionary, noopListener);
                System.out.println(bytes);
                final long end = System.nanoTime();

                final long durationMs = TimeUnit.NANOSECONDS.toMillis(end - start);
                final double opsPerSec = (iterations * 3.0) / (durationMs / 1000.0);
                final double mbPerSec = (fileSize / 1024.0 / 1024.0) / (durationMs / 1000.0);

                System.out.println("\n--- Results ---");
                System.out.println("Throughput: " + String.format("%.2f", opsPerSec / 1_000_000.0) + " million events/sec");
                System.out.println("Data Rate:  " + String.format("%.2f", mbPerSec) + " MB/s");
                System.out.println("Time:       " + durationMs + " ms");

                VlfJournal.unmap(buffer);
            }
        }
        finally
        {
            // Cleanup temp directory
            if (Files.exists(tempDir))
            {
                try (Stream<Path> walk = Files.walk(tempDir))
                {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignore) {}
                    });
                }
            }
        }
    }
}