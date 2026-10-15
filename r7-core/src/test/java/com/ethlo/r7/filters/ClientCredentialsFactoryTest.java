package com.ethlo.r7.filters;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The filter against a real token endpoint on loopback, so the request it sends is the one on the
 * wire. The returned token is verified against an in-memory key set: fetching a remote JWKS is
 * Nimbus's to test, and {@code JwtAuthFactoryTest} covers the verification rules themselves.
 */
class ClientCredentialsFactoryTest
{
    private static final String ISSUER = "https://auth.example.com/realms/acme";
    private static final String AUDIENCE = "billing";
    private static final String CHALLENGE = "Basic realm=\"Secure Area\"";

    private static RSAPrivateKey privateKey;
    private static JWKSource<SecurityContext> keys;

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<Received, Answer> responder;

    record Received(String authorization, String contentType, String body)
    {
        String basicPair()
        {
            return new String(Base64.getDecoder().decode(this.authorization.substring("Basic ".length())), UTF_8);
        }
    }

    /**
     * @param bodyGate when set, the head is sent at once and the body only once it opens
     */
    record Answer(int status, String body, CountDownLatch bodyGate)
    {
        Answer(final int status, final String body)
        {
            this(status, body, null);
        }
    }

    @BeforeAll
    static void generateKeyPair() throws Exception
    {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair keyPair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        keys = new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).keyID("k1").build()));
    }

    @BeforeEach
    void startTokenEndpoint() throws IOException
    {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/token", this::handle);
        this.server.start();
        this.responder = request -> tokenFor("legacy-erp", 300);
    }

    @AfterEach
    void stopTokenEndpoint()
    {
        this.server.stop(0);
    }

    private void handle(final HttpExchange exchange) throws IOException
    {
        this.calls.incrementAndGet();
        final Received request = new Received(
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), UTF_8));
        this.received.add(request);
        final Answer answer = this.responder.apply(request);
        final byte[] body = answer.body().getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (answer.bodyGate() != null)
        {
            exchange.sendResponseHeaders(answer.status(), 0);
            exchange.getResponseBody().flush();
            await(answer.bodyGate());
        }
        else
        {
            exchange.sendResponseHeaders(answer.status(), body.length);
        }
        try
        {
            exchange.getResponseBody().write(body);
            exchange.close();
        }
        catch (final IOException e)
        {
            // The client gave up on a stalled body, as it should.
        }
    }

    @Test
    void theCredentialsAreExchangedAndTheTokenReplacesThem() throws Exception
    {
        final Answer answer = tokenFor("legacy-erp", 300);
        this.responder = request -> answer;
        final ClientRequestGatewayExchange exchange = exchange(basic("legacy-erp", "s3cret"));
        final ClientCredentialsFactory.GF filter = filter(config("billing.read"));

        filter.onClientRequest(exchange);

        verify(exchange, never()).shortCircuit(any());
        final Received request = this.received.getFirst();
        assertThat(request.basicPair()).isEqualTo("legacy-erp:s3cret");
        assertThat(request.contentType()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(request.body()).isEqualTo("grant_type=client_credentials&scope=billing.read");

        final Identity identity = exchange.getAttachment(Identity.KEY);
        assertThat(identity.scheme()).isEqualTo("client_credentials");
        assertThat(identity.issuer()).isEqualTo(ISSUER);
        assertThat(identity.subject()).isEqualTo("service-account-legacy-erp");
        assertThat(identity.username()).isEqualTo("legacy-erp");
        assertThat(exchange.getAttachment(GatewayContextKeys.CLIENT_AUTHORIZATION_CONSUMED)).isTrue();

        final UpstreamRequestGatewayExchange upstream = upstream(exchange);
        filter.onUpstreamRequest(upstream);
        assertThat(upstream.upstreamRequest().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer " + accessToken(answer));
    }

    @Test
    void theIdAndSecretAreFormEncodedAsRfc6749Requires() throws Exception
    {
        filter(config(null)).onClientRequest(exchange(basic("line 7", "p:w&d%+")));

        assertThat(this.received.getFirst().basicPair()).isEqualTo("line+7:p%3Aw%26d%25%2B");
        assertThat(this.received.getFirst().body()).isEqualTo("grant_type=client_credentials");
    }

    @Test
    void theUsernameFallsBackToAzpThenToTheClientId() throws Exception
    {
        this.responder = request -> answer(claims().claim("azp", "erp-azp").build());
        final ClientRequestGatewayExchange viaAzp = exchange(basic("legacy-erp", "s3cret"));
        filter(config(null)).onClientRequest(viaAzp);
        assertThat(viaAzp.getAttachment(Identity.KEY).username()).isEqualTo("erp-azp");

        this.responder = request -> answer(claims().build());
        final ClientRequestGatewayExchange viaBasic = exchange(basic("legacy-erp", "s3cret"));
        filter(config(null)).onClientRequest(viaBasic);
        assertThat(viaBasic.getAttachment(Identity.KEY).username()).isEqualTo("legacy-erp");
    }

    @Test
    void aTokenIsReusedUntilShortlyBeforeItExpires() throws Exception
    {
        final ClientCredentialsFactory.GF filter = filter(config(null));

        filter.onClientRequest(exchange(basic("legacy-erp", "s3cret")));
        final ClientRequestGatewayExchange second = exchange(basic("legacy-erp", "s3cret"));
        filter.onClientRequest(second);

        assertThat(this.calls).hasValue(1);
        verify(second, never()).shortCircuit(any());
        assertThat(second.getAttachment(Identity.KEY).username()).isEqualTo("legacy-erp");
    }

    @Test
    void aTokenWithinTheExpiryMarginIsUsedOnceAndNotCached() throws Exception
    {
        this.responder = request -> tokenFor("legacy-erp", 20);
        final ClientCredentialsFactory.GF filter = filter(config(null));

        final ClientRequestGatewayExchange first = exchange(basic("legacy-erp", "s3cret"));
        filter.onClientRequest(first);
        filter.onClientRequest(exchange(basic("legacy-erp", "s3cret")));

        verify(first, never()).shortCircuit(any());
        assertThat(this.calls).hasValue(2);
    }

    @Test
    void aDifferentSecretForTheSameIdIsExchangedAgain() throws Exception
    {
        this.responder = request -> request.basicPair().equals("legacy-erp:s3cret")
                ? tokenFor("legacy-erp", 300) : new Answer(401, "{\"error\":\"invalid_client\"}");
        final ClientCredentialsFactory.GF filter = filter(config(null));

        filter.onClientRequest(exchange(basic("legacy-erp", "s3cret")));
        final ClientRequestGatewayExchange guess = exchange(basic("legacy-erp", "guess"));
        filter.onClientRequest(guess);

        assertThat(this.calls).hasValue(2);
        assertUnauthorized(guess);
    }

    @Test
    void refusedCredentialsAre401AndNeverCached()
    {
        this.responder = request -> new Answer(401, "{\"error\":\"invalid_client\"}");
        final ClientCredentialsFactory.GF filter = filter(config(null));

        final ClientRequestGatewayExchange first = exchange(basic("legacy-erp", "wrong"));
        filter.onClientRequest(first);
        final ClientRequestGatewayExchange second = exchange(basic("legacy-erp", "wrong"));
        filter.onClientRequest(second);

        assertUnauthorized(first);
        assertUnauthorized(second);
        assertThat(this.calls).hasValue(2);
    }

    @Test
    void only400ErrorsAboutTheClientAre401()
    {
        for (final String error : List.of("invalid_client", "unauthorized_client", "invalid_grant"))
        {
            this.responder = request -> new Answer(400, "{\"error\":\"" + error + "\"}");
            final ClientRequestGatewayExchange exchange = exchange(basic("legacy-erp", "s3cret"));
            filter(config(null)).onClientRequest(exchange);
            assertUnauthorized(exchange);
        }
        for (final String error : List.of("invalid_scope", "invalid_request", "unsupported_grant_type"))
        {
            this.responder = request -> new Answer(400, "{\"error\":\"" + error + "\"}");
            assertUnavailable(exchange(basic("legacy-erp", "s3cret")), filter(config(null)));
        }
    }

    @Test
    void a401IsRefusedWithoutReadingItsBody()
    {
        this.responder = request -> new Answer(401, "x".repeat(ClientCredentialsFactory.MAX_RESPONSE_BYTES * 2));
        final ClientRequestGatewayExchange exchange = exchange(basic("legacy-erp", "wrong"));

        filter(config(null)).onClientRequest(exchange);

        assertUnauthorized(exchange);
    }

    @Test
    void anOversizedTokenResponseIs503()
    {
        this.responder = request -> new Answer(200, "{\"pad\":\"" + "x".repeat(ClientCredentialsFactory.MAX_RESPONSE_BYTES) + "\"}");

        assertUnavailable(exchange(basic("legacy-erp", "s3cret")), filter(config(null)));
    }

    @Test
    void aStalledBodyIs503WithinTheDeadline()
    {
        final CountDownLatch never = new CountDownLatch(1);
        this.responder = request -> new Answer(200, "{}", never);
        final ClientCredentialsFactory.Config config = config(null);
        final ClientCredentialsFactory.GF filter = new ClientCredentialsFactory.GF(config,
                JwtVerification.processor(keys, config.issuer(), config.audience(), 60), Duration.ofMillis(300));

        final long started = System.nanoTime();
        assertUnavailable(exchange(basic("legacy-erp", "s3cret")), filter);
        never.countDown();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void anythingButATokenResponseIs503()
    {
        final List<Answer> answers = List.of(
                new Answer(500, "{}"),
                new Answer(302, ""),
                new Answer(200, "not json"),
                new Answer(200, "{\"token_type\":\"Bearer\"}"),
                new Answer(200, "{\"access_token\":\"opaque-token\",\"token_type\":\"Bearer\"}"));
        for (final Answer answer : answers)
        {
            this.responder = request -> answer;
            assertUnavailable(exchange(basic("legacy-erp", "s3cret")), filter(config(null)));
        }
    }

    @Test
    void aTokenTypeOtherThanBearerIs503() throws Exception
    {
        final String token = accessToken(tokenFor("legacy-erp", 300));
        this.responder = request -> new Answer(200, "{\"access_token\":\"" + token + "\",\"token_type\":\"DPoP\"}");

        assertUnavailable(exchange(basic("legacy-erp", "s3cret")), filter(config(null)));
    }

    @Test
    void aTokenThatFailsVerificationIs503Not401()
    {
        this.responder = request -> answer(claims().audience("some-other-client").build());

        assertUnavailable(exchange(basic("legacy-erp", "s3cret")), filter(config(null)));
    }

    @Test
    void unreachableKeysAre503()
    {
        final JWKSource<SecurityContext> failing = (selector, context) ->
        {
            throw new RemoteKeySourceException("Couldn't retrieve JWK set", new IOException("connect timed out"));
        };
        final ClientCredentialsFactory.Config config = config(null);
        final ClientCredentialsFactory.GF filter = new ClientCredentialsFactory.GF(config,
                JwtVerification.processor(failing, config.issuer(), config.audience(), 60));

        assertUnavailable(exchange(basic("legacy-erp", "s3cret")), filter);
    }

    @Test
    void anUnreachableTokenEndpointIs503() throws Exception
    {
        final int closedPort;
        try (ServerSocket socket = new ServerSocket(0))
        {
            closedPort = socket.getLocalPort();
        }
        final ClientCredentialsFactory.Config config = new ClientCredentialsFactory.Config(
                "http://127.0.0.1:" + closedPort + "/token", "https://auth.example.com/keys", ISSUER, AUDIENCE, null, null, null);

        assertUnavailable(exchange(basic("legacy-erp", "s3cret")), filter(config));
    }

    @Test
    void requestsWithoutUsableBasicCredentialsNeverReachTheTokenEndpoint()
    {
        final MutableGatewayHeaders twice = new MutableFastGatewayHeaders();
        twice.add(HttpHeaders.AUTHORIZATION, basic("legacy-erp", "s3cret"));
        twice.add(HttpHeaders.AUTHORIZATION, basic("other", "s3cret"));
        final List<ClientRequestGatewayExchange> exchanges = new ArrayList<>(List.of(
                exchange((String) null),
                exchange("Bearer " + "abc"),
                exchange("Basic "),
                exchange("Basic !!!not-base64"),
                exchange("Basic " + Base64.getEncoder().encodeToString("no-colon".getBytes(UTF_8))),
                exchange(basic("", "s3cret")),
                exchange(basic("legacy-erp", "")),
                exchange(twice)));

        for (final ClientRequestGatewayExchange exchange : exchanges)
        {
            filter(config(null)).onClientRequest(exchange);
            assertUnauthorized(exchange);
        }
        assertThat(this.calls).hasValue(0);
    }

    @Test
    void concurrentRequestsWithTheSameCredentialsShareOneExchange() throws Exception
    {
        final CountDownLatch release = new CountDownLatch(1);
        this.responder = request ->
        {
            await(release);
            return tokenFor("legacy-erp", 300);
        };
        final ClientCredentialsFactory.GF filter = filter(config(null));

        final List<ClientRequestGatewayExchange> exchanges = new ArrayList<>();
        final List<Future<?>> done = new ArrayList<>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor())
        {
            for (int i = 0; i < 8; i++)
            {
                final ClientRequestGatewayExchange exchange = exchange(basic("legacy-erp", "s3cret"));
                exchanges.add(exchange);
                done.add(threads.submit(() -> filter.onClientRequest(exchange)));
            }
            Thread.sleep(200);
            release.countDown();
            for (final Future<?> future : done)
            {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(this.calls).hasValue(1);
        for (final ClientRequestGatewayExchange exchange : exchanges)
        {
            verify(exchange, never()).shortCircuit(any());
            assertThat(exchange.getAttachment(Identity.KEY).username()).isEqualTo("legacy-erp");
        }
    }

    @Test
    void exchangesBeyondTheCapAreShedWith503() throws Exception
    {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch firstArrived = new CountDownLatch(1);
        this.responder = request ->
        {
            firstArrived.countDown();
            await(release);
            return tokenFor("legacy-erp", 300);
        };
        final ClientCredentialsFactory.GF filter = capOfOne();

        try (var threads = Executors.newVirtualThreadPerTaskExecutor())
        {
            final ClientRequestGatewayExchange first = exchange(basic("legacy-erp", "s3cret"));
            final Future<?> firstDone = threads.submit(() -> filter.onClientRequest(first));
            assertThat(firstArrived.await(5, TimeUnit.SECONDS)).isTrue();

            assertUnavailable(exchange(basic("another-line", "s3cret")), filter);

            release.countDown();
            firstDone.get(10, TimeUnit.SECONDS);
            verify(first, never()).shortCircuit(any());
        }
        assertThat(this.calls).hasValue(1);
    }

    @Test
    void theCapCoversTheWholeExchangeNotJustTheResponseHead() throws Exception
    {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch firstArrived = new CountDownLatch(1);
        this.responder = request ->
        {
            firstArrived.countDown();
            final Answer token = tokenFor("legacy-erp", 300);
            return new Answer(token.status(), token.body(), release);
        };
        final ClientCredentialsFactory.GF filter = capOfOne();

        try (var threads = Executors.newVirtualThreadPerTaskExecutor())
        {
            final ClientRequestGatewayExchange first = exchange(basic("legacy-erp", "s3cret"));
            final Future<?> firstDone = threads.submit(() -> filter.onClientRequest(first));
            assertThat(firstArrived.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);

            assertUnavailable(exchange(basic("another-line", "s3cret")), filter);

            release.countDown();
            firstDone.get(10, TimeUnit.SECONDS);
            verify(first, never()).shortCircuit(any());
        }
    }

    private ClientCredentialsFactory.GF capOfOne()
    {
        return filter(new ClientCredentialsFactory.Config(tokenUrl(), "https://auth.example.com/keys", ISSUER, AUDIENCE, null, null, 1));
    }

    @Test
    void requiredSettingsAreNamed()
    {
        assertThat(errors(new ClientCredentialsFactory.Config(null, null, null, null, null, null, null)))
                .anyMatch(error -> error.contains("token_url"))
                .anyMatch(error -> error.contains("jwks_url"))
                .anyMatch(error -> error.contains("issuer"))
                .anyMatch(error -> error.contains("audience"));
    }

    @Test
    void badValuesAreRefused()
    {
        assertThat(errors(withTokenUrl("http://auth.example.com/token"))).singleElement().asString().contains("https");
        assertThat(errors(withTokenUrl("ftp://auth.example.com/token"))).singleElement().asString().contains("http or https");
        for (final String withCredentials : List.of("https://gateway:secret@auth.example.com/token",
                "http://gateway:secret@auth.example.com/token", "https://gateway:secret@auth example.com/token"))
        {
            assertThat(errors(withTokenUrl(withCredentials))).as(withCredentials)
                    .singleElement().asString().contains("'@'").doesNotContain("secret");
        }
        assertThat(errors(new ClientCredentialsFactory.Config(tokenUrl(), "https://auth.example.com/keys", ISSUER, AUDIENCE, "a  b", null, null)))
                .singleElement().asString().contains("scope");
        assertThat(errors(new ClientCredentialsFactory.Config(tokenUrl(), "https://auth.example.com/keys", ISSUER, AUDIENCE, "a\"b", null, null)))
                .singleElement().asString().contains("scope");
        assertThat(errors(new ClientCredentialsFactory.Config(tokenUrl(), "https://auth.example.com/keys", ISSUER, AUDIENCE, null, "bad\nrealm", null)))
                .singleElement().asString().contains("realm");
        assertThat(errors(new ClientCredentialsFactory.Config(tokenUrl(), "https://auth.example.com/keys", ISSUER, AUDIENCE, null, null, 0)))
                .singleElement().asString().contains("between 1 and");
    }

    @Test
    void plainHttpIsAcceptedOnlyOnALoopbackHost()
    {
        for (final String url : List.of("http://127.0.0.1:8080/token", "http://localhost/token", "http://[::1]:8080/token", "https://auth.example.com/token"))
        {
            assertThat(errors(withTokenUrl(url))).as(url).isEmpty();
        }
        for (final String url : List.of("http://10.0.0.1/token", "http://localhost.example.com/token", "http://127.0.0.1.example.com/token"))
        {
            assertThat(errors(withTokenUrl(url))).as(url).singleElement().asString().contains("https");
        }
    }

    private static ClientCredentialsFactory.Config withTokenUrl(final String url)
    {
        return new ClientCredentialsFactory.Config(url, "https://auth.example.com/keys", ISSUER, AUDIENCE, null, null, null);
    }

    private static void await(final CountDownLatch latch)
    {
        try
        {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertUnauthorized(final ClientRequestGatewayExchange exchange)
    {
        final ArgumentCaptor<ShortCircuitGatewayResponse> captor = ArgumentCaptor.forClass(ShortCircuitGatewayResponse.class);
        verify(exchange).shortCircuit(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(HttpStatuses.UNAUTHORIZED);
        assertThat(captor.getValue().headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(CHALLENGE);
        verify(exchange, never()).setAttachment(eq(Identity.KEY), any());
    }

    private static void assertUnavailable(final ClientRequestGatewayExchange exchange, final ClientCredentialsFactory.GF filter)
    {
        filter.onClientRequest(exchange);
        final ArgumentCaptor<ShortCircuitGatewayResponse> captor = ArgumentCaptor.forClass(ShortCircuitGatewayResponse.class);
        verify(exchange).shortCircuit(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(HttpStatuses.SERVICE_UNAVAILABLE);
        assertThat(captor.getValue().headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        verify(exchange, never()).setAttachment(eq(Identity.KEY), any());
    }

    private static List<String> errors(final ClientCredentialsFactory.Config config)
    {
        final ValidationResult result = new ValidationResult();
        config.validate(result);
        return result.getErrors();
    }

    private String tokenUrl()
    {
        return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
    }

    private ClientCredentialsFactory.Config config(final String scope)
    {
        return new ClientCredentialsFactory.Config(tokenUrl(), "https://auth.example.com/keys", ISSUER, AUDIENCE, scope, null, null);
    }

    private static ClientCredentialsFactory.GF filter(final ClientCredentialsFactory.Config config)
    {
        return new ClientCredentialsFactory.GF(config, JwtVerification.processor(keys, config.issuer(), config.audience(), 60));
    }

    private static JWTClaimsSet.Builder claims()
    {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("service-account-legacy-erp")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }

    private static Answer tokenFor(final String clientId, final int lifetimeSeconds)
    {
        return answer(claims().claim("client_id", clientId).expirationTime(Date.from(Instant.now().plusSeconds(lifetimeSeconds))).build());
    }

    private static Answer answer(final JWTClaimsSet claims)
    {
        try
        {
            final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(privateKey));
            return new Answer(200, "{\"access_token\":\"" + jwt.serialize() + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
        }
        catch (final Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static String accessToken(final Answer answer) throws Exception
    {
        return (String) com.nimbusds.jose.util.JSONObjectUtils.parse(answer.body()).get("access_token");
    }

    private static String basic(final String id, final String secret)
    {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(UTF_8));
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

        final Map<StateKey<?>, Object> attachments = new ConcurrentHashMap<>();
        doAnswer(invocation -> attachments.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(exchange).setAttachment(any(), any());
        when(exchange.getAttachment(any())).thenAnswer(invocation -> attachments.get(invocation.getArgument(0, StateKey.class)));
        return exchange;
    }

    /**
     * The upstream request as the gateway hands it over: the client's Authorization is already
     * gone, since the filter marked it consumed.
     */
    private static UpstreamRequestGatewayExchange upstream(final ClientRequestGatewayExchange client)
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        final MutableGatewayRequest request = mock(MutableGatewayRequest.class);
        when(request.headers()).thenReturn(headers);
        final UpstreamRequestGatewayExchange exchange = mock(UpstreamRequestGatewayExchange.class);
        when(exchange.upstreamRequest()).thenReturn(request);
        when(exchange.getAttachment(any())).thenAnswer(invocation -> client.getAttachment(invocation.getArgument(0, StateKey.class)));
        return exchange;
    }
}
