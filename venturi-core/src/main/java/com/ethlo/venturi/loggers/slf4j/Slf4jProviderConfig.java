package com.ethlo.venturi.loggers.slf4j;

import com.ethlo.venturi.BaseProviderConfig;

public class Slf4jProviderConfig extends BaseProviderConfig
{
    private final String pattern;

    protected Slf4jProviderConfig(final boolean enabled, final String pattern)
    {
        super(enabled);
        this.pattern = pattern;
    }

    public String getPattern()
    {
        return pattern;
    }
}
