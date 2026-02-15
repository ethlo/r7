package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

public class JournalAnalyzer
{
    public static void main(String[] args) throws IOException
    {
        String path = args.length > 0 ? args[0] : "/tmp/venturi/";
        Path journalDir = Paths.get(path);

        if (!Files.exists(journalDir))
        {
            System.err.println("Directory does not exist: " + path);
            return;
        }

        final long started = System.currentTimeMillis();
        try (Stream<Path> s = Files.list(journalDir))
        {
            List<Path> files = s.filter(p -> {
                        String name = p.toString();
                        return name.endsWith(".raw") || name.endsWith(".tmp");
                    })
                    .sorted()
                    .toList();

            System.out.println("Processing " + files.size() + " journal shards...");
            System.out.println("--------------------------------------------------------------------------------");
            System.out.printf("%-45s | %-8s | %-8s | %-8s%n", "Shard Name", "Begins", "Bodies", "Ends");
            System.out.println("--------------------------------------------------------------------------------");

            Stats totalStats = new Stats();

            for (Path file : files)
            {
                Stats fileStats = analyzeFile(file);
                System.out.printf("%-45s | %-8d | %-8d | %-8d%n",
                        file.getFileName(), fileStats.begins, fileStats.bodies, fileStats.ends
                );

                totalStats.begins += fileStats.begins;
                totalStats.bodies += fileStats.bodies;
                totalStats.ends += fileStats.ends;
            }

            System.out.println("--------------------------------------------------------------------------------");
            System.out.printf("%-45s | %-8d | %-8d | %-8d%n",
                    "TOTALS", totalStats.begins, totalStats.bodies, totalStats.ends
            );
            System.out.printf("Finished processing in %s\n", System.currentTimeMillis() - started);
        }
    }

    public static Stats analyzeFile(Path path)
    {
        Stats stats = new Stats();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r"))
        {
            long size = raf.length();
            if (size == 0) return stats;

            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, size);

            while (buffer.remaining() > 0)
            {
                int pos = buffer.position();
                byte marker = buffer.get();

                if (marker == 0)
                {
                    // Peek ahead to check if this is just padding or end of data
                    if (buffer.remaining() > 8 && buffer.getLong(buffer.position()) == 0) break;
                    continue;
                }

                try
                {
                    switch (marker)
                    {
                        case 0x01 ->
                        { // BEGIN
                            stats.begins++;
                            buffer.getInt();      // skip dir
                            skipData(buffer);     // skip reqId
                            skipData(buffer);     // skip startLine
                            int headerCount = buffer.getInt();
                            for (int i = 0; i < headerCount * 2; i++) skipData(buffer);
                        }
                        case 0x02 ->
                        { // BODY
                            stats.bodies++;
                            skipData(buffer);     // skip reqId
                            skipData(buffer);     // skip data
                        }
                        case 0x03 ->
                        { // END
                            stats.ends++;
                            skipData(buffer);     // skip reqId
                            buffer.getLong();     // timestamp
                        }
                        default ->
                        {
                            // Silently stop on unexpected bytes at the end of pre-allocated files
                            return stats;
                        }
                    }
                }
                catch (Exception e)
                {
                    break;
                }
            }
        }
        catch (IOException e)
        {
            System.err.println("Error reading " + path + ": " + e.getMessage());
        }
        return stats;
    }

    private static void skipData(ByteBuffer buffer)
    {
        int len = buffer.getInt();
        if (len <= 0) return;
        buffer.position(buffer.position() + len);
    }

    public static class Stats
    {
        public long begins, bodies, ends;
    }
}