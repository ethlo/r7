package com.ethlo.venturi.loggers.slf4j;

import java.util.Map;
import java.util.function.BiFunction;

import com.ethlo.venturi.HttpLogger;
import com.ethlo.venturi.loggers.rendering.PebbleAccessLogTemplateRenderer;

public class Slf4jHttpLoggerFactory
{

    public HttpLogger getInstance(final Map<String, Object> configuration, BiFunction<String, Object, Object> beanRegistration)
    {
        final Slf4jProviderConfig fileProviderConfig = null;
        return new Slf4jFileLogger(new PebbleAccessLogTemplateRenderer(fileProviderConfig.getPattern(), false));
    }
}
