package com.ethlo.r7.core;

import com.ethlo.r7.api.Cookie;

public record SimpleCookie(String name, String value) implements Cookie
{
    public SimpleCookie
    {
        if (name == null || name.isBlank())
        {
            throw new IllegalArgumentException("Cookie name cannot be null or blank");
        }
    }
}