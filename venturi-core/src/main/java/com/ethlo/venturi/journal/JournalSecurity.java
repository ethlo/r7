package com.ethlo.venturi.journal;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JournalSecurity
{
    private static final Set<String> COMMON_SAFE = Set.of(
            "connection",
            "keep-alive",
            "upgrade",
            "te",
            "x-request-id",
            "x-correlation-id",
            "x-b3-traceid",
            "x-b3-spanid",
            "x-b3-parentspanid",
            "x-b3-sampled",
            "x-b3-flags",
            "b3",
            "traceparent",
            "tracestate",
            "date"
    );

    public static final Set<String> SAFE_REQUEST_HEADERS = Collections.unmodifiableSet(
            Stream.concat(COMMON_SAFE.stream(), Stream.of(
                            "host",
                            "user-agent",
                            "accept",
                            "accept-encoding",
                            "accept-language",
                            "accept-charset",
                            "content-type",
                            "content-length",
                            "dnt",
                            "origin",
                            "sec-ch-ua",
                            "sec-ch-ua-mobile",
                            "sec-ch-ua-platform",
                            "cache-control",
                            "pragma",
                            "if-match",
                            "if-none-match",
                            "if-modified-since",
                            "if-unmodified-since",
                            "x-forwarded-for",
                            "x-forwarded-proto",
                            "x-forwarded-host",
                            "x-forwarded-port",
                            "x-real-ip",
                            "forwarded",
                            "via"
                    )
            ).collect(Collectors.toSet())
    );

    public static final Set<String> SAFE_RESPONSE_HEADERS = Collections.unmodifiableSet(
            Stream.concat(COMMON_SAFE.stream(), Stream.of(
                            "server",
                            "x-powered-by",
                            "content-type",
                            "content-length",
                            "content-encoding",
                            "content-language",
                            "content-disposition",
                            "etag",
                            "vary",
                            "expires",
                            "age",
                            "retry-after",
                            "access-control-allow-origin",
                            "access-control-allow-methods",
                            "access-control-allow-headers",
                            "access-control-expose-headers",
                            "access-control-max-age",
                            "access-control-allow-credentials",
                            "x-content-type-options",
                            "x-frame-options",
                            "strict-transport-security"
                    )
            ).collect(Collectors.toSet())
    );
}