package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JournalAnalyzer implements JournalEventListener
{
    private static final Logger logger = LoggerFactory.getLogger(JournalAnalyzer.class);
    private final Path journalDir;
    private final Stats stats = new Stats();

    public JournalAnalyzer(Path journalDir)
    {
        this.journalDir = journalDir;
    }

    public static void main(String[] args) throws IOException
    {
        String path = args.length > 0 ? args[0] : "/tmp/venturi/";
        Path journalDir = Paths.get(path);

        if (!Files.exists(journalDir))
        {
            logger.error("Directory does not exist: {}", path);
            return;
        }

        new JournalAnalyzer(journalDir).analyze();
    }

    public Stats analyze() throws IOException
    {
        final long started = System.currentTimeMillis();

        logger.info("Processing journal shards...");
        logger.info("--------------------------------------------------------------------------------");
        logger.info(String.format("%-45s | %-8s | %-8s | %-8s", "Shard Name", "Begins", "Bodies", "Ends"));
        logger.info("--------------------------------------------------------------------------------");

        VenturiTailer tailer = new VenturiTailer(journalDir, this);
        tailer.runTick();

        logger.info("--------------------------------------------------------------------------------");
        logger.info(String.format("%-45s | %-8d | %-8d | %-8d",
                "TOTALS", stats.begins, stats.bodies, stats.ends
        ));
        logger.info("Finished processing in {}ms", System.currentTimeMillis() - started);
        return stats;
    }

    @Override
    public void onBegin(int direction, String reqId, String startLine, Map<String, String> headers)
    {
        stats.begins++;
    }

    @Override
    public void onBody(String reqId, ByteBuffer body)
    {
        stats.bodies++;
    }

    @Override
    public void onEnd(String reqId, long timestamp, int status, long bytesSent, long bytesReceived, long durationNanos)
    {
        stats.ends++;
    }

    public static class Stats
    {
        public long begins, bodies, ends;
    }
}