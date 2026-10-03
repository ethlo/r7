package com.ethlo.r7.tailer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.config.YamlConfigSupport;
import com.ethlo.r7.journal.api.ExchangeCompletionListener;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.R7Tailer;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import tools.jackson.databind.ObjectMapper;

/**
 * The part every tailer shares: loading its YAML config, and the read loop around
 * {@link R7Tailer} with one failure policy and one shutdown order. A tailer supplies what it
 * writes ({@link ExchangeCompletionListener}) and how its output is flushed or closed
 * ({@link TailerOutput}); see {@code design/tailers.md}.
 */
public final class TailerRunner
{
    private static final Logger logger = LoggerFactory.getLogger(TailerRunner.class);

    private TailerRunner()
    {
    }

    /**
     * The config file named by {@code envVar}, or {@code defaultFile} in the working directory.
     */
    public static Path configFile(final String envVar, final String defaultFile)
    {
        return Paths.get(System.getenv().getOrDefault(envVar, defaultFile));
    }

    /**
     * Loads and validates {@code configFile}. A missing or empty file means all defaults.
     */
    public static <C extends ValidatableConfig> C loadConfig(final Path configFile, final Class<C> type, final Supplier<C> defaults)
    {
        if (!Files.exists(configFile))
        {
            logger.info("No config file found at {}. Using defaults", configFile.toAbsolutePath());
            return defaults.get();
        }

        logger.info("Loading settings from {}", configFile.toAbsolutePath());
        final ObjectMapper mapper = YamlConfigSupport.baseMapperBuilder().build();
        C config = YamlConfigSupport.load(mapper, configFile, type);
        if (config == null)
        {
            logger.warn("No settings found in {}, using only defaults", configFile);
            config = defaults.get();
        }
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        result.throwIfInvalid();
        return config;
    }

    /**
     * Reads journals and hands each exchange to {@code listener} until the process is stopped.
     * Never returns normally.
     */
    public static void run(final TailerConfig config, final ExchangeCompletionListener listener, final TailerOutput output)
            throws InterruptedException
    {
        final Path journalDir = Paths.get(config.journalDir());
        // Retention is a dedicated reaper's job, applied uniformly across every tailer sharing
        // the journal directory: a tailer never deletes a segment itself.
        final R7Tailer tailer = new R7Tailer(journalDir, Paths.get(config.checkpointDir()), listener,
                new LoggingIntegrityListener(), ReassemblyOptions.DEFAULTS);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            // A container restart or rolling deploy stops the process with SIGTERM, which runs
            // this hook. Saving the checkpoint here resumes from where reading got to, not from
            // the last tick, and it must happen before the output is closed. Exchanges still
            // being assembled are completed after the restart either way, from where the
            // checkpoint file says they began; a hard crash, which runs no hook, only loses the
            // progress since the last tick.
            tailer.shutdown();
            try
            {
                output.close();
            }
            catch (final IOException e)
            {
                logger.warn("Failed to close the tailer output on shutdown", e);
            }
        }, "tailer-shutdown"));

        while (!Thread.currentThread().isInterrupted())
        {
            try
            {
                tailer.runTick();
            }
            catch (final IOException | RuntimeException e)
            {
                // A listener reports a failed write as an unchecked exception (onComplete cannot
                // declare a checked one), and R7Tailer re-offers that exchange on the next tick.
                // Catching only IOException would let that failure end the process instead of
                // reaching the retry it was designed for.
                logger.error("Error while tailing journals in {}", journalDir, e);
            }
            try
            {
                // Runs even when the read failed: an exchange that keeps failing must not keep
                // the records written before it from being sealed on time.
                output.afterTick();
            }
            catch (final IOException | RuntimeException e)
            {
                logger.error("Error in the tailer output after reading {}", journalDir, e);
            }
            // Returns as soon as the gateway commits something, or after poll_interval.
            tailer.awaitNewData(config.pollInterval());
        }
    }
}
