package com.ethlo.r7.reaper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.config.YamlConfigSupport;
import com.ethlo.r7.validation.ValidationResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Entry point for the standalone reaper image (see {@code docs/journaling.md}).
 * <p>
 * Deletes sealed journal segments from {@code journal_dir} once they are older than {@code
 * ttl}, or earlier once every tailer listed in {@code tailers} is done with them (see
 * {@link ReaperConfig}).
 * Configured by {@code reaper.yaml} (path overridable via {@code REAPER_CONFIG}), the same
 * YAML conventions as the gateway's {@code routes.yaml}/{@code server.yaml}.
 */
public final class ReaperMain
{
    private static final Logger logger = LoggerFactory.getLogger(ReaperMain.class);

    private ReaperMain()
    {
    }

    public static void main(final String[] args) throws InterruptedException
    {
        final Path configFile = Paths.get(System.getenv().getOrDefault("REAPER_CONFIG", "config/reaper.yaml"));
        final ReaperConfig config = loadConfig(configFile);

        final Path journalDir = Paths.get(config.journalDir());
        final Duration ttl = config.ttl();
        final Duration pollInterval = config.pollInterval();

        final java.util.List<Path> tailers = config.tailers().stream().map(Paths::get).toList();
        if (tailers.isEmpty())
        {
            logger.info("Reaping sealed segments in '{}' older than {} (checking every {})", journalDir, ttl, pollInterval);
        }
        else
        {
            logger.info("Reaping sealed segments in '{}' once {} old and done with by every tailer in {}, or older than {} (checking every {})",
                    journalDir, config.minAge(), tailers, ttl, pollInterval);
        }

        final JournalReaper reaper = new JournalReaper(journalDir, ttl, tailers, config.minAge());

        while (!Thread.currentThread().isInterrupted())
        {
            try
            {
                final int deleted = reaper.sweep();
                if (deleted > 0)
                {
                    logger.info("Reaped {} segment(s) from {}", deleted, journalDir);
                }
            }
            catch (final IOException e)
            {
                logger.error("Error while reaping journals in {}", journalDir, e);
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    private static ReaperConfig loadConfig(final Path configFile)
    {
        if (!Files.exists(configFile))
        {
            logger.info("No reaper.yaml file found at {}. Using defaults", configFile.toAbsolutePath());
            return ReaperConfig.standard();
        }

        logger.info("Loading reaper settings from {}", configFile.toAbsolutePath());
        final ObjectMapper mapper = YamlConfigSupport.baseMapperBuilder().build();
        ReaperConfig config = YamlConfigSupport.load(mapper, configFile, ReaperConfig.class);
        if (config == null)
        {
            logger.warn("No settings found in reaper.yaml, using only defaults");
            config = ReaperConfig.standard();
        }
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        result.throwIfInvalid();
        return config;
    }
}
