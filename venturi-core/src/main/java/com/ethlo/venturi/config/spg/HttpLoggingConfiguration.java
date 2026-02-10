package com.ethlo.venturi.config.spg;


import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ethlo.venturi.LogFilter;
import com.ethlo.venturi.api.GatewayPredicate;

public class HttpLoggingConfiguration
{
    public static final long DEFAULT_MAX_MEMORY_BUFFER = 0L;

    private CaptureConfiguration capture;
    private LogFilter filter;
    private Map<String, Map<String, Object>> providers;
    private List<GatewayPredicate> matchers;
    private long maxMemoryBuffer = DEFAULT_MAX_MEMORY_BUFFER;

    private boolean async;

    public LogFilter getFilter()
    {
        return filter;
    }

    public HttpLoggingConfiguration setFilter(final LogFilter filter)
    {
        this.filter = Optional.ofNullable(filter).orElse(new LogFilter());
        return this;
    }

    public Map<String, Map<String, Object>> getProviders()
    {
        return providers;
    }

    public HttpLoggingConfiguration setProviders(final Map<String, Map<String, Object>> providers)
    {
        this.providers = providers;
        return this;
    }

    public List<GatewayPredicate> getMatchers()
    {
        return Optional.ofNullable(matchers).orElse(Collections.emptyList());
    }

    public void setMatchers(final List<GatewayPredicate> matchers)
    {
        this.matchers = matchers;
    }

    public long maxMemoryBuffer()
    {
        return maxMemoryBuffer;
    }

    public HttpLoggingConfiguration setMaxMemoryBuffer(final long maxMemoryBuffer)
    {
        this.maxMemoryBuffer = maxMemoryBuffer;
        return this;
    }

    public CaptureConfiguration getCapture()
    {
        return capture;
    }

    public HttpLoggingConfiguration setCapture(final CaptureConfiguration capture)
    {
        this.capture = capture;
        return this;
    }

    public boolean async()
    {
        return async;
    }

    public HttpLoggingConfiguration setAsync(final boolean async)
    {
        this.async = async;
        return this;
    }
}