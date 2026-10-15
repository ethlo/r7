package com.ethlo.r7.filters;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.ethlo.r7.api.Claims;
import com.ethlo.r7.util.ValidatorUtils;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * How r7 verifies a JWT, wherever the token comes from: one set of rules for {@code JwtAuth},
 * which checks the token a client sends, and {@code ClientCredentials}, which checks the token
 * the identity provider hands back. Only asymmetric algorithms are accepted: a JWKS is public, so
 * an HMAC key in it is one anybody could sign with.
 */
final class JwtVerification
{
    static final Set<JWSAlgorithm> ALLOWED_ALGORITHMS = Set.of(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);

    /**
     * An IPv4 literal in 127.0.0.0/8; a name such as {@code 127.0.0.1.example.com} is not one.
     */
    private static final Pattern LOOPBACK_IPV4 = Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private static final Duration MAX_REFRESH_AHEAD = Duration.ofSeconds(30);

    /**
     * Runs the background key refreshes of every verifying filter. Virtual threads, so a filter
     * retired by a reload leaves no thread behind and its key source needs no closing.
     */
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("r7-jwks-refresh-", 0).factory());

    /**
     * A plain JWT, or an OAuth access token typed as one by RFC 9068; an ID token or any other
     * typed JWT is refused.
     */
    private static final DefaultJOSEObjectTypeVerifier<SecurityContext> TYPE_VERIFIER = new DefaultJOSEObjectTypeVerifier<>(
            JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), new JOSEObjectType("application/at+jwt"), null);

    private JwtVerification()
    {
    }

    /**
     * @return a processor that fetches the keys from {@code jwksUrl} and caches them, refreshing
     * them in the background shortly before the cache expires
     */
    static ConfigurableJWTProcessor<SecurityContext> processor(final String jwksUrl, final Duration cacheDuration,
                                                               final String issuer, final String audience, final int clockSkewSeconds)
    {
        final URL url;
        try
        {
            url = URI.create(jwksUrl).toURL();
        }
        catch (final IllegalArgumentException | MalformedURLException e)
        {
            throw new IllegalStateException("Invalid jwks_url: " + jwksUrl, e);
        }

        // Shortly before the cache expires, the next request starts a refresh on a background
        // thread and is itself still answered from the cached keys.
        final long refreshAheadMillis = Math.max(1, Math.min(MAX_REFRESH_AHEAD.toMillis(), cacheDuration.toMillis() / 2));
        final JWKSource<SecurityContext> jwkSource = JWKSourceBuilder.<SecurityContext>create(url)
                .cache(cacheDuration.toMillis(), Duration.ofSeconds(30).toMillis())
                .refreshAheadCache(refreshAheadMillis, null, REFRESH_EXECUTOR, false, null, false)
                .retrying(true)
                .build();
        return processor(jwkSource, issuer, audience, clockSkewSeconds);
    }

    static ConfigurableJWTProcessor<SecurityContext> processor(final JWKSource<SecurityContext> jwkSource,
                                                               final String issuer, final String audience, final int clockSkewSeconds)
    {
        final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(TYPE_VERIFIER);
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALLOWED_ALGORITHMS, jwkSource));
        final DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                Set.of(audience),
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of("exp", "sub"),
                null);
        claimsVerifier.setMaxClockSkew(clockSkewSeconds);
        processor.setJWTClaimsSetVerifier(claimsVerifier);
        return processor;
    }

    /**
     * Checks that {@code value}, when present, is an absolute http or https URL with a host; blank
     * is the caller's to report.
     *
     * @param httpsUnlessLoopback whether plain http is refused for any host but a loopback one: for
     *                            a URL that carries a secret, which anybody on the path could read
     */
    static void validateUrl(final ValidatorUtils validator, final String property, final String value, final boolean httpsUnlessLoopback)
    {
        if (value == null || value.isBlank())
        {
            return;
        }
        try
        {
            final URI uri = URI.create(value);
            final String protocol = uri.toURL().getProtocol();
            if (!"https".equalsIgnoreCase(protocol) && !"http".equalsIgnoreCase(protocol))
            {
                validator.invalid(property, value, "Must be an http or https URL");
            }
            else if (uri.getHost() == null)
            {
                validator.invalid(property, value, "Must contain a valid host");
            }
            else if (httpsUnlessLoopback && "http".equalsIgnoreCase(protocol) && !isLoopback(uri.getHost()))
            {
                validator.invalid(property, value, "Must be an https URL: plain http is only accepted for a loopback host");
            }
        }
        catch (final IllegalArgumentException | MalformedURLException e)
        {
            validator.invalid(property, value, "Not a valid URL: " + e.getMessage());
        }
    }

    /**
     * Decided from the literal host only, never by resolving a name: what a name resolves to can
     * change after the check.
     */
    private static boolean isLoopback(final String host)
    {
        final String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return "localhost".equalsIgnoreCase(bare) || LOOPBACK_IPV4.matcher(bare).matches() || "::1".equals(bare) || "0:0:0:0:0:0:0:1".equals(bare);
    }

    /**
     * A verified token's claims, as r7-api reads them: strings and arrays of strings only.
     */
    record JwtClaims(JWTClaimsSet claims) implements Claims
    {
        @Override
        public Optional<String> string(final String name)
        {
            return this.claims.getClaim(name) instanceof String value ? Optional.of(value) : Optional.empty();
        }

        @Override
        public List<String> strings(final String name)
        {
            final Object value = this.claims.getClaim(name);
            if (value instanceof String single)
            {
                return List.of(single);
            }
            if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance))
            {
                return list.stream().map(String.class::cast).toList();
            }
            return List.of();
        }

        @Override
        public boolean has(final String name)
        {
            return this.claims.getClaim(name) != null;
        }
    }
}
