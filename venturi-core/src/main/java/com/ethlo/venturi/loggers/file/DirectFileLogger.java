package com.ethlo.venturi.loggers.file;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import com.ethlo.venturi.HttpLogger;
import com.ethlo.venturi.core.GatewayExchangeDataWriter;
import com.ethlo.venturi.core.model.WebExchangeDataProvider;
import com.ethlo.venturi.loggers.rendering.AccessLogTemplateRenderer;

public class DirectFileLogger implements HttpLogger
{
    private final AccessLogTemplateRenderer accessLogTemplateRenderer;
    private final Path storageDirectory;
    private final long maxRolloverSize;
    private long currentSize;
    private LocalDate currentDate;
    private OutputStream destination;

    public DirectFileLogger(AccessLogTemplateRenderer accessLogTemplateRenderer, Path storageDirectory, long maxRolloverSize, GatewayExchangeDataWriter repository)
    {
        this.accessLogTemplateRenderer = accessLogTemplateRenderer;
        this.storageDirectory = Objects.requireNonNull(storageDirectory, "storageDirectory cannot be null");
        this.maxRolloverSize = maxRolloverSize;

        try
        {
            init();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Unable to initialize logger: " + e.getMessage(), e);
        }
    }

    private void init() throws IOException
    {
        Files.createDirectories(storageDirectory);
        final Path activePath = storageDirectory.resolve("access.log");

        this.currentDate = LocalDate.now();
        this.destination = new BufferedOutputStream(Files.newOutputStream(activePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        ));

        // Check if the existing file is already over the limit from a previous run
        this.currentSize = Files.size(activePath);
        if (currentSize > maxRolloverSize)
        {
            roll(currentDate);
        }
    }

    @Override
    public void accessLog(WebExchangeDataProvider data)
    {
        try
        {
            final String logLine = accessLogTemplateRenderer.render(data.asMetaMap()) + "\n";
            writeToRollingFile(logLine.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private void writeToRollingFile(byte[] bytes) throws IOException
    {
        final LocalDate now = LocalDate.now();

        // Trigger roll if the date changed OR the file grew too large
        if (!now.equals(currentDate) || (currentSize + bytes.length > maxRolloverSize))
        {
            roll(now);
        }

        destination.write(bytes);
        currentSize += bytes.length;
        destination.flush();
    }

    private void roll(LocalDate newDate) throws IOException
    {
        if (destination != null)
        {
            destination.flush();
            destination.close();
        }

        final Path activeLog = storageDirectory.resolve("access.log");

        if (Files.exists(activeLog))
        {
            // Add a timestamp to the rolled file: access.log.2026-02-05.143005.log
            final String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd.HHmmss").format(LocalDateTime.now());
            final Path rolledLog = storageDirectory.resolve("access.log." + timestamp + ".log");
            Files.move(activeLog, rolledLog, StandardCopyOption.REPLACE_EXISTING);
        }

        // Open new buffered stream for the next batch of logs
        this.destination = new BufferedOutputStream(Files.newOutputStream(activeLog,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        ));

        this.currentSize = Files.exists(activeLog) ? Files.size(activeLog) : 0L;
        this.currentDate = newDate;
    }

    @Override
    public String getName()
    {
        return "direct_file";
    }

    @Override
    public void close() throws Exception
    {
        this.destination.close();
    }
}