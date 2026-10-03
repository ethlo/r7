package com.ethlo.r7.tailer.warc;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.tailer.TailerOutput;
import com.ethlo.r7.tailer.TailerRunner;
import com.ethlo.r7.warc.PayloadDedupIndex;
import com.ethlo.r7.warc.WarcExchangeWriter;
import com.ethlo.r7.warc.WarcFileWriter;

/**
 * Entry point for the standalone WARC/zstd tailer image (see {@code docs/journaling.md}).
 * <p>
 * Reads binary journals written by the gateway from {@code journal_dir} and writes them as
 * {@code .warc.zst} files (WARC 1.1, one independent Zstandard frame per record) to
 * {@code output_dir}, rotating by size or age. Configured by {@code warc-tailer.yaml} (path
 * overridable via {@code WARC_TAILER_CONFIG}), the same YAML conventions as the gateway's
 * {@code routes.yaml}/{@code server.yaml}.
 */
public final class WarcTailerMain
{
    private static final Logger logger = LoggerFactory.getLogger(WarcTailerMain.class);

    private WarcTailerMain()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final WarcTailerConfig config = TailerRunner.loadConfig(TailerRunner.configFile("WARC_TAILER_CONFIG", "warc-tailer.yaml"),
                WarcTailerConfig.class, WarcTailerConfig::standard);

        final long maxFileSizeBytes = config.maxFileSize().bytes();
        final Duration maxFileAge = config.maxFileAge();
        final int zstdLevel = config.zstdLevel();
        final int dedupCacheEntries = config.dedupCacheEntries();
        final boolean cdxjIndex = config.cdxjIndex();

        logger.info("Tailing journals from '{}' -> WARC files in '{}' (checkpoints in '{}', max file size {} bytes, max file age {}, "
                        + "zstd level {}, dedup cache {} entries, poll every {}, CDXJ index {})",
                config.journalDir(), config.outputDir(), config.checkpointDir(), maxFileSizeBytes, maxFileAge, zstdLevel, dedupCacheEntries,
                config.pollInterval(), cdxjIndex ? "on" : "off");

        final WarcFileWriter warcFileWriter = new WarcFileWriter(Paths.get(config.outputDir()), config.filePrefix(), maxFileSizeBytes,
                maxFileAge.toMillis(), zstdLevel, cdxjIndex);
        final WarcExchangeWriter warcWriter = new WarcExchangeWriter(warcFileWriter, new PayloadDedupIndex(dedupCacheEntries));
        TailerRunner.run(config, warcWriter, new TailerOutput()
        {
            @Override
            public void afterTick() throws IOException
            {
                warcFileWriter.rollIfStale();
            }

            @Override
            public void close() throws IOException
            {
                warcFileWriter.close();
            }
        });
    }
}
