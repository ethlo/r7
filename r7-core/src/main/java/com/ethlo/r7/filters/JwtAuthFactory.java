package com.ethlo.r7.filters;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;

/**
 * Verifies a {@code Bearer} JWT against a JWKS endpoint and sets the caller's {@link Identity}.
 * The rules a token must pass are {@link JwtVerification}'s.
 */
@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Rejects requests that do not present a valid Bearer JWT, verified against a JWKS endpoint.")
public final class JwtAuthFactory implements GatewayFilterFactory<JwtAuthFactory.Config>
{
    static final int MAX_CLOCK_SKEW_SECONDS = 300;
    /**
     * The keys are fetched at most once per 30 s, so a token with an unknown key id cannot make
     * every request fetch them; a cache shorter than a minute leaves no room for that and a refresh.
     */
    static final Duration MIN_JWKS_CACHE_DURATION = Duration.ofMinutes(1);
    private static final String FILTER_NAME = "JwtAuth";
    private static final String SCHEME = "jwt";
    private static final String BEARER_SCHEME = "Bearer ";

    /**
     * The verified claims, kept apart from {@link Identity#KEY}: a later filter may replace the
     * identity, but the headers this filter forwards come from the token it verified.
     */
    private static final StateKey<Claims> CLAIMS_KEY = new StateKey<>("gateway.auth.jwt.claims");

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
        return new GF(config, JwtVerification.processor(config.jwksUrl(), config.jwksCacheDuration(),
                config.issuer(), config.audience(), config.clockSkewSeconds()));
    }

    static ConfigurableJWTProcessor<SecurityContext> buildJwtProcessor(final Config config, final JWKSource<SecurityContext> jwkSource)
    {
        return JwtVerification.processor(jwkSource, config.issuer(), config.audience(), config.clockSkewSeconds());
    }

    public record Config(
            @Description("The JWKS endpoint the signing keys are fetched from, such as 'https://auth.example.com/realms/acme/protocol/openid-connect/certs'.")
            String jwksUrl,

            @Description("The 'iss' a token must carry. A token from any other issuer is refused.")
            String issuer,

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
            validator.notBlank("jwks_url", this.jwksUrl);
            validator.notBlank("issuer", this.issuer);
            validator.notBlank("audience", this.audience);
            JwtVerification.validateUrl(validator, "jwks_url", this.jwksUrl, false);
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
    }

    /**
     * A header name as a backend may read it: case-insensitive, and with {@code _} and {@code -}
     * the same, as CGI-style servers fold both into {@code HTTP_X_USER_ID}.
     */
    static String canonicalHeaderName(final String name)
    {
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static final class GF implements ClientRequestGatewayFilter, UpstreamRequestGatewayFilter, ShortInfo
    {
        private static final byte[] UNAUTHORIZED_PAYLOAD = "Unauthorized".getBytes(UTF_8);
        private static final byte[] UNAVAILABLE_PAYLOAD = "Service Unavailable".getBytes(UTF_8);
        private static final String CHALLENGE = "Bearer";
        private static final String CHALLENGE_INVALID_TOKEN = "Bearer error=\"invalid_token\"";

        private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;
        private final String jwksUrl;
        private final String usernameClaim;
        private final boolean forwardCredentials;
        private final Map<String, String> forwardClaimsAsHeaders;
        private final List<String> managedHeaders;
        private final Set<String> canonicalManagedHeaders;

        GF(final Config config, final ConfigurableJWTProcessor<SecurityContext> jwtProcessor)
        {
            this.jwtProcessor = jwtProcessor;
            this.jwksUrl = config.jwksUrl();
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

            final JWTClaimsSet verified;
            try
            {
                verified = this.jwtProcessor.process(authorization.substring(BEARER_SCHEME.length()).trim(), null);
            }
            catch (final KeySourceException e)
            {
                // The gateway's problem, not the client's: a 401 would tell every holder of a good
                // token to throw it away and sign in again, right through the outage.
                this.rejectUnavailable(exchange);
                return;
            }
            catch (final ParseException | BadJOSEException | JOSEException e)
            {
                // No reason given: which check failed is what someone probing with forged tokens wants to learn.
                this.rejectUnauthorized(exchange, CHALLENGE_INVALID_TOKEN);
                return;
            }

            final Claims claims = new JwtVerification.JwtClaims(verified);
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
            return FILTER_NAME + ": " + this.jwksUrl;
        }
    }
}
