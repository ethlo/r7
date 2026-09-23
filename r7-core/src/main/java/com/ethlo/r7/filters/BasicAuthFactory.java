package com.ethlo.r7.filters;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.TextValues;
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
import com.ethlo.r7.util.crypto.BCrypt;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Rejects requests that do not present valid Basic Authentication credentials.")
public final class BasicAuthFactory implements GatewayFilterFactory<BasicAuthFactory.Config>
{
    /**
     * Attribute holding the username that authenticated the request, for journaling and access logs.
     * Only ever set after the password has been verified.
     */
    public static final String AUTHENTICATED_USER_KEY = "gateway.auth.basic.user";

    private static final String FILTER_NAME = "BasicAuth";

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
        return new GF(config);
    }

    public record Config(
            @Description("List of users in htpasswd format (username:bcrypt-hash), as produced by 'htpasswd -B'.")
            List<String> users,
            @Description("The authentication realm presented to the client.")
            @Nullable String realm) implements ValidatableConfig
    {
        private static final String DEFAULT_REALM = "Secure Area";

        @Override
        public String realm()
        {
            return this.realm != null && !this.realm.isBlank() ? this.realm : DEFAULT_REALM;
        }

        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);
            validator.notEmpty("users", this.users);

            if (this.users == null)
            {
                return;
            }

            // Anything rejected here would otherwise degrade silently: a dropped entry locks the
            // user out, and a malformed hash throws on every request instead of once at startup.
            final Set<String> seen = new HashSet<>();
            for (int i = 0; i < this.users.size(); i++)
            {
                final String property = "users[" + i + "]";
                final String entry = this.users.get(i);

                if (entry == null || entry.isBlank())
                {
                    result.addError(property, "'" + property + "' cannot be blank");
                    continue;
                }

                final int separator = entry.indexOf(':');
                if (separator <= 0 || separator == entry.length() - 1)
                {
                    validator.invalid(property, "<redacted>", "expected 'username:bcrypt-hash'");
                    continue;
                }

                final String username = entry.substring(0, separator);
                try
                {
                    BCrypt.requireValidHash(entry.substring(separator + 1));
                }
                catch (final IllegalArgumentException exception)
                {
                    validator.invalid(property, username, exception.getMessage());
                    continue;
                }

                if (!seen.add(username))
                {
                    validator.invalid(property, username, "duplicate username");
                }
            }

            this.validateRealm(validator);
        }

        /**
         * The realm is echoed into a quoted-string in the {@code WWW-Authenticate} header of every
         * 401. A control character there would split the response, and one above ISO-8859-1 cannot
         * be encoded in a header at all, so both are refused once at startup rather than on every
         * rejected request. Quote and backslash are permitted because the challenge escapes them.
         */
        private void validateRealm(final ValidatorUtils validator)
        {
            final String effective = this.realm();
            for (int i = 0; i < effective.length(); i++)
            {
                final char character = effective.charAt(i);
                if (character > TextValues.MAX_STORABLE || character == 0x7F || (character < 0x20 && character != '\t'))
                {
                    validator.invalid("realm", effective, String.format(
                            "character U+%04X at index %d cannot appear in an HTTP challenge; "
                                    + "use printable ISO-8859-1 text", (int) character, i));
                    return;
                }
            }
        }

        /**
         * @return the configured credentials keyed by username, assuming {@link #validate} passed
         */
        Map<String, String> credentials()
        {
            final Map<String, String> credentials = new LinkedHashMap<>();
            for (final String entry : this.users)
            {
                final int separator = entry.indexOf(':');
                credentials.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
            return Map.copyOf(credentials);
        }
    }

    private static final class GF implements ClientRequestGatewayFilter, ShortInfo
    {
        private static final byte[] UNAUTHORIZED_PAYLOAD = "Unauthorized".getBytes(UTF_8);
        private static final String SCHEME = "Basic ";

        /**
         * Only a credential that has already passed bcrypt is ever inserted, so an attacker cannot
         * drive growth with failed guesses - the live set is essentially the number of configured
         * users, and a hot-reload discards the whole filter anyway. The bound and the idle expiry
         * are therefore housekeeping rather than a defence, and need no operator tuning.
         */
        private static final int MAX_CACHE_ENTRIES = 1024;

        private static final Duration CACHE_TTL = Duration.ofMinutes(15);

        /**
         * Salt and ciphertext of a hash that exists only to be verified against and fail, so that an
         * unknown username costs the same time as a known one. Nothing hinges on it being
         * unmatchable - the {@code storedHash == null} test is what actually denies the request.
         */
        private static final String DUMMY_HASH_SUFFIX = "$.....................................................";

        private final Map<String, String> credentials;
        private final Cache<String, String> verifiedCredentials;
        private final String realm;
        private final String challenge;
        private final String dummyHash;

        GF(final Config config)
        {
            this.realm = config.realm();
            this.challenge = "Basic realm=\"" + escapeQuotedString(this.realm) + "\"";
            this.credentials = config.credentials();
            this.verifiedCredentials = Caffeine.newBuilder()
                    .maximumSize(MAX_CACHE_ENTRIES)
                    .expireAfterAccess(CACHE_TTL)
                    .build();
            this.dummyHash = buildDummyHash(this.credentials.values());
        }

        /**
         * Escapes a value for an RFC 9110 quoted-string. Backslashes must be doubled <em>before</em>
         * quotes are escaped: doing it the other way round would re-escape the backslashes this
         * inserts. Leaving backslashes alone is worse than cosmetic - a realm ending in one would
         * escape the closing quote, so the remainder of the header would be parsed as further
         * auth-params.
         */
        private static String escapeQuotedString(final String value)
        {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        /**
         * Cost used for the dummy hash when no user is configured at all.
         */
        private static final int FALLBACK_COST = 10;

        /**
         * Runs at the highest configured cost, so probing for a username can never come back faster
         * than an existing one would.
         * <p>
         * This closes the enumeration oracle only while every configured user shares one cost, which
         * is the normal case for a single {@code htpasswd} file. Mixed costs are themselves an
         * oracle - a reply at cost-4 speed proves a cost-4 user exists - and no choice of dummy cost
         * can hide that, because the cost is a property of the user's own hash. Re-hash all users at
         * the same cost if that matters for the route.
         */
        private static String buildDummyHash(final Iterable<String> hashes)
        {
            int cost = 0;
            for (final String hash : hashes)
            {
                cost = Math.max(cost, BCrypt.costOf(hash));
            }
            if (cost == 0)
            {
                cost = FALLBACK_COST;
            }
            return "$2y$" + (cost < 10 ? "0" + cost : Integer.toString(cost)) + DUMMY_HASH_SUFFIX;
        }

        /**
         * bcrypt is deliberately expensive, so verification must never run on an XNIO I/O thread.
         */
        @Override
        public boolean requiresDispatch()
        {
            return true;
        }

        @Override
        public void onClientRequest(final ClientRequestGatewayExchange exchange)
        {
            final String authHeader = exchange.clientRequest().headers().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.regionMatches(true, 0, SCHEME, 0, SCHEME.length()))
            {
                this.rejectUnauthorized(exchange);
                return;
            }

            final String base64Credentials = authHeader.substring(SCHEME.length()).trim();
            final String cacheKey = digest(base64Credentials);

            // Only a credential that already passed bcrypt is in here, so a hit is possible solely
            // for input the attacker had to get right anyway - and the 200 it produces announces
            // that just as loudly. Both failure paths below miss the cache and cost a full bcrypt,
            // which is what keeps them indistinguishable from each other.
            final String cachedUser = this.verifiedCredentials.getIfPresent(cacheKey);
            if (cachedUser != null)
            {
                exchange.attributes().set(AUTHENTICATED_USER_KEY, cachedUser);
                return;
            }

            final byte[] decodedBytes;
            try
            {
                decodedBytes = Base64.getDecoder().decode(base64Credentials);
            }
            catch (final IllegalArgumentException exception)
            {
                this.rejectUnauthorized(exchange);
                return;
            }

            final String decoded = new String(decodedBytes, UTF_8);
            final int separator = decoded.indexOf(':');
            if (separator < 0)
            {
                this.rejectUnauthorized(exchange);
                return;
            }

            final String username = decoded.substring(0, separator);
            final String password = decoded.substring(separator + 1);
            final String storedHash = this.credentials.get(username);

            // An unknown username is still run through bcrypt, otherwise the response time alone
            // tells an attacker which usernames exist.
            if (!BCrypt.checkPassword(password, storedHash != null ? storedHash : this.dummyHash) || storedHash == null)
            {
                this.rejectUnauthorized(exchange);
                return;
            }

            this.verifiedCredentials.put(cacheKey, username);
            exchange.attributes().set(AUTHENTICATED_USER_KEY, username);
        }

        /**
         * The cache is keyed by a digest rather than by the credentials themselves so that the
         * gateway does not hold every accepted password in memory for its whole lifetime. One
         * SHA-256 over a few dozen bytes is nothing against the bcrypt round it saves.
         */
        private static String digest(final String base64Credentials)
        {
            try
            {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(base64Credentials.getBytes(UTF_8)));
            }
            catch (final NoSuchAlgorithmException exception)
            {
                throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
            }
        }

        private void rejectUnauthorized(final ClientRequestGatewayExchange exchange)
        {
            final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
            headers.set(HttpHeaders.WWW_AUTHENTICATE, List.of(this.challenge));
            headers.set(HttpHeaders.CONTENT_TYPE, MediaTypes.TEXT_PLAIN);
            exchange.shortCircuit(new ShortCircuitGatewayResponse(headers,
                    HttpStatuses.UNAUTHORIZED,
                    ByteBuffer.wrap(UNAUTHORIZED_PAYLOAD)
            ));
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME + " (" + this.credentials.size() + " users, realm '" + this.realm + "')";
        }
    }
}
