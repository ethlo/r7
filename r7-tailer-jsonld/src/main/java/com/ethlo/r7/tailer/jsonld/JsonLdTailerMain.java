package com.ethlo.r7.tailer.jsonld;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.json.JsonLdWriter;
import com.ethlo.r7.r7f.JournalFiles;
import com.ethlo.r7.tailer.TailerOutput;
import com.ethlo.r7.tailer.TailerRunner;

/**
 * Entry point for the standalone JSON tailer image (see {@code docs/journaling.md}).
 * <p>
 * Reads binary journals written by the gateway from {@code journal_dir} and streams one
 * JSON object per completed exchange to {@code output_path}, which defaults to standard
 * output so it can be picked up by whatever log forwarder the deployment already runs
 * (Promtail, Fluent Bit, Vector, a Docker logging driver, ...). Configured by {@code
 * jsonld-tailer.yaml} (path overridable via {@code JSONLD_TAILER_CONFIG}), the same YAML
 * conventions as the gateway's {@code routes.yaml}/{@code server.yaml}.
 */
public final class JsonLdTailerMain
{
    private static final Logger logger = LoggerFactory.getLogger(JsonLdTailerMain.class);

    private JsonLdTailerMain()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final JsonldTailerConfig config = TailerRunner.loadConfig(TailerRunner.configFile("JSONLD_TAILER_CONFIG", "jsonld-tailer.yaml"),
                JsonldTailerConfig.class, JsonldTailerConfig::standard);

        final String outputPath = config.outputPath();
        final boolean toStdOut = config.outputDir() == null && ("-".equals(outputPath) || "stdout".equalsIgnoreCase(outputPath));
        final RollingFileOutputStream rolling = config.outputDir() == null ? null
                : new RollingFileOutputStream(Paths.get(config.outputDir()), config.filePrefix(),
                config.maxFileSize().bytes(), config.maxFileAge().toMillis());
        final OutputStream out = rolling != null ? rolling : toStdOut ? System.out : openOutputFile(Paths.get(outputPath));

        logger.info("Tailing journals from '{}' -> '{}' (checkpoints in '{}', poll every {})",
                config.journalDir(),
                rolling != null ? "rotating files in " + config.outputDir() + " (max " + config.maxFileSize() + ", " + config.maxFileAge() + ")"
                        : toStdOut ? "stdout" : outputPath,
                config.checkpointDir(), config.pollInterval());

        final TailerOutput output = rolling == null ? out::flush : new TailerOutput()
        {
            @Override
            public void afterTick() throws IOException
            {
                rolling.rollIfStale();
            }

            @Override
            public void close() throws IOException
            {
                rolling.close();
            }
        };
        TailerRunner.run(config, new JsonLdWriter(out, config.prettyPrint(), config.hideEmptyFields()), output);
    }

    /**
     * Opens {@code outputPath} for appending, with the same permissions a journal segment
     * gets ({@link JournalFiles}) rather than whatever the process umask happens to allow.
     * <p>
     * This file carries the same request/response data the journals do - request lines,
     * headers the redaction policy let through and, at {@code FULL} level, bodies - so
     * leaving it world-readable under a permissive umask (022 is common) would undo exactly
     * the protection the journal segments themselves are deliberately created with.
     * {@link Files#newOutputStream} has no attribute overload, so the file is opened as a
     * channel instead; the attributes only take effect when the channel actually creates the
     * file - an append to one already on disk with wider permissions is deliberately not
     * narrowed here, the same carve-out {@link JournalFiles} documents for journal segments.
     */
    static OutputStream openOutputFile(final Path outputPath) throws IOException
    {
        final Path parent = outputPath.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        final Path attributeSource = parent != null ? parent : outputPath;
        return new BufferedOutputStream(Channels.newOutputStream(
                FileChannel.open(outputPath,
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE),
                        JournalFiles.fileAttributes(attributeSource))));
    }
}
