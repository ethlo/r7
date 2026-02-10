package com.ethlo.venturi.loggers.file;

import java.util.Map;
import java.util.function.BiFunction;

import com.ethlo.venturi.HttpLogger;
import com.ethlo.venturi.core.GatewayExchangeDataWriter;
import com.ethlo.venturi.loggers.rendering.PebbleAccessLogTemplateRenderer;

public class DirectHttpLoggerFactory
{
    public HttpLogger getInstance(final Map<String, Object> configuration, BiFunction<String, Object, Object> beanRegistration)
    {
        final DirectFileProviderConfig config = null;
        final GatewayExchangeDataWriter dataBufferRepository = null;
        return new DirectFileLogger(new PebbleAccessLogTemplateRenderer(config.pattern(), false), config.storageDirectory(), config.maxRolloverSize(), dataBufferRepository);
    }
}
