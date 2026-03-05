package com.ethlo.venturi.config;

import java.util.StringJoiner;

public record TargetConfig(
        String url,
        Integer weight
)
{
    @Override
    public String toString()
    {
        final StringJoiner builder = new StringJoiner(", ", TargetConfig.class.getSimpleName() + "[", "]");
        builder.add("url='" + url + "'");
        if (weight != null)
        {
            builder.add("weight=" + weight);
        }
        return builder.toString();
    }
}