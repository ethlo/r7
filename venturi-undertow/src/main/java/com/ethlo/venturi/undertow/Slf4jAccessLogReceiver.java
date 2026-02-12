package com.ethlo.venturi.undertow;

import org.slf4j.Logger;

import io.undertow.server.handlers.accesslog.AccessLogReceiver;

public class Slf4jAccessLogReceiver implements AccessLogReceiver
{
    private final Logger logger;

    public Slf4jAccessLogReceiver(final Logger logger)
    {
        this.logger = logger;
    }

    public void logMessage(String message)
    {
        logger.info("{}", message);
    }
}