package com.ethlo.r7.filters;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ethlo.r7.api.Claims;
import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.Identity;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.TextValues;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.core.GatewayContextKeys;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.util.constants.MediaTypes;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Verifies a {@code Bearer} JWT against the JWKS endpoint of its issuer and sets the caller's
 * {@link Identity}. Only the configured issuers are trusted, each with keys of its own: a token's
 * {@code iss} picks the keys it is verified with, and a token from any other issuer is refused
 * before a key is looked up. Only asymmetric algorithms are accepted: a JWKS is public, so an HMAC
 * key in it is one anybody could sign with.
 */
@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Rejects requests that do not present a valid Bearer JWT from a trusted issuer, verified against that issuer's JWKS endpoint.")
public final class JwtAuthFactory implements GatewayFilterFactory<JwtAuthFactory.Config>
{
    static final Set<JWSAlgorithm> ALLOWED_ALGORITHMS = Set.of(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);
    static final int MAX_CLOCK_SKEW_SECONDS = 300;
    /**
     * The keys are fetched at most once per 30 s, so a token with an unknown key id cannot make
     * every request fetch them; a cache shorter than a minute leaves no room for that and a refresh.
     */
    static final Duration MIN_JWKS_CACHE_DURATION = Duration.ofMinutes(1);
    private static final String FILTER_NAME = "JwtAuth";
    private static final String SCHEME = "jwt";
    private static final String BEARER_SCHEME = "Bearer ";
    private static final Duration MAX_REFRESH_AHEAD = Duration.ofSeconds(30);
    /**
     * Where Keycloak publishes a realm's keys, relative to the realm's issuer.
     */
    static final String DEFAULT_JWKS_PATH = "/protocol/openid-connect/certs";

