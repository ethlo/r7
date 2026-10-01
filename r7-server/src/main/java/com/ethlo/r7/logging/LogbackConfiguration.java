package com.ethlo.r7.logging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter2;

/**
 * Configures logback the same way for every standalone server: from {@code R7_LOGBACK_CONFIG}
 * (default {@code config/logback.xml}) when that file exists, otherwise from the bundled
 * {@code default-logback.xml}. Without this, logback falls back to its built-in configuration,
 * which logs everything at DEBUG.
 * <p>
 * A servlet container owns its own logging, so this is for servers that own the process.
 */
public final class LogbackConfiguration
{
    private LogbackConfiguration()
    {
    }

    /**
     * @return the configured context, for a server that adds appenders of its own; those must be
     * added after this call, as configuring resets the context
     */
    public static LoggerContext configure()
    {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try (final InputStream configStream = configStream())
        {
            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();
            configurator.doConfigure(configStream);
        }
        catch (final JoranException | IOException e)
        {
            System.err.println("FATAL: Logback failed to configure from XML: " + e.getMessage());
        }
        finally
        {
            new StatusPrinter2().printInCaseOfErrorsOrWarnings(context);
        }
        return context;
    }

    private static InputStream configStream()
    {
        final String logbackConfigPath = System.getenv().getOrDefault("R7_LOGBACK_CONFIG", "config/logback.xml");
        final Path configFilePath = Paths.get(logbackConfigPath).toAbsolutePath();
        if (Files.isRegularFile(configFilePath))
        {
            try
            {
                return Files.newInputStream(configFilePath, StandardOpenOption.READ);
            }
            catch (final IOException e)
            {
                System.err.println("FATAL: Logback failed to read from config file: " + e.getMessage());
            }
        }

        final InputStream defaultConfig = LogbackConfiguration.class.getResourceAsStream("/default-logback.xml");
        if (defaultConfig == null)
        {
            throw new IllegalStateException("FATAL: Unable to load classpath:/default-logback.xml");
        }
        return defaultConfig;
    }
}
