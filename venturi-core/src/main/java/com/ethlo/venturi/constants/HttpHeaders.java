package com.ethlo.venturi.constants;

public final class HttpHeaders
{
    // Request Headers
    public static final String AUTHORIZATION = "Authorization";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_ENCODING = "Content-Encoding";
    public static final String CONTENT_LENGTH = "Content-Length";
    public static final String USER_AGENT = "User-Agent";
    public static final String ACCEPT = "Accept";
    public static final String HOST = "Host";

    // Response Headers
    public static final String SET_COOKIE = "Set-Cookie";
    public static final String LOCATION = "Location";
    public static final String SERVER = "Server";

    public static final String CACHE_CONTROL = "Cache-Control";
    public static final CharSequence IF_MODIFIED_SINCE = "If-Modified-Since";
    public static final CharSequence IF_NONE_MATCH = "If-None-Match";
    public static final CharSequence PRAGMA = "Pragma";

    // Gateway Custom Headers (Standardized)
    public static final String X_CORRELATION_ID = "X-Correlation-Id";
    public static final String X_REQUEST_ID = "X-Request-Id";
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private HttpHeaders()
    {
    }
}