    /**
     * Runs the background key refreshes of every JwtAuth filter. Virtual threads, so a filter
     * retired by a reload leaves no thread behind and its key source needs no closing.
     */
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("r7-jwks-refresh-", 0).factory());

    /**
     * The verified claims, kept apart from {@link Identity#KEY}: a later filter may replace the
     * identity, but the headers this filter forwards come from the token it verified.
     */
    private static final StateKey<Claims> CLAIMS_KEY = new StateKey<>("gateway.auth.jwt.claims");

    /**
     * A plain JWT, or an OAuth access token typed as one by RFC 9068; an ID token or any other
     * typed JWT is refused.
     */
    private static final DefaultJOSEObjectTypeVerifier<SecurityContext> TYPE_VERIFIER = new DefaultJOSEObjectTypeVerifier<>(
            JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), new JOSEObjectType("application/at+jwt"), null);

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    @Override
    public Class<Config> configClass()
    {
        return Config.class;
    }

    @Override
    public ClientRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config, buildJwtProcessors(config, issuer -> remoteKeySource(issuer, config.jwksCacheDuration())));
    }

    /**
     * One processor per trusted issuer, each with its own keys and requiring its own issuer, so a
     * key of one issuer can never verify a token that names another.
     */
    static Map<String, ConfigurableJWTProcessor<SecurityContext>> buildJwtProcessors(final Config config, final Function<TrustedIssuer, JWKSource<SecurityContext>> keySources)
    {
        final Map<String, ConfigurableJWTProcessor<SecurityContext>> processors = new LinkedHashMap<>();
        for (final TrustedIssuer issuer : config.trustedIssuers())
        {
            processors.put(issuer.issuer(), buildJwtProcessor(config, issuer.issuer(), keySources.apply(issuer)));
        }
        return processors;
    }

    private static JWKSource<SecurityContext> remoteKeySource(final TrustedIssuer issuer, final Duration cacheDuration)
    {
        final URL jwksUrl;
        try
        {
            jwksUrl = URI.create(issuer.jwksUrl()).toURL();
        }
        catch (final IllegalArgumentException | MalformedURLException e)
        {
            throw new IllegalStateException("Invalid jwks_url: " + issuer.jwksUrl(), e);
        }

        // Shortly before the cache expires, the next request starts a refresh on a background
        // thread and is itself still answered from the cached keys.
        final long refreshAheadMillis = Math.max(1, Math.min(MAX_REFRESH_AHEAD.toMillis(), cacheDuration.toMillis() / 2));
        return JWKSourceBuilder.<SecurityContext>create(jwksUrl)
                .cache(cacheDuration.toMillis(), Duration.ofSeconds(30).toMillis())
                .refreshAheadCache(refreshAheadMillis, null, REFRESH_EXECUTOR, false, null, false)
                .retrying(true)
                .build();
    }

    private static ConfigurableJWTProcessor<SecurityContext> buildJwtProcessor(final Config config, final String issuer, final JWKSource<SecurityContext> jwkSource)
    {
        final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(TYPE_VERIFIER);
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALLOWED_ALGORITHMS, jwkSource));
        final DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                Set.of(config.audience()),
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of("exp", "sub"),
                null);
        claimsVerifier.setMaxClockSkew(config.clockSkewSeconds());
        processor.setJWTClaimsSetVerifier(claimsVerifier);
        return processor;
    }

    public record Config(
            @Nullable
            @Description("The JWKS endpoint the signing keys are fetched from, such as 'https://auth.example.com/realms/tenant-a/protocol/openid-connect/certs'. Set with 'issuer', for a single issuer; 'issuers' replaces both.")
            String jwksUrl,

            @Nullable
            @Description("The 'iss' a token must carry, for a single issuer. A token from any other issuer is refused. 'issuers' replaces it and 'jwks_url'.")
            String issuer,

            @Nullable
            @Description("The issuers whose tokens are accepted, each with its own keys, in place of 'issuer' and 'jwks_url'. "
                    + "A token's 'iss' must equal one of them exactly; a token from any other issuer is refused before any keys are fetched.")
            List<TrustedIssuer> issuers,

            @Description("A value the token's 'aud' must contain. Required: an identity provider signs the tokens of all its clients with the same keys, so without it a token issued to any of them would pass.")
            String audience,

            @Nullable
            @DefaultValue("preferred_username")
            @Description("The claim that holds the caller's readable name, journaled beside the subject. 'sub' is often an opaque id, such as a UUID in Keycloak.")
            String usernameClaim,

            @Nullable
            @DefaultValue("60")
            @Description("Clock skew allowed on 'exp' and 'nbf', in seconds. At most 300: beyond that it stops allowing for unsynchronised clocks and silently extends every token's lifetime.")
            Integer clockSkewSeconds,

            @Nullable
            @DefaultValue("5m")
            @Description("How long the fetched keys are cached. Shortly before the cache expires, the next request starts a refresh on a background thread.")
            Duration jwksCacheDuration,

            @Nullable
            @DefaultValue("false")
            @Description("Whether the client's own Authorization header is passed on to the upstream after the token has been verified. "
                    + "Off by default: the token is the gateway's to check, and forwarding it hands every client's credential to the upstream. "
                    + "A header set by another filter, such as InjectBasicAuth, is never removed.")
            Boolean forwardCredentials,

            @Nullable
            @Description("Claim name to upstream header name. Each listed claim that is a string is set on the upstream request under that header. "
                    + "Every listed header is removed from the client's own request first, in any spelling a backend could read as it (X_User_Id as well as x-user-id), so a client can never supply one itself.")
            Map<String, String> forwardClaimsAsHeaders) implements ValidatableConfig
    {
        private static final String DEFAULT_USERNAME_CLAIM = "preferred_username";

        /**
         * The issuers to trust, each with the JWKS URL its keys come from: the {@code issuers}
         * list, or the single {@code issuer} with {@code jwks_url}.
         */
        public List<TrustedIssuer> trustedIssuers()
        {
            return this.issuers != null ? this.issuers : List.of(new TrustedIssuer(this.issuer, this.jwksUrl));
        }

        @Override
        public String usernameClaim()
        {
            return this.usernameClaim != null && !this.usernameClaim.isBlank() ? this.usernameClaim : DEFAULT_USERNAME_CLAIM;
        }

        @Override
        public Integer clockSkewSeconds()
        {
            return Optional.ofNullable(this.clockSkewSeconds).orElse(60);
        }

        @Override
        public Duration jwksCacheDuration()
        {
            return Optional.ofNullable(this.jwksCacheDuration).orElse(Duration.ofMinutes(5));
        }

        @Override
        public Boolean forwardCredentials()
        {
            return Optional.ofNullable(this.forwardCredentials).orElse(false);
        }

        @Override
        public Map<String, String> forwardClaimsAsHeaders()
        {
            return Optional.ofNullable(this.forwardClaimsAsHeaders).orElse(Map.of());
        }

        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);
            if (this.issuers == null)
            {
                validator.notBlank("jwks_url", this.jwksUrl);
                validator.notBlank("issuer", this.issuer);
                if (this.jwksUrl != null && !this.jwksUrl.isBlank())
                {
                    validateHttpUrl(validator, "jwks_url", this.jwksUrl);
                }
            }
            else
            {
                this.validateIssuers(validator);
            }
            validator.notBlank("audience", this.audience);
            if (this.clockSkewSeconds != null && (this.clockSkewSeconds < 0 || this.clockSkewSeconds > MAX_CLOCK_SKEW_SECONDS))
            {
                validator.invalid("clock_skew_seconds", this.clockSkewSeconds, "Must be between 0 and " + MAX_CLOCK_SKEW_SECONDS);
            }
            if (this.jwksCacheDuration != null && this.jwksCacheDuration.compareTo(MIN_JWKS_CACHE_DURATION) < 0)
            {
                validator.invalid("jwks_cache_duration", this.jwksCacheDuration,
                        "Must be at least " + MIN_JWKS_CACHE_DURATION + ", or the keys are fetched again on practically every request");
            }
            final Set<String> targets = new HashSet<>();
            this.forwardClaimsAsHeaders().forEach((claim, header) ->
            {
                final String property = "forward_claims_as_headers[" + claim + "]";
                validator.notBlank(property, header);
                validator.httpToken(property, header);
                if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(header))
                {
                    validator.invalid(property, header, "Must not be Authorization; forward_credentials decides whether the client's token reaches the upstream");
                }
                else if (header != null && !targets.add(canonicalHeaderName(header)))
                {
                    validator.invalid(property, header, "Is the target of another claim already");
                }
            });
        }

        private void validateIssuers(final ValidatorUtils validator)
        {
            if (this.issuer != null || this.jwksUrl != null)
            {
                validator.results().addError("issuers", "'issuers' replaces 'issuer' and 'jwks_url': set either the list or those two, not both");
            }
            validator.notEmpty("issuers", this.issuers);
            final Set<String> seen = new HashSet<>();
            for (int index = 0; index < this.issuers.size(); index++)
            {
                final TrustedIssuer entry = this.issuers.get(index);
                final String property = "issuers[" + index + "]";
                if (entry == null)
                {
                    validator.results().addError(property, "'" + property + "' is empty; each entry needs an 'issuer'");
                    continue;
                }
                validator.notBlank(property + ".issuer", entry.issuer);
                if (entry.issuer == null || entry.issuer.isBlank())
                {
                    continue;
                }
                if (!seen.add(entry.issuer))
                {
                    validator.invalid(property + ".issuer", entry.issuer, "Is listed already");
                }
                if (entry.jwksUrl != null)
                {
                    validator.notBlank(property + ".jwks_url", entry.jwksUrl);
                    if (!entry.jwksUrl.isBlank())
                    {
                        validateHttpUrl(validator, property + ".jwks_url", entry.jwksUrl);
                    }
                }
                else if (!isDerivableIssuer(entry.issuer))
                {
                    validator.invalid(property + ".issuer", entry.issuer,
                            "Must be an http or https URL with a host and no query or fragment for jwks_url to be derived from it; otherwise set jwks_url");
                }
            }
        }

        private static boolean isDerivableIssuer(final String issuer)
        {
            try
            {
                final URI uri = URI.create(issuer);
                final String scheme = uri.getScheme();
                return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                        && uri.getHost() != null && uri.getRawQuery() == null && uri.getRawFragment() == null;
            }
            catch (final IllegalArgumentException e)
            {
                return false;
            }
        }

        /**
         * Whether the value is an absolute http or https URL with a host; reports it under the
         * property when it is not.
         */
        private static boolean validateHttpUrl(final ValidatorUtils validator, final String property, final String value)
        {
            try
            {
                final URI uri = URI.create(value);
                final String protocol = uri.toURL().getProtocol();
                if (!"https".equalsIgnoreCase(protocol) && !"http".equalsIgnoreCase(protocol))
                {
                    validator.invalid(property, value, "Must be an http or https URL");
                    return false;
                }
                if (uri.getHost() == null)
                {
                    validator.invalid(property, value, "Must contain a valid host");
                    return false;
                }
                return true;
            }
            catch (final IllegalArgumentException | MalformedURLException e)
            {
                validator.invalid(property, value, "Not a valid URL: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * An issuer whose tokens are accepted, and where its signing keys are published.
     */
    public record TrustedIssuer(
            @Description("The 'iss' the issuer's tokens carry, such as 'https://auth.example.com/realms/tenant-a'. Matched exactly.")
            String issuer,

            @Nullable
            @Description("The JWKS endpoint the issuer's signing keys are fetched from. Defaults to the issuer followed by '/protocol/openid-connect/certs', where Keycloak publishes a realm's keys.")
            String jwksUrl)
    {
        @Override
        public String jwksUrl()
        {
            if (this.jwksUrl != null || this.issuer == null)
            {
                return this.jwksUrl;
            }
            final String base = this.issuer.endsWith("/") ? this.issuer.substring(0, this.issuer.length() - 1) : this.issuer;
            return base + DEFAULT_JWKS_PATH;
        }
    }

    /**
     * A header name as a backend may read it: case-insensitive, and with {@code _} and {@code -}
     * the same, as CGI-style servers fold both into {@code HTTP_X_USER_ID}.
     */
    static String canonicalHeaderName(final String name)
    {
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * The verified token's claims, as r7-api reads them: strings and arrays of strings only.
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

    static final class GF implements ClientRequestGatewayFilter, UpstreamRequestGatewayFilter, ShortInfo
    {
        private static final byte[] UNAUTHORIZED_PAYLOAD = "Unauthorized".getBytes(UTF_8);
        private static final byte[] UNAVAILABLE_PAYLOAD = "Service Unavailable".getBytes(UTF_8);
        private static final String CHALLENGE = "Bearer";
        private static final String CHALLENGE_INVALID_TOKEN = "Bearer error=\"invalid_token\"";

        /**
         * Keyed by the exact issuer; a token whose {@code iss} is not a key here is refused.
         */
        private final Map<String, ConfigurableJWTProcessor<SecurityContext>> jwtProcessors;
        private final String summary;
        private final String usernameClaim;
        private final boolean forwardCredentials;
        private final Map<String, String> forwardClaimsAsHeaders;
        private final List<String> managedHeaders;
        private final Set<String> canonicalManagedHeaders;

        GF(final Config config, final Map<String, ConfigurableJWTProcessor<SecurityContext>> jwtProcessors)
        {
            this.jwtProcessors = Map.copyOf(jwtProcessors);
            final List<TrustedIssuer> trusted = config.trustedIssuers();
            this.summary = FILTER_NAME + ": " + (trusted.size() == 1 ? trusted.getFirst().jwksUrl() : trusted.size() + " issuers");
            this.usernameClaim = config.usernameClaim();
            this.forwardCredentials = config.forwardCredentials();
            this.forwardClaimsAsHeaders = Map.copyOf(config.forwardClaimsAsHeaders());
            this.managedHeaders = List.copyOf(new LinkedHashSet<>(this.forwardClaimsAsHeaders.values()));
            this.canonicalManagedHeaders = this.managedHeaders.stream().map(JwtAuthFactory::canonicalHeaderName).collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String authorization = exchange.clientRequest().headers().getFirst(HttpHeaders.AUTHORIZATION);
            if (authorization == null || !authorization.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())
                    || hasMoreThanOne(exchange.clientRequest().headers().getAll(HttpHeaders.AUTHORIZATION)))
            {
                this.rejectUnauthorized(exchange, CHALLENGE);
                return;
            }

            final SignedJWT token;
            final ConfigurableJWTProcessor<SecurityContext> processor;
            try
            {
                token = SignedJWT.parse(authorization.substring(BEARER_SCHEME.length()).trim());
                // Unverified, and only used to pick the keys: the processor checks the signature
                // and requires this same issuer. An unknown issuer is refused here, so no token
                // can make the gateway fetch keys from anywhere but the configured endpoints.
                final String issuer = token.getJWTClaimsSet().getIssuer();
                processor = issuer != null ? this.jwtProcessors.get(issuer) : null;
            }
            catch (final ParseException e)
            {
                this.rejectUnauthorized(exchange, CHALLENGE_INVALID_TOKEN);
                return;
            }
            if (processor == null)
            {
                this.rejectUnauthorized(exchange, CHALLENGE_INVALID_TOKEN);
                return;
            }

            final JWTClaimsSet verified;
            try
            {
                verified = processor.process(token, null);
            }
            catch (final KeySourceException e)
            {
                // The gateway's problem, not the client's: a 401 would tell every holder of a good
                // token to throw it away and sign in again, right through the outage. Only this
                // issuer's tokens are affected; every other issuer has keys of its own.
                this.rejectUnavailable(exchange);
                return;
            }
            catch (final BadJOSEException | JOSEException e)
            {
                // No reason given: which check failed is what someone probing with forged tokens wants to learn.
                this.rejectUnauthorized(exchange, CHALLENGE_INVALID_TOKEN);
                return;
            }

            final Claims claims = new JwtClaims(verified);
            exchange.setAttachment(CLAIMS_KEY, claims);
            exchange.setAttachment(Identity.KEY, new Identity(SCHEME, verified.getIssuer(), verified.getSubject(),
                    claims.string(this.usernameClaim).filter(name -> !name.isBlank()).orElse(null), claims));
            if (!this.forwardCredentials)
            {
                exchange.setAttachment(GatewayContextKeys.CLIENT_AUTHORIZATION_CONSUMED, Boolean.TRUE);
            }
        }

        /**
         * Every forwarded header is removed first, whatever the token holds: the upstream request
         * starts as the client's own, so a token without the claim would otherwise leave a
         * client-supplied value standing for the upstream to trust.
         */
        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            if (this.managedHeaders.isEmpty())
            {
                return;
            }
            // Every spelling a backend could read as one of these headers, X_User_Id as well as x-user-id.
            final List<String> clientCopies = new ArrayList<>(0);
            exchange.upstreamRequest().headers().forEach((name, value) ->
            {
                if (this.canonicalManagedHeaders.contains(canonicalHeaderName(name)) && !clientCopies.contains(name))
                {
                    clientCopies.add(name);
                }
            });
            clientCopies.forEach(exchange.upstreamRequest().headers()::remove);
            final Claims claims = exchange.getAttachment(CLAIMS_KEY);
            if (claims != null)
            {
                this.forwardClaimsAsHeaders.forEach((claim, header) ->
                        claims.string(claim)
                                .filter(GF::isHeaderValue)
                                .ifPresent(value -> exchange.upstreamRequest().headers().set(header, value)));
            }
        }

        /**
         * A claim that cannot be sent as a header value (a character beyond ISO-8859-1, or a control
         * character) is not forwarded: the header is left out, as for a token without the claim.
         */
        private static boolean isHeaderValue(final String value)
        {
            return TextValues.isStorable(value) && TextValues.firstControlCharacterIndex(value) < 0;
        }

        private static boolean hasMoreThanOne(final Iterable<String> values)
        {
            int count = 0;
            for (final String ignored : values)
            {
                if (++count > 1)
                {
                    return true;
                }
            }
            return false;
        }

        private void rejectUnauthorized(final ClientRequestGatewayExchange exchange, final String challenge)
        {
            final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
            headers.set(HttpHeaders.WWW_AUTHENTICATE, challenge);
            headers.set(HttpHeaders.CONTENT_TYPE, MediaTypes.TEXT_PLAIN_UTF8);
            exchange.shortCircuit(new ShortCircuitGatewayResponse(headers, HttpStatuses.UNAUTHORIZED, ByteBuffer.wrap(UNAUTHORIZED_PAYLOAD)));
        }

        private void rejectUnavailable(final ClientRequestGatewayExchange exchange)
        {
            final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "5");
            headers.set(HttpHeaders.CONTENT_TYPE, MediaTypes.TEXT_PLAIN_UTF8);
            exchange.shortCircuit(new ShortCircuitGatewayResponse(headers, HttpStatuses.SERVICE_UNAVAILABLE, ByteBuffer.wrap(UNAVAILABLE_PAYLOAD)));
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return this.summary;
        }
    }
}
