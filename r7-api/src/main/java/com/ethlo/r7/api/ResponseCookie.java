package com.ethlo.r7.api;

import java.time.Duration;

public record ResponseCookie(
        String name,
        String value,
        String domain,
        String path,
        Duration maxAge,
        boolean secure,
        boolean httpOnly,
        String sameSite
)
{
    public ResponseCookie
    {
        if (name == null || name.isBlank())
        {
            throw new IllegalArgumentException("Cookie name cannot be null or blank");
        }
    }

    // A builder-like static constructor for ease of use in the gateway filters
    public static ResponseCookie create(final String name, final String value)
    {
        return new ResponseCookie(name, value, null, "/", null, true, true, "Lax");
    }
}