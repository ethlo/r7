package com.ethlo.venturi;

public abstract class BaseProviderConfig
{
    private final boolean enabled;

    protected BaseProviderConfig(final boolean enabled)
    {
        this.enabled = enabled;
    }

    public boolean isEnabled()
    {
        return enabled;
    }
}
