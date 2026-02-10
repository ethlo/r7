package com.ethlo.venturi.loggers.slf4j;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.HttpLogger;
import com.ethlo.venturi.core.model.WebExchangeDataProvider;
import com.ethlo.venturi.loggers.rendering.AccessLogTemplateRenderer;

public class Slf4jFileLogger implements HttpLogger
{
    private static final Logger accessLogLogger = LoggerFactory.getLogger("access-log");

    private final AccessLogTemplateRenderer accessLogTemplateRenderer;


    public Slf4jFileLogger(final AccessLogTemplateRenderer accessLogTemplateRenderer)
    {
        this.accessLogTemplateRenderer = accessLogTemplateRenderer;
    }

    public void accessLog(final WebExchangeDataProvider dataProvider)
    {
        final Map<String, Object> metaMap = dataProvider.asMetaMap();
        accessLogLogger.info(accessLogTemplateRenderer.render(metaMap));
    }

    @Override
    public String getName()
    {
        return "slf4j";
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + " - pattern='" + accessLogTemplateRenderer.getPattern() + "'";
    }

    @Override
    public void close()
    {
        // Nothing
    }
}