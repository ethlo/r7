package com.ethlo.r7.journal;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
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
            // transfer-encoding and priority (RFC 9218) are mechanism headers usable in either
            // direction, like the hop-by-hop ones above.
            "transfer-encoding",
            "priority",
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

    private static final Set<String> BASE_SAFE_REQUEST = Stream.concat(COMMON_SAFE.stream(), Stream.of(
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
                    // Client Hints: bounded device/browser metadata (architecture, model,
                    // OS version, dark-mode preference, ...), not user-identifying secrets.
                    "sec-ch-ua-arch",
                    "sec-ch-ua-bitness",
                    "sec-ch-ua-full-version",
                    "sec-ch-ua-full-version-list",
                    "sec-ch-ua-model",
                    "sec-ch-ua-platform-version",
                    "sec-ch-ua-wow64",
                    "sec-ch-prefers-color-scheme",
                    "sec-ch-prefers-reduced-motion",
                    "save-data",
                    // Fixed-vocabulary Fetch Metadata headers: sec-fetch-site is one of
                    // cross-site/same-origin/same-site/none, sec-fetch-mode one of
                    // cors/navigate/no-cors/same-origin/websocket, sec-fetch-dest names a
                    // request destination (document/image/script/...), sec-fetch-user is
                    // exactly "?1" or absent. None can carry anything other than its
                    // enumerated values, so there is nothing to redact.
                    "sec-fetch-site",
                    "sec-fetch-mode",
                    "sec-fetch-dest",
                    "sec-fetch-user",
                    "cache-control",
                    "pragma",
                    "if-match",
                    "if-none-match",
                    "if-modified-since",
                    "if-unmodified-since",
                    "range",
                    "expect",
                    "max-forwards",
                    "upgrade-insecure-requests",
                    "early-data",
                    "x-requested-with",
                    "x-forwarded-for",
                    "x-forwarded-proto",
                    "x-forwarded-host",
                    "x-forwarded-port",
                    "x-real-ip",
                    "forwarded",
                    "via"
            )
    ).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> BASE_SAFE_RESPONSE = Stream.concat(COMMON_SAFE.stream(), Stream.of(
                    "server",
                    "x-powered-by",
                    "content-type",
                    "content-length",
                    "content-encoding",
                    "content-language",
                    "content-disposition",
                    "content-range",
                    "accept-ranges",
                    "last-modified",
                    "allow",
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
                    "x-ratelimit-remaining",
                    "x-ratelimit-limit",
                    "x-frame-options",
                    "strict-transport-security",
                    // Fixed-vocabulary response policy headers: each is one of a small
                    // enumerated set of values (e.g. referrer-policy is one of
                    // no-referrer/same-origin/strict-origin/..., the cross-origin-* trio is
                    // one of same-origin/same-site/cross-origin/require-corp/credentialless),
                    // so there is nothing to redact.
                    "referrer-policy",
                    "x-xss-protection",
                    "x-permitted-cross-domain-policies",
                    "cross-origin-opener-policy",
                    "cross-origin-embedder-policy",
                    "cross-origin-resource-policy",
                    "server-timing",
                    "x-runtime"
            )
    ).collect(Collectors.toUnmodifiableSet());

    /**
     * The built-in policy, unmodified. Kept for callers (tests, benchmarks) that have no
     * operator-supplied overrides to apply.
     */
    public static final HeaderNameSet SAFE_REQUEST_HEADERS = HeaderNameSet.of(BASE_SAFE_REQUEST);
    public static final HeaderNameSet SAFE_RESPONSE_HEADERS = HeaderNameSet.of(BASE_SAFE_RESPONSE);

    private JournalSecurity()
    {
    }

    /**
     * Applies an operator's {@code server.yaml -> storage.journal_security} whitelist to the
     * built-in request-header policy. If {@code overrideSafeHeaders} is non-empty it replaces
     * the built-in whitelist entirely; otherwise {@code additionalSafeHeaders} are added on
     * top of the defaults. Either way, anything not on the resulting list is fingerprinted, no
     * exceptions. {@code ServerConfig.JournalSecurityConfig} validation rejects a configuration
     * that sets both for the same direction, so callers need not resolve that ambiguity here.
     */
    public static HeaderNameSet resolveSafeRequestHeaders(final Collection<String> additionalSafeHeaders, final Collection<String> overrideSafeHeaders)
    {
        return resolve(BASE_SAFE_REQUEST, additionalSafeHeaders, overrideSafeHeaders);
    }

    /**
     * As {@link #resolveSafeRequestHeaders(Collection, Collection)}, for the response-header
     * policy.
     */
    public static HeaderNameSet resolveSafeResponseHeaders(final Collection<String> additionalSafeHeaders, final Collection<String> overrideSafeHeaders)
    {
        return resolve(BASE_SAFE_RESPONSE, additionalSafeHeaders, overrideSafeHeaders);
    }

    private static HeaderNameSet resolve(final Set<String> base, final Collection<String> additionalSafeHeaders, final Collection<String> overrideSafeHeaders)
    {
        if (!overrideSafeHeaders.isEmpty())
        {
            final Set<String> effective = new HashSet<>();
            for (final String name : overrideSafeHeaders)
            {
                effective.add(name.toLowerCase(Locale.ROOT));
            }
            return HeaderNameSet.of(effective);
        }

        if (additionalSafeHeaders.isEmpty())
        {
            return HeaderNameSet.of(base);
        }

        final Set<String> effective = new HashSet<>(base);
        for (final String name : additionalSafeHeaders)
        {
            effective.add(name.toLowerCase(Locale.ROOT));
        }
        return HeaderNameSet.of(effective);
    }
}