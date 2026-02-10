package com.ethlo.venturi.loggers.file;

import java.nio.file.Path;
import java.util.Optional;

import com.ethlo.venturi.BaseProviderConfig;

public class DirectFileProviderConfig extends BaseProviderConfig
{
    private final String pattern;

    private final Path storageDirectory;

    private final Long maxRolloverSize;

    public DirectFileProviderConfig(final boolean enabled, final String pattern, final Path storageDirectory, final Long maxRolloverSize)
    {
        super(enabled);
        this.pattern = pattern;
        this.storageDirectory = storageDirectory;
        this.maxRolloverSize = maxRolloverSize;
    }

    public String pattern()
    {
        return pattern;
    }

    public Path storageDirectory()
    {
        return storageDirectory;
    }

    public long maxRolloverSize()
    {
        return Optional.ofNullable(maxRolloverSize).orElse(10L * 1024L * 1024L * 1024L);
    }
}
