package com.ethlo.venturi.journal;

import java.util.HashSet;
import java.util.Set;

public class JournalSecurity
{
    public static final Set<String> DEFAULT_SAFE_HEADERS = new HashSet<>(Set.of(
            // --- Routing & Network Identity ---
            "host",
            "x-forwarded-for",
            "x-forwarded-proto",
            "x-forwarded-host",
            "x-forwarded-port",
            "x-real-ip",
            "forwarded",
            "via",

            // --- Content & Negotiation ---
            "accept",
            "accept-encoding",
            "accept-language",
            "accept-charset",
            "content-type",
            "content-length",
            "content-encoding",
            "content-language",

            // --- Client Details ---
            "user-agent",
            "sec-ch-ua",
            "sec-ch-ua-mobile",
            "sec-ch-ua-platform",
            "connection",
            "keep-alive",
            "upgrade",
            "te",

            // --- Caching & Conditional Requests ---
            "cache-control",
            "pragma",
            "etag",
            "if-match",
            "if-none-match",
            "if-modified-since",
            "if-unmodified-since",
            "vary",
            "expires",
            "age",

            // --- CORS (Cross-Origin Resource Sharing) ---
            "origin",
            "access-control-request-method",
            "access-control-request-headers",
            "access-control-allow-origin",
            "access-control-allow-methods",
            "access-control-allow-headers",
            "access-control-expose-headers",
            "access-control-max-age",
            // Note: access-control-allow-credentials is safe because it just contains "true", not the credential itself.
            "access-control-allow-credentials",

            // --- Tracing & Correlation (Crucial for Gateways) ---
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

            // --- Standard Response Metadata ---
            "date",
            "server",
            "x-powered-by",
            "retry-after"
    ));
}
