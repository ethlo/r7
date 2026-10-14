package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.Identity;
import com.ethlo.r7.api.MutableGatewayAttributes;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.core.GatewayContextKeys;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.constants.HttpHeaders;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidationResult;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The filter's own decisions, against an in-memory key set: Nimbus's fetching and caching of a
 * remote JWKS is Nimbus's to test. The processor is the factory's own, so the algorithm and type
 * rules under test are the ones the gateway runs.
 */
class JwtAuthFactoryTest
{
    private static final String ISSUER = "https://auth.example.com/realms/acme";
    private static final String AUDIENCE = "billing";
    private static final String INVALID_TOKEN = "Bearer error=\"invalid_token\"";

    private static RSAPrivateKey privateKey;
    private static JWKSource<SecurityContext> keys;

    @BeforeAll
    static void generateKeyPair() throws Exception
    {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair keyPair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        keys = new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).keyID("k1").build()));
    }

    @Test
    void aValidTokenSetsTheIdentityInPlainText() throws Exception
    {
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token(claims().claim("preferred_username", "alice").build()));

        filter(config()).onClientRequest(exchange);

        verify(exchange, never()).shortCircuit(any());
        final Identity identity = exchange.getAttachment(Identity.KEY);
        assertThat(identity.scheme()).isEqualTo("jwt");
        assertThat(identity.issuer()).isEqualTo(ISSUER);
        assertThat(identity.subject()).isEqualTo("6f1c2d3e");
        assertThat(identity.username()).isEqualTo("alice");
        assertThat(identity.claims().string("preferred_username")).contains("alice");
    }

    @Test
    void theUsernameComesFromTheConfiguredClaim() throws Exception
    {
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token(claims().claim("email", "alice@example.com").build()));

        filter(config("email", Map.of(), false)).onClientRequest(exchange);

        assertThat(exchange.getAttachment(Identity.KEY).username()).isEqualTo("alice@example.com");
    }

    @Test
    void aTokenWithoutTheUsernameClaimStillHasItsSubject() throws Exception
    {
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token(claims().claim("preferred_username", List.of("a", "b")).build()));

        filter(config()).onClientRequest(exchange);

        assertThat(exchange.getAttachment(Identity.KEY).subject()).isEqualTo("6f1c2d3e");
        assertThat(exchange.getAttachment(Identity.KEY).username()).isNull();
    }

    @Test
    void claimsReadStringsAndStringArraysOnly() throws Exception
    {
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token(claims()
                .claim("roles", List.of("admin", "user")).claim("level", 3).claim("mixed", List.of("a", 1)).build()));

        filter(config()).onClientRequest(exchange);

        final var claims = exchange.getAttachment(Identity.KEY).claims();
        assertThat(claims.strings("roles")).containsExactly("admin", "user");
        assertThat(claims.strings("sub")).containsExactly("6f1c2d3e");
        assertThat(claims.string("level")).isEmpty();
        assertThat(claims.strings("mixed")).isEmpty();
        assertThat(claims.has("level")).isTrue();
        assertThat(claims.has("absent")).isFalse();
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception
    {
        assertRefused(token(claims().expirationTime(Date.from(Instant.now().minusSeconds(600))).build()));
    }

    @Test
    void aTokenThatIsNotYetValidIsRefused() throws Exception
    {
        assertRefused(token(claims().notBeforeTime(Date.from(Instant.now().plusSeconds(600))).build()));
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() throws Exception
    {
        assertRefused(token(claims().issuer("https://auth.example.com/realms/other").build()));
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() throws Exception
    {
        assertRefused(token(claims().audience("admin").build()));
    }

    @Test
    void aTokenWithoutASubjectIsRefused() throws Exception
    {
        assertRefused(token(claims().subject(null).build()));
    }

    @Test
    void anIdTokenIsRefused() throws Exception
    {
        assertRefused(token(claims().build(), new JOSEObjectType("id+jwt")));
    }

    @Test
    void anAccessTokenTypedByRfc9068IsAccepted() throws Exception
    {
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token(claims().build(), new JOSEObjectType("at+jwt")));

        filter(config()).onClientRequest(exchange);

        verify(exchange, never()).shortCircuit(any());
    }

    @Test
    void aTamperedTokenIsRefused() throws Exception
    {
        final String valid = token(claims().build());
        // Not the last character: the last of an unpadded base64url string may carry only padding bits.
        final int index = valid.length() - 10;
        assertRefused(valid.substring(0, index) + (valid.charAt(index) == 'A' ? 'B' : 'A') + valid.substring(index + 1));
    }

    @Test
    void anHmacTokenIsRefused() throws Exception
    {
        // Algorithm confusion: the JWKS is public, so an HS* key is one anybody could sign with.
        final byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), claims().build());
        jwt.sign(new MACSigner(secret));
        assertRefused(jwt.serialize());
    }

    @Test
    void aMissingOrNonBearerAuthorizationIsChallenged()
    {
        for (final String header : new String[]{null, "Basic YWxpY2U6c2VjcmV0"})
        {
            final ClientRequestGatewayExchange exchange = exchange(header);
            filter(config()).onClientRequest(exchange);
            assertUnauthorized(exchange, "Bearer");
        }
    }

    @Test
    void aSecondAuthorizationHeaderIsRefused() throws Exception
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims().build()));
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims().subject("mallory").build()));
        final ClientRequestGatewayExchange exchange = exchange(headers);

        filter(config()).onClientRequest(exchange);

        assertUnauthorized(exchange, "Bearer");
    }

    @Test
    void unreachableKeysAre503Not401() throws Exception
    {
        final JWKSource<SecurityContext> failing = (selector, context) ->
        {
            throw new RemoteKeySourceException("Couldn't retrieve JWK set", new IOException("connect timed out"));
        };
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token(claims().build()));

        new JwtAuthFactory.GF(config(), JwtAuthFactory.buildJwtProcessor(config(), failing)).onClientRequest(exchange);

        final ArgumentCaptor<ShortCircuitGatewayResponse> captor = ArgumentCaptor.forClass(ShortCircuitGatewayResponse.class);
        verify(exchange).shortCircuit(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(HttpStatuses.SERVICE_UNAVAILABLE);
        assertThat(exchange.getAttachment(Identity.KEY)).isNull();
    }

    @Test
    void theClientsTokenIsConsumedUnlessForwarded() throws Exception
    {
        final ClientRequestGatewayExchange consumed = exchange("Bearer " + token(claims().build()));
        filter(config()).onClientRequest(consumed);
        assertThat(consumed.getAttachment(GatewayContextKeys.CLIENT_AUTHORIZATION_CONSUMED)).isTrue();

        final ClientRequestGatewayExchange forwarded = exchange("Bearer " + token(claims().build()));
        filter(config(null, Map.of(), true)).onClientRequest(forwarded);
        assertThat(forwarded.getAttachment(GatewayContextKeys.CLIENT_AUTHORIZATION_CONSUMED)).isNull();
    }

    @Test
    void aStringClaimIsForwardedAndTheClientsOwnValueReplaced() throws Exception
    {
        final JwtAuthFactory.GF filter = filter(config(null, Map.of("role", "X-User-Role"), false));
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token(claims().claim("role", "admin").build()));
        filter.onClientRequest(exchange);

        final UpstreamRequestGatewayExchange upstream = upstream(exchange, "X-User-Role", "superuser");
        filter.onUpstreamRequest(upstream);

        assertThat(upstream.upstreamRequest().headers().getAll("X-User-Role")).containsExactly("admin");
    }

    @Test
    void theClientsOwnValueIsRemovedWhenTheClaimIsAbsentOrNotAString() throws Exception
    {
        final JwtAuthFactory.GF filter = filter(config(null, Map.of("role", "X-User-Role"), false));
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token(claims().claim("role", List.of("admin")).build()));
        filter.onClientRequest(exchange);

        final UpstreamRequestGatewayExchange upstream = upstream(exchange, "X-User-Role", "admin");
        filter.onUpstreamRequest(upstream);

        assertThat(upstream.upstreamRequest().headers().getFirst("X-User-Role")).isNull();
    }

    @Test
    void aClaimThatCannotBeAHeaderValueIsNotForwarded() throws Exception
    {
        final JwtAuthFactory.GF filter = filter(config(null, Map.of("name", "X-User-Name", "role", "X-User-Role"), false));
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token(claims().claim("name", "Łukasz").claim("role", "admin\r\nX-Admin: 1").build()));
        filter.onClientRequest(exchange);

        final UpstreamRequestGatewayExchange upstream = upstream(exchange, "X-User-Name", "mallory");
        filter.onUpstreamRequest(upstream);

        assertThat(upstream.upstreamRequest().headers().getFirst("X-User-Name")).isNull();
        assertThat(upstream.upstreamRequest().headers().getFirst("X-User-Role")).isNull();
    }

    @Test
    void theClientsOwnValueIsRemovedWhenTheTokenWasRefused()
    {
        final JwtAuthFactory.GF filter = filter(config(null, Map.of("role", "X-User-Role"), false));
        final ClientRequestGatewayExchange exchange = exchange((String) null);
        filter.onClientRequest(exchange);

        final UpstreamRequestGatewayExchange upstream = upstream(exchange, "X-User-Role", "admin");
        filter.onUpstreamRequest(upstream);

        assertThat(upstream.upstreamRequest().headers().getFirst("X-User-Role")).isNull();
    }

    @Test
    void aWellFormedConfigValidates()
    {
        assertThat(errors(config(null, Map.of("sub", "X-User-Id"), false))).isEmpty();
    }

    @Test
    void theShortestValidCacheDurationBuildsTheKeySource()
    {
        final JwtAuthFactory.Config config = new JwtAuthFactory.Config("https://auth.example.com/keys", ISSUER, AUDIENCE, null, null,
                JwtAuthFactory.MIN_JWKS_CACHE_DURATION, null, null);
        assertThat(errors(config)).isEmpty();
        assertThat(new JwtAuthFactory().create(config, null)).isNotNull();
    }

    @Test
    void theIssuerAudienceAndJwksUrlAreRequired()
    {
        assertThat(errors(new JwtAuthFactory.Config(null, null, null, null, null, null, null, null)))
                .anyMatch(error -> error.contains("jwks_url"))
                .anyMatch(error -> error.contains("issuer"))
                .anyMatch(error -> error.contains("audience"));
    }

    @Test
    void badValuesAreRefused()
    {
        assertThat(errors(new JwtAuthFactory.Config("ftp://example.com/keys", ISSUER, AUDIENCE, null, null, null, null, null)))
                .singleElement().asString().contains("http or https");
        assertThat(errors(new JwtAuthFactory.Config("https://example.com/keys", ISSUER, AUDIENCE, null, 301, null, null, null)))
                .singleElement().asString().contains("between 0 and 300");
        assertThat(errors(new JwtAuthFactory.Config("https://example.com/keys", ISSUER, AUDIENCE, null, -1, null, null, null)))
                .singleElement().asString().contains("between 0 and 300");
        assertThat(errors(new JwtAuthFactory.Config("https://example.com/keys", ISSUER, AUDIENCE, null, null, Duration.ofSeconds(59), null, null)))
                .singleElement().asString().contains("at least");
        assertThat(errors(config(null, Map.of("sub", "Authorization"), false))).singleElement().asString().contains("forward_credentials");
        assertThat(errors(config(null, Map.of("sub", "X-User", "email", "x-user"), false))).singleElement().asString().contains("another claim");
    }

    private void assertRefused(final String token)
    {
        final ClientRequestGatewayExchange exchange = exchange("Bearer " + token);
        filter(config()).onClientRequest(exchange);
        assertUnauthorized(exchange, INVALID_TOKEN);
    }

    private static void assertUnauthorized(final ClientRequestGatewayExchange exchange, final String challenge)
    {
        final ArgumentCaptor<ShortCircuitGatewayResponse> captor = ArgumentCaptor.forClass(ShortCircuitGatewayResponse.class);
        verify(exchange).shortCircuit(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(HttpStatuses.UNAUTHORIZED);
        assertThat(captor.getValue().headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(challenge);
        verify(exchange, never()).setAttachment(eq(Identity.KEY), any());
    }

    private static List<String> errors(final JwtAuthFactory.Config config)
    {
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        return result.getErrors();
    }

    private static JwtAuthFactory.GF filter(final JwtAuthFactory.Config config)
    {
        return new JwtAuthFactory.GF(config, JwtAuthFactory.buildJwtProcessor(config, keys));
    }

    private static JwtAuthFactory.Config config()
    {
        return config(null, Map.of(), false);
    }

    private static JwtAuthFactory.Config config(final String usernameClaim, final Map<String, String> forwardClaims, final boolean forwardCredentials)
    {
        return new JwtAuthFactory.Config("https://auth.example.com/keys", ISSUER, AUDIENCE, usernameClaim, null, null, forwardCredentials, forwardClaims);
    }

    private static JWTClaimsSet.Builder claims()
    {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("6f1c2d3e")
                .expirationTime(Date.from(Instant.now().plusSeconds(60)));
    }

    private static String token(final JWTClaimsSet claims) throws Exception
    {
        return token(claims, JOSEObjectType.JWT);
    }

    private static String token(final JWTClaimsSet claims, final JOSEObjectType type) throws Exception
    {
        final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(type).build(), claims);
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }

    private static ClientRequestGatewayExchange exchange(final String authorization)
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        if (authorization != null)
        {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return exchange(headers);
    }

    private static ClientRequestGatewayExchange exchange(final MutableGatewayHeaders headers)
    {
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.headers()).thenReturn(headers);
        final ClientRequestGatewayExchange exchange = mock(ClientRequestGatewayExchange.class);
        when(exchange.clientRequest()).thenReturn(request);
        when(exchange.attributes()).thenReturn(mock(MutableGatewayAttributes.class));

        final Map<StateKey<?>, Object> attachments = new HashMap<>();
        doAnswer(invocation -> attachments.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(exchange).setAttachment(any(), any());
        when(exchange.getAttachment(any())).thenAnswer(invocation -> attachments.get(invocation.getArgument(0, StateKey.class)));
        return exchange;
    }

    /**
     * The upstream request starts as the client's own, so it carries whatever the client sent.
     */
    private static UpstreamRequestGatewayExchange upstream(final ClientRequestGatewayExchange client, final String header, final String clientValue)
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.set(header, clientValue);
        final MutableGatewayRequest request = mock(MutableGatewayRequest.class);
        when(request.headers()).thenReturn(headers);
        final UpstreamRequestGatewayExchange exchange = mock(UpstreamRequestGatewayExchange.class);
        when(exchange.upstreamRequest()).thenReturn(request);
        when(exchange.getAttachment(any())).thenAnswer(invocation -> client.getAttachment(invocation.getArgument(0, StateKey.class)));
        return exchange;
    }
}
