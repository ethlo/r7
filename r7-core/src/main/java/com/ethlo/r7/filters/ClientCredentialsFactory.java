package com.ethlo.r7.filters;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.Claims;
import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.Identity;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.StateKey;
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
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.google.auto.service.AutoService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;

/**
 * Exchanges a client's Basic credentials for an access token at an OAuth token endpoint, with the
 * client credentials grant (RFC 6749 §4.4), and sends the upstream that token in their place. The
 * Basic pair is the OAuth client id and secret, so a legacy client that only speaks Basic reaches
 * upstreams that only accept the identity provider's JWTs.
 * <p>
 * The token endpoint and the issuer are fixed by configuration: the caller never chooses where
 * its credentials are checked. The token that comes back is verified by the same rules as
 * {@code JwtAuth}'s before it is used.
 */
@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Exchanges the client's Basic credentials, as an OAuth client id and secret, for an access token at a token endpoint, and sends the upstream that token instead.")
public final class ClientCredentialsFactory implements GatewayFilterFactory<ClientCredentialsFactory.Config>
{
    static final int DEFAULT_MAX_CONCURRENT_EXCHANGES = 16;
    static final int MAX_MAX_CONCURRENT_EXCHANGES = 1024;

    /**
     * A token is used only until this long before it expires, so an upstream is never handed one
     * that runs out while the request is still on its way or being processed.
     */
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How long a request waits for an exchange slot before answering 503. Long enough to ride out
     * a burst of reconnecting clients, short enough that a flood is shed rather than queued.
     */
    static final long PERMIT_WAIT_MILLIS = 250;

    /**
     * A request waiting on an exchange another request started gives up after this: the exchange
     * itself is bounded by the connect and response timeouts, and a slow key fetch may add to it.
     */
    static final Duration EXCHANGE_WAIT = Duration.ofSeconds(15);

    /**
     * A token response is a few kilobytes at most; anything larger is not one, and is not read
     * into memory.
     */
    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /**
     * Housekeeping, not a defence: only a successful exchange is cached, so failed guesses cannot
     * grow it.
     */
    static final int MAX_CACHED_TOKENS = 10_000;

    /**
     * The longest a token is cached, whatever its expiry says.
     */
    static final Duration MAX_CACHE_DURATION = Duration.ofDays(1);

