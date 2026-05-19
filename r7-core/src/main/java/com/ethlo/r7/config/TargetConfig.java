package com.ethlo.r7.config;

import java.util.StringJoiner;

public record TargetConfig(String url)
{
    @Override
    public String toString()
    {
        return new StringJoiner(", ", TargetConfig.class.getSimpleName() + "[", "]")
                .add("url='" + url + "'")
                .toString();
    }
}