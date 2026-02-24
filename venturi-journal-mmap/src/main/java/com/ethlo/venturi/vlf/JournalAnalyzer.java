package com.ethlo.venturi.vlf;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.journal.api.ExchangeCompletionListener;
import com.ethlo.venturi.journal.api.JournalExchange;

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

    public Stats analyze() throws IOException
    {
        final long started = System.currentTimeMillis();
        VenturiTailer tailer = new VenturiTailer(journalDir, Duration.ofMinutes(1), this);
        tailer.runTick();

        logger.info("Analyzed {} full exchanges in {}ms",
                stats.completedExchanges, System.currentTimeMillis() - started
        );
        return stats;
    }

    @Override
    public void onComplete(JournalExchange exchange)
    {
        // One call = one fully reconstructed Request/Response pair
        exchange.getRequestHeaders().forEach((name, value) -> {
            //System.out.println(name + " - " + value);
        });
        stats.completedExchanges++;
    }

    public static class Stats
    {
        public long completedExchanges = 0;
    }
}