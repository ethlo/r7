package com.ethlo.r7.r7f;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.JournalExchange;

/**
 * High-performance analyzer for R7 journals.
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
        R7Tailer tailer = new R7Tailer(journalDir, Duration.ofMinutes(1), this);
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
        exchange.getClientRequestHeaders().forEach((name, value) -> {
            //System.out.println(name + " - " + value);
        });
        stats.completedExchanges++;
    }

    public static class Stats
    {
        public long completedExchanges = 0;
    }
}