package com.ethlo.r7.tailer.app;

import java.io.IOException;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.json.JsonLinesWriter;
import com.ethlo.r7.tailer.TailerOutput;
import com.ethlo.r7.tailer.TailerRunner;
import com.ethlo.r7.tailer.json.BodyFiles;
import com.ethlo.r7.tailer.json.RollingFileOutputStream;
import com.ethlo.r7.warc.PayloadDedupIndex;
import com.ethlo.r7.warc.WarcExchangeWriter;
import com.ethlo.r7.warc.WarcFileWriter;

/**
 * Entry point for the tailer image (see {@code docs/journaling.md}): reads the gateway's
 * journals once and writes each exchange to the enabled outputs, WARC files and JSON lines.
 * Configured by {@code tailer.yaml} in the working directory (path overridable via
 * {@code TAILER_CONFIG}).
 */
public final class TailerMain
{
    private static final Logger logger = LoggerFactory.getLogger(TailerMain.class);

    private TailerMain()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final TailerAppConfig config = TailerRunner.loadConfig(TailerRunner.configFile("TAILER_CONFIG", "tailer.yaml"),
                TailerAppConfig.class, TailerAppConfig::standard);
        final WarcOutputConfig warcConfig = config.warc();
        final JsonOutputConfig jsonConfig = config.json();

        final WarcFileWriter warcFiles = warcConfig.enabled()
                ? new WarcFileWriter(Paths.get(warcConfig.outputDir()), warcConfig.filePrefix(), warcConfig.maxFileSize().bytes(),
                warcConfig.maxFileAge().toMillis(), warcConfig.zstdLevel(), warcConfig.cdxjIndex())
                : null;
        final RollingFileOutputStream jsonFiles = jsonConfig.enabled() && jsonConfig.output() == JsonOutputConfig.Output.FILE
                ? new RollingFileOutputStream(Paths.get(jsonConfig.outputDir()), jsonConfig.filePrefix(), jsonConfig.maxFileSize().bytes(),
                jsonConfig.maxFileAge().toMillis())
                : null;

        final BodyFilesConfig bodyConfig = jsonConfig.bodyFiles();
        final BodyFiles bodyFiles = jsonConfig.enabled() && jsonConfig.bodies() == JsonOutputConfig.Bodies.FILES
                ? new BodyFiles(Paths.get(bodyConfig.outputDir()), bodyConfig.filePrefix(), bodyConfig.maxFileSize().bytes(),
                bodyConfig.maxFileAge().toMillis(), bodyConfig.zstdLevel(), bodyConfig.dedupCacheEntries())
                : null;

        final WarcExchangeWriter warc = warcFiles != null ? new WarcExchangeWriter(warcFiles, new PayloadDedupIndex(warcConfig.dedupCacheEntries()), warcConfig.bodies()) : null;
        final JsonLinesWriter json;
        if (!jsonConfig.enabled())
        {
            json = null;
        }
        else if (bodyFiles != null)
        {
            json = new JsonLinesWriter(jsonFiles != null ? jsonFiles : System.out, bodyFiles);
        }
        else
        {
            json = new JsonLinesWriter(jsonFiles != null ? jsonFiles : System.out, jsonConfig.bodies() == JsonOutputConfig.Bodies.INLINE);
        }

        logger.info("Tailing journals from '{}' (checkpoints in '{}', poll every {}) -> WARC: {}; JSON: {}; JSON bodies: {}",
                config.journalDir(), config.checkpointDir(), config.pollInterval(),
                warcConfig.enabled() ? warcConfig.exchanges().name().toLowerCase() + " exchanges to " + warcConfig.outputDir()
                        + " (max " + warcConfig.maxFileSize().bytes() + " bytes, " + warcConfig.maxFileAge() + ", zstd level " + warcConfig.zstdLevel()
                        + ", CDXJ index " + (warcConfig.cdxjIndex() ? "on" : "off") + ")" : "off",
                !jsonConfig.enabled() ? "off" : jsonFiles != null
                        ? "files in " + jsonConfig.outputDir() + " (max " + jsonConfig.maxFileSize().bytes() + " bytes, " + jsonConfig.maxFileAge() + ")"
                        : "stdout",
                bodyFiles == null ? jsonConfig.bodies().name().toLowerCase()
                        : "files in " + bodyConfig.outputDir() + " (max " + bodyConfig.maxFileSize().bytes() + " bytes, " + bodyConfig.maxFileAge()
                        + ", zstd level " + bodyConfig.zstdLevel() + ")");

        TailerRunner.run(config, new ExchangeFanOut(warc, warcConfig.exchanges(), json), new Outputs(warcFiles, jsonFiles, bodyFiles));
    }

    /**
     * Seals stale files after every read and closes them on shutdown, for each output that
     * writes files. A failure in one does not keep the others from their turn.
     */
    record Outputs(WarcFileWriter warc, RollingFileOutputStream json, BodyFiles bodies) implements TailerOutput
    {
        @Override
        public void afterTick() throws IOException
        {
            IOException failure = null;
            if (warc != null)
            {
                try
                {
                    warc.rollIfStale();
                }
                catch (final IOException e)
                {
                    failure = e;
                }
            }
            if (json != null)
            {
                try
                {
                    json.rollIfStale();
                }
                catch (final IOException e)
                {
                    failure = addTo(failure, e);
                }
            }
            if (bodies != null)
            {
                try
                {
                    bodies.rollIfStale();
                }
                catch (final IOException e)
                {
                    failure = addTo(failure, e);
                }
            }
            if (failure != null)
            {
                throw failure;
            }
        }

        @Override
        public void close() throws IOException
        {
            IOException failure = null;
            if (warc != null)
            {
                try
                {
                    warc.close();
                }
                catch (final IOException e)
                {
                    failure = e;
                }
            }
            if (json != null)
            {
                try
                {
                    json.close();
                }
                catch (final IOException e)
                {
                    failure = addTo(failure, e);
                }
            }
            else
            {
                System.out.flush();
            }
            if (bodies != null)
            {
                try
                {
                    bodies.close();
                }
                catch (final IOException e)
                {
                    failure = addTo(failure, e);
                }
            }
            if (failure != null)
            {
                throw failure;
            }
        }

        private static IOException addTo(final IOException failure, final IOException e)
        {
            if (failure == null)
            {
                return e;
            }
            failure.addSuppressed(e);
            return failure;
        }
    }
}
