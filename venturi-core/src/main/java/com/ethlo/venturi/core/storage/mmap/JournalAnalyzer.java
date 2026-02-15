package com.ethlo.venturi.core.storage.mmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-performance analyzer for Venturi journals.
 * Acts as the final sink (ExchangeCompletionListener) to verify reassembly.
 */
public class JournalAnalyzer implements ExchangeCompletionListener
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

        // The Tailer now orchestrates the Decoder and Reassembler internally
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
    public void onComplete(JournalExchange exchange)
    {
        // Track stats from the fully reconstructed exchange
        stats.begins++;

        // Count body fragments from both request and response
        stats.bodies += (exchange.getRequestBodyFragments().size() + exchange.getResponseBodyFragments().size());

        stats.ends++;

        if (logger.isDebugEnabled())
        {
            logger.debug("Analyzed exchange: {} [Status: {}]", exchange.getRequestId(), exchange.getStatus());
        }
    }

    public static class Stats
    {
        public long begins = 0;
        public long bodies = 0;
        public long ends = 0;
    }
}