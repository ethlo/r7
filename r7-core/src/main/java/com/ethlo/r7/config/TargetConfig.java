package com.ethlo.r7.config;

import java.util.StringJoiner;

public record TargetConfig(
        String url,
        String healthPath
)
{
    @Override
    public String toString()
    {
        return new StringJoiner(", ", TargetConfig.class.getSimpleName() + "[", "]")
                .add("url='" + url + "'")
                .add("healthPath='" + healthPath + "'")
                .toString();
    }
}