    private static final Logger logger = LoggerFactory.getLogger(ClientCredentialsFactory.class);
    private static final String FILTER_NAME = "ClientCredentials";
    private static final String IDENTITY_SCHEME = "client_credentials";
    private static final String DEFAULT_REALM = "Secure Area";
    private static final int CLOCK_SKEW_SECONDS = 60;
    private static final Duration JWKS_CACHE_DURATION = Duration.ofMinutes(5);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * A JWS in compact form: three base64url parts. Anything else could not be sent on as a header
     * value without first being checked for characters that would split it.
     */
    private static final Pattern COMPACT_JWS = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /**
     * RFC 6749 §3.3: scope tokens separated by single spaces.
     */
    private static final Pattern SCOPE = Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]+( [\\x21\\x23-\\x5B\\x5D-\\x7E]+)*");

    /**
     * The token to send upstream, set once the exchange succeeded.
     */
    private static final StateKey<String> ACCESS_TOKEN_KEY = new StateKey<>("gateway.auth.client_credentials.token");

    /**
     * One client for every filter, so a reload leaves no connection pool or selector thread
     * behind. Redirects are not followed: a token endpoint that redirects is misconfigured, and
     * following it would send the credentials somewhere the configuration never named.
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(Optional.ofNullable(ProxySelector.getDefault()).orElse(HttpClient.Builder.NO_PROXY))
            .executor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("r7-token-exchange-", 0).factory()))
            .build();

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
        return new GF(config, JwtVerification.processor(config.jwksUrl(), JWKS_CACHE_DURATION,
                config.issuer(), config.audience(), CLOCK_SKEW_SECONDS));
    }

    public record Config(
            @Description("The OAuth token endpoint the credentials are exchanged at, such as 'https://auth.example.com/realms/acme/protocol/openid-connect/token'. "
                    + "Must be https, except on a loopback host: the client's secret is sent to it.")
            String tokenUrl,

            @Description("The JWKS endpoint the returned token is verified against.")
            String jwksUrl,

            @Description("The 'iss' the returned token must carry.")
            String issuer,

            @Description("A value the returned token's 'aud' must contain.")
            String audience,

            @Nullable
            @Description("The scope requested with each exchange, as space-separated scope names. Without it, the identity provider grants the client's default scopes.")
            String scope,

            @Nullable
            @DefaultValue("Secure Area")
            @Description("The authentication realm presented to the client in the Basic challenge.")
            String realm,

            @Nullable
            @DefaultValue("16")
            @Description("How many exchanges this filter runs at once. A request that cannot start one within 250 ms is answered with 503, "
                    + "so a flood of wrong secrets is shed by the gateway rather than passed on to the identity provider.")
            Integer maxConcurrentExchanges) implements ValidatableConfig
    {
        @Override
        public String realm()
        {
            return this.realm != null && !this.realm.isBlank() ? this.realm : DEFAULT_REALM;
        }

        @Override
        public Integer maxConcurrentExchanges()
        {
            return Optional.ofNullable(this.maxConcurrentExchanges).orElse(DEFAULT_MAX_CONCURRENT_EXCHANGES);
        }

        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);
            validator.notBlank("token_url", this.tokenUrl);
            validator.notBlank("jwks_url", this.jwksUrl);
            validator.notBlank("issuer", this.issuer);
            validator.notBlank("audience", this.audience);
            // Checked first and alone: every other check quotes the URL in its message.
            if (this.tokenUrl != null && this.tokenUrl.indexOf('@') >= 0)
            {
                validator.invalid("token_url", "<redacted>", "Must not contain '@': credentials in the URL would be shown in diagnostics, and the client's own are sent to it");
            }
            else
            {
                JwtVerification.validateUrl(validator, "token_url", this.tokenUrl, true);
            }
            JwtVerification.validateUrl(validator, "jwks_url", this.jwksUrl, false);
            if (this.scope != null && !SCOPE.matcher(this.scope).matches())
            {
                validator.invalid("scope", this.scope, "Must be scope names separated by single spaces, without quotes or backslashes");
            }
            BasicAuthFactory.validateRealm(validator, this.realm());
            if (this.maxConcurrentExchanges != null && (this.maxConcurrentExchanges < 1 || this.maxConcurrentExchanges > MAX_MAX_CONCURRENT_EXCHANGES))
            {
                validator.invalid("max_concurrent_exchanges", this.maxConcurrentExchanges, "Must be between 1 and " + MAX_MAX_CONCURRENT_EXCHANGES);
            }
        }
    }

    /**
     * An access token, and who it says the caller is.
     *
     * @param useUntil when to stop sending it: {@link #EXPIRY_MARGIN} before it expires
     */
    record Token(String value, Identity identity, Instant useUntil) implements Outcome
    {
    }

    /**
     * An exchange that failed, handed to the requests that waited for it and then dropped. It is
     * a value rather than an exceptionally completed future: the cache logs every failed load as
     * a warning, and failed logins are not the gateway's to warn about.
     */
    record Failed(Exception cause) implements Outcome
    {
    }

    sealed interface Outcome permits Token, Failed
    {
    }

    /**
     * The identity provider refused the credentials: the client's to fix.
     */
    static final class Refused extends Exception
    {
        Refused(final String message)
        {
            super(message, null, false, false);
        }
    }

    /**
     * No verdict on the credentials: the identity provider could not be reached, answered
     * something that is not a token response, or the gateway is shedding load.
     */
    static final class Unavailable extends Exception
    {
        Unavailable(final String message)
        {
            super(message, null, false, false);
        }
    }

    static final class GF implements ClientRequestGatewayFilter, UpstreamRequestGatewayFilter, ShortInfo
    {
        private static final byte[] UNAUTHORIZED_PAYLOAD = "Unauthorized".getBytes(UTF_8);
        private static final byte[] UNAVAILABLE_PAYLOAD = "Service Unavailable".getBytes(UTF_8);
        private static final String BASIC_SCHEME = "Basic ";
        private static final String BEARER_SCHEME = "Bearer ";

        private final URI tokenUrl;
        private final String scopeParameter;
        private final String challenge;
        private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;
        private final Semaphore exchangePermits;
        private final SecretKeySpec cacheKeySecret;
        private final AsyncCache<String, Outcome> tokens;
        private final AtomicBoolean warned = new AtomicBoolean();
        private final Duration responseTimeout;

        GF(final Config config, final ConfigurableJWTProcessor<SecurityContext> jwtProcessor)
        {
            this(config, jwtProcessor, RESPONSE_TIMEOUT);
        }

        GF(final Config config, final ConfigurableJWTProcessor<SecurityContext> jwtProcessor, final Duration responseTimeout)
        {
            this.responseTimeout = responseTimeout;
            this.tokenUrl = URI.create(config.tokenUrl());
            this.scopeParameter = config.scope() != null ? "&scope=" + URLEncoder.encode(config.scope(), UTF_8) : "";
            this.challenge = BasicAuthFactory.basicChallenge(config.realm());
            this.jwtProcessor = jwtProcessor;
            this.exchangePermits = new Semaphore(config.maxConcurrentExchanges());

            // A random key held only in memory, new with every load: with a plain digest, a heap
            // dump would let a short secret be guessed offline at hashing speed.
            final byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            this.cacheKeySecret = new SecretKeySpec(secret, HMAC_ALGORITHM);

            this.tokens = Caffeine.newBuilder()
                    .maximumSize(MAX_CACHED_TOKENS)
                    .expireAfter(new UseUntil())
                    .buildAsync();
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String authorization = exchange.clientRequest().headers().getFirst(HttpHeaders.AUTHORIZATION);

            // Authorization is a singleton field (RFC 9110 11.6.2): with two, a second would ride
            // along to whatever reads the request next, so an ambiguous request is refused.
            if (authorization == null || !authorization.regionMatches(true, 0, BASIC_SCHEME, 0, BASIC_SCHEME.length())
                    || BasicAuthFactory.hasMoreThanOne(exchange.clientRequest().headers().getAll(HttpHeaders.AUTHORIZATION)))
            {
                this.rejectUnauthorized(exchange);
                return;
            }

            final String credentials = authorization.substring(BASIC_SCHEME.length()).trim();
            final Token token;
            try
            {
                token = this.token(credentials);
            }
            catch (final Refused e)
            {
                this.rejectUnauthorized(exchange);
                return;
            }
            catch (final Unavailable e)
            {
                this.rejectUnavailable(exchange);
                return;
            }

            exchange.setAttachment(Identity.KEY, token.identity());
            exchange.setAttachment(ACCESS_TOKEN_KEY, token.value());
            // The client's secret is never forwarded: the token replaces it.
            exchange.setAttachment(GatewayContextKeys.CLIENT_AUTHORIZATION_CONSUMED, Boolean.TRUE);
        }

        /**
         * The client's own Authorization header is already gone (it was marked consumed), so this
         * sets the only one the upstream sees.
         */
        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            final String token = exchange.getAttachment(ACCESS_TOKEN_KEY);
            if (token != null)
            {
                exchange.upstreamRequest().headers().set(HttpHeaders.AUTHORIZATION, BEARER_SCHEME + token);
            }
        }

        /**
         * A cached token, or a fresh one. Concurrent requests with the same credentials share one
         * exchange: the first to miss runs it on its own thread, the others wait for its outcome.
         * A failed exchange is never cached, so the next request tries again.
         */
        Token token(final String credentials) throws Refused, Unavailable
        {
            if (credentials.isEmpty())
            {
                throw new Refused("no credentials");
            }

            final String key = this.cacheKey(credentials);
            final CompletableFuture<Outcome> mine = new CompletableFuture<>();
            final boolean[] leader = {false};
            final CompletableFuture<Outcome> shared = this.tokens.get(key, (ignored, executor) ->
            {
                leader[0] = true;
                return mine;
            });
            if (leader[0])
            {
                Outcome outcome = new Failed(new Unavailable("exchange failed"));
                try
                {
                    outcome = this.exchange(credentials);
                }
                catch (final Refused | Unavailable e)
                {
                    outcome = new Failed(e);
                }
                finally
                {
                    // Whatever happened, the waiters must not wait for the full timeout, and a
                    // failure must not answer the next request.
                    if (outcome instanceof Failed)
                    {
                        this.tokens.asMap().remove(key, mine);
                    }
                    mine.complete(outcome);
                }
            }

            final Outcome outcome;
            try
            {
                outcome = shared.get(EXCHANGE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (final ExecutionException e)
            {
                throw new IllegalStateException("Token exchange failed", e.getCause());
            }
            catch (final TimeoutException e)
            {
                throw new Unavailable("timed out waiting for the exchange");
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new Unavailable("interrupted");
            }
            return switch (outcome)
            {
                case Token token -> token;
                case Failed(Refused refused) -> throw refused;
                case Failed(Unavailable unavailable) -> throw unavailable;
                case Failed(Exception other) -> throw new IllegalStateException("Token exchange failed", other);
            };
        }

        private Token exchange(final String credentials) throws Refused, Unavailable
        {
            final String clientId;
            final String clientSecret;
            try
            {
                final String decoded = new String(Base64.getDecoder().decode(credentials), UTF_8);
                final int separator = decoded.indexOf(':');
                if (separator <= 0 || separator == decoded.length() - 1)
                {
                    throw new Refused("malformed credentials");
                }
                clientId = decoded.substring(0, separator);
                clientSecret = decoded.substring(separator + 1);
            }
            catch (final IllegalArgumentException e)
            {
                throw new Refused("malformed credentials");
            }

            // The permit is held until the token is verified: the exchange is not over when the
            // response head arrives, and a cap that let go then would not bound the work.
            this.acquirePermit();
            try
            {
                final HttpResponse<byte[]> response = this.post(clientId, clientSecret);
                final int status = response.statusCode();
                if (status == HttpStatuses.UNAUTHORIZED)
                {
                    throw new Refused("refused by the token endpoint");
                }
                final Map<String, Object> body = parse(response.body());
                if (status == HttpStatuses.BAD_REQUEST)
                {
                    // RFC 6749 §5.2: these say the credentials are wrong or not allowed this grant.
                    // Any other error (invalid_scope, invalid_request, unsupported_grant_type) is a
                    // configuration problem the client cannot fix, so it is not answered with 401.
                    final Object error = body != null ? body.get("error") : null;
                    if ("invalid_client".equals(error) || "unauthorized_client".equals(error) || "invalid_grant".equals(error))
                    {
                        throw new Refused("refused by the token endpoint");
                    }
                    throw this.unexpected("the token endpoint answered 400 with error '" + error + "'");
                }
                if (status != HttpStatuses.OK)
                {
                    throw this.unexpected("the token endpoint answered " + status);
                }
                if (body == null)
                {
                    throw this.unexpected("the token endpoint answered 200 without a JSON object");
                }
                return this.verify(body, clientId);
            }
            finally
            {
                this.exchangePermits.release();
            }
        }

        private void acquirePermit() throws Unavailable
        {
            try
            {
                if (!this.exchangePermits.tryAcquire(PERMIT_WAIT_MILLIS, TimeUnit.MILLISECONDS))
                {
                    throw new Unavailable("too many exchanges in progress");
                }
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new Unavailable("interrupted");
            }
        }

        /**
         * Sends the token request and reads the whole response within {@link #responseTimeout}:
         * the request timeout alone ends at the response head, and an endpoint that then stalls
         * its body would hold the request forever. A 401 body is discarded unread, since the
         * status is the whole answer, and any other body is read up to {@link #MAX_RESPONSE_BYTES}.
         */
        private HttpResponse<byte[]> post(final String clientId, final String clientSecret) throws Unavailable
        {
            // RFC 6749 §2.3.1: id and secret are form-urlencoded before they are joined, which a
            // legacy client does not do, so they are decoded and encoded again here.
            final String basic = Base64.getEncoder().encodeToString(
                    (URLEncoder.encode(clientId, UTF_8) + ":" + URLEncoder.encode(clientSecret, UTF_8)).getBytes(UTF_8));
            final HttpRequest request = HttpRequest.newBuilder(this.tokenUrl)
                    .timeout(this.responseTimeout)
                    .header(HttpHeaders.AUTHORIZATION, BASIC_SCHEME + basic)
                    .header(HttpHeaders.CONTENT_TYPE, MediaTypes.APPLICATION_X_WWW_FORM_URLENCODED)
                    .header(HttpHeaders.ACCEPT, MediaTypes.APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials" + this.scopeParameter))
                    .build();
            final HttpResponse.BodyHandler<byte[]> handler = info -> info.statusCode() == HttpStatuses.UNAUTHORIZED
                    ? HttpResponse.BodySubscribers.replacing(new byte[0])
                    : HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofByteArray(), MAX_RESPONSE_BYTES);
            final CompletableFuture<HttpResponse<byte[]>> response = HTTP_CLIENT.sendAsync(request, handler);
            try
            {
                return response.get(CONNECT_TIMEOUT.plus(this.responseTimeout).toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (final ExecutionException e)
            {
                throw this.unexpected("the token endpoint could not be reached or its response not read: " + e.getCause());
            }
            catch (final TimeoutException e)
            {
                response.cancel(true);
                throw this.unexpected("the token endpoint did not answer in full within " + CONNECT_TIMEOUT.plus(this.responseTimeout));
            }
            catch (final InterruptedException e)
            {
                response.cancel(true);
                Thread.currentThread().interrupt();
                throw new Unavailable("interrupted");
            }
        }

        /**
         * @return the JSON object in the body, or null when the body is not one
         */
        private static Map<String, Object> parse(final byte[] body)
        {
            try
            {
                return JSONObjectUtils.parse(new String(body, UTF_8));
            }
            catch (final ParseException e)
            {
                return null;
            }
        }

        private Token verify(final Map<String, Object> body, final String clientId) throws Unavailable
        {
            if (!(body.get("access_token") instanceof String accessToken) || !COMPACT_JWS.matcher(accessToken).matches())
            {
                throw this.unexpected("the token endpoint's response has no access_token in JWS compact form");
            }
            if (!(body.get("token_type") instanceof String tokenType) || !"Bearer".equalsIgnoreCase(tokenType))
            {
                throw this.unexpected("the token endpoint's response has token_type '" + body.get("token_type") + "', not Bearer");
            }

            final JWTClaimsSet verified;
            try
            {
                verified = this.jwtProcessor.process(accessToken, null);
            }
            catch (final KeySourceException e)
            {
                throw this.unexpected("the signing keys could not be fetched from jwks_url: " + e.getMessage());
            }
            catch (final ParseException | BadJOSEException | JOSEException e)
            {
                // The client's credentials were accepted; the token is what is wrong, which is the
                // configuration's to fix (audience, issuer or keys), so this is not a 401.
                throw this.unexpected("the token from the token endpoint failed verification: " + e.getMessage());
            }

            final Claims claims = new JwtVerification.JwtClaims(verified);
            final String username = claims.string("client_id")
                    .or(() -> claims.string("azp"))
                    .filter(name -> !name.isBlank())
                    .orElse(clientId);
            final Identity identity = new Identity(IDENTITY_SCHEME, verified.getIssuer(), verified.getSubject(), username, claims);
            return new Token(accessToken, identity, verified.getExpirationTime().toInstant().minus(EXPIRY_MARGIN));
        }

        /**
         * The first unexpected answer after a load is logged as a warning, since it is usually a
         * configuration mistake; later ones at debug, so an outage does not flood the log.
         */
        private Unavailable unexpected(final String reason)
        {
            if (this.warned.compareAndSet(false, true))
            {
                logger.warn("{} at {}: {}; answering 503. Later failures of this filter are logged at debug.", FILTER_NAME, this.tokenUrl, reason);
            }
            else
            {
                logger.debug("{} at {}: {}", FILTER_NAME, this.tokenUrl, reason);
            }
            return new Unavailable(reason);
        }

        private String cacheKey(final String credentials)
        {
            try
            {
                final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(this.cacheKeySecret);
                return HexFormat.of().formatHex(mac.doFinal(credentials.getBytes(UTF_8)));
            }
            catch (final GeneralSecurityException e)
            {
                throw new IllegalStateException("HmacSHA256 is required by every Java platform", e);
            }
        }

        private void rejectUnauthorized(final ClientRequestGatewayExchange exchange)
        {
            final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
            headers.set(HttpHeaders.WWW_AUTHENTICATE, this.challenge);
            headers.set(HttpHeaders.CONTENT_TYPE, MediaTypes.TEXT_PLAIN_UTF8);
            exchange.shortCircuit(new ShortCircuitGatewayResponse(headers, HttpStatuses.UNAUTHORIZED, ByteBuffer.wrap(UNAUTHORIZED_PAYLOAD)));
        }

        private void rejectUnavailable(final ClientRequestGatewayExchange exchange)
        {
            final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "1");
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
            return FILTER_NAME + ": " + this.tokenUrl;
        }
    }

    /**
     * Each token is kept until its own {@link Token#useUntil()}. One already past it, like a
     * failure, is still handed to the requests that waited for it, and dropped at once.
     */
    private static final class UseUntil implements Expiry<String, Outcome>
    {
        @Override
        public long expireAfterCreate(final String key, final Outcome outcome, final long currentTime)
        {
            if (!(outcome instanceof Token token))
            {
                return 0;
            }
            final Duration remaining = Duration.between(Instant.now(), token.useUntil());
            if (remaining.isNegative())
            {
                return 0;
            }
            // Bounded, so a token that claims to live for centuries cannot overflow the nanoseconds.
            return remaining.compareTo(MAX_CACHE_DURATION) > 0 ? MAX_CACHE_DURATION.toNanos() : remaining.toNanos();
        }

        @Override
        public long expireAfterUpdate(final String key, final Outcome outcome, final long currentTime, final long currentDuration)
        {
            return this.expireAfterCreate(key, outcome, currentTime);
        }

        @Override
        public long expireAfterRead(final String key, final Outcome outcome, final long currentTime, final long currentDuration)
        {
            return currentDuration;
        }
    }
}
