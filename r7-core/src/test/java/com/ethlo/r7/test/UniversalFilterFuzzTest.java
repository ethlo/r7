package com.ethlo.r7.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.ethlo.r7.api.ClientRequestGatewayExchange;
import com.ethlo.r7.api.ClientRequestGatewayFilter;
import com.ethlo.r7.api.ClientResponseGatewayExchange;
import com.ethlo.r7.api.ClientResponseGatewayFilter;
import com.ethlo.r7.api.CompletedGatewayExchange;
import com.ethlo.r7.api.CompletedGatewayFilter;
import com.ethlo.r7.api.Cookies;
import com.ethlo.r7.api.GatewayFilter;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.GatewayRouteInfo;
import com.ethlo.r7.api.MultiAttributes;
import com.ethlo.r7.api.MutableCookies;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.MutableGatewayResponse;
import com.ethlo.r7.api.MutableQueryParams;
import com.ethlo.r7.api.QueryParams;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public class UniversalFilterFuzzTest
{
    private static final Logger logger = LoggerFactory.getLogger(UniversalFilterFuzzTest.class);
    private static final List<GatewayFilterFactory<?>> FILTER_FACTORIES = loadAllFilterFactories();
    private static final List<GatewayPredicateFactory<?>> PREDICATE_FACTORIES = loadAllPredicateFactories();

    private static List<GatewayPredicateFactory<?>> loadAllPredicateFactories()
    {
        final List<GatewayPredicateFactory<?>> list = new ArrayList<>();
        ServiceLoader.load(GatewayPredicateFactory.class).forEach(list::add);
        return list;
    }

    private static List<GatewayFilterFactory<?>> loadAllFilterFactories()
    {
        final List<GatewayFilterFactory<?>> list = new ArrayList<>();
        ServiceLoader.load(GatewayFilterFactory.class).forEach(list::add);
        return list;
    }

    @FuzzTest
    @SuppressWarnings("unchecked")
    void fuzzFiltersAndPredicates(final FuzzedDataProvider data)
    {
        if (data.consumeBoolean())
        {
            fuzzFilter(data);
        }
        else
        {
            fuzzPredicate(data);
        }
    }

    @SuppressWarnings("unchecked")
    private void fuzzFilter(final FuzzedDataProvider data)
    {
        if (FILTER_FACTORIES.isEmpty())
        {
            return;
        }

        final GatewayFilterFactory<?> factory = data.pickValue(FILTER_FACTORIES);
        final Class<?> configClass = factory.configClass();

        final ValidatableConfig fuzzedConfig;
        try
        {
            fuzzedConfig = generateFuzzedRecord(configClass, data);
        }
        catch (final Exception e)
        {
            logger.info("Creating config throws: {}", e.getMessage(), e);
            return;
        }

        final ValidationResult result = new ValidationResult();
        fuzzedConfig.validate(result);
        if (result.hasErrors())
        {
            return;
        }

        // NO EXCEPTION CAPTURING - Let it crash!
        final GatewayFilterFactory<ValidatableConfig> typedFactory = (GatewayFilterFactory<ValidatableConfig>) factory;
        final GatewayFilter filter = typedFactory.create(fuzzedConfig, null);

        if (filter instanceof ClientRequestGatewayFilter clientRequestGatewayFilter)
        {
            final ClientRequestGatewayExchange safeExchange = createSafeRequestExchange(data);
            clientRequestGatewayFilter.onClientRequest(safeExchange);
        }
        else if (filter instanceof UpstreamRequestGatewayFilter upstreamRequestGatewayFilter)
        {
            final UpstreamRequestGatewayExchange safeExchange = createSafeUpstreamExchange(data);
            upstreamRequestGatewayFilter.onUpstreamRequest(safeExchange);
        }
        else if (filter instanceof ClientResponseGatewayFilter clientResponseGatewayFilter)
        {
            final ClientResponseGatewayExchange safeExchange = createSafeResponseExchange(data);
            clientResponseGatewayFilter.onClientResponse(safeExchange);
        }
        else if (filter instanceof CompletedGatewayFilter completedGatewayFilter)
        {
            final CompletedGatewayExchange safeExchange = createSafeCompletedResponseExchange(data);
            completedGatewayFilter.onCompleted(safeExchange);
        }

        // Harvest boilerplate coverage
        assertThat(factory.name()).isNotNull();
        assertThat(factory.configClass()).isNotNull();
        assertThat(fuzzedConfig.toString()).isNotNull();
        assertThat(filter.name()).isNotNull();
        assertThat(filter.summary()).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private void fuzzPredicate(final FuzzedDataProvider data)
    {
        if (PREDICATE_FACTORIES.isEmpty())
        {
            return;
        }

        final GatewayPredicateFactory<?> factory = data.pickValue(PREDICATE_FACTORIES);
        final Class<?> configClass = factory.configClass();

        final ValidatableConfig fuzzedConfig;
        try
        {
            fuzzedConfig = generateFuzzedRecord(configClass, data);
        }
        catch (final Exception e)
        {
            logger.info("Creating config throws: {}", e.getMessage(), e);
            return;
        }

        final ValidationResult result = new ValidationResult();
        fuzzedConfig.validate(result);
        if (result.hasErrors())
        {
            return;
        }

        final GatewayPredicateFactory<ValidatableConfig> typedFactory = (GatewayPredicateFactory<ValidatableConfig>) factory;

        // Assuming the factory creates a standard Java Predicate or a custom GatewayPredicate
        final GatewayPredicate predicate = typedFactory.create(fuzzedConfig);
        final GatewayRequest safeExchange = buildPredicateRequest(data);

        predicate.test(safeExchange);

        // Harvest boilerplate coverage
        assertThat(factory.name()).isNotNull();
        assertThat(factory.configClass()).isNotNull();
        assertThat(fuzzedConfig.toString()).isNotNull();
        assertThat(predicate.name()).isNotNull();
        assertThat(predicate.summary()).isNotNull();
    }

    private ValidatableConfig generateFuzzedRecord(final Class<?> recordClass, final FuzzedDataProvider data) throws Exception
    {
        final RecordComponent[] components = recordClass.getRecordComponents();
        final Object[] args = new Object[components.length];
        final Class<?>[] types = new Class<?>[components.length];

        for (int i = 0; i < components.length; i++)
        {
            final Class<?> type = components[i].getType();
            types[i] = type;

            // Let Jazzer decide if an object should be null (20% chance is usually good for fuzzing)
            final boolean injectNull = !type.isPrimitive() && data.consumeBoolean();

            if (injectNull)
            {
                args[i] = null;
            }
            else if (type == String.class)
            {
                final String componentName = components[i].getName();

                args[i] = switch (componentName)
                {
                    // Add networking strings for RemoteAddrFactory
                    case "sources", "address", "cidr", "ip" -> data.pickValue(new String[]{
                            "127.0.0.1", "192.168.1.0/24", "10.0.0.0/8", "::1", "invalid-cidr-block"
                    });

                    // Force Jazzer to generate string paths that actually match typical regex patterns
                    case "source", "pattern" -> data.pickValue(new String[]{
                            "^/api/v1/.*", "^/users/(\\w+)/posts/(\\d+)", "/static/(.*)", "^/.*"
                    });
                    // Force strings that contain valid, malformed, or malicious template markers
                    case "target", "replacement" -> data.pickValue(new String[]{
                            "/v2/$1", "/archive/{{1}}/{{2}}", "/error?code={{status}}", "$6", "\\$1"
                    });
                    // General strings (headers, keys)
                    default -> data.consumeString(50);
                };
            }
            // Instead of raw data.consumeLong() which spans the entire 64-bit space:
            else if (type == long.class || type == Long.class)
            {
                // Limit capacity and tokens to a wide but sane range (0 to 100 Million)
                args[i] = data.consumeLong(0L, 100_000_000L);
            }
            else if (type == int.class || type == Integer.class)
            {
                // Limit statuses to valid HTTP ranges, maxBuckets to a sane ceiling
                if (components[i].getName().equals("status"))
                {
                    args[i] = data.pickValue(new Integer[]{200, 301, 302, 400, 401, 403, 404, 500});
                }
                else
                {
                    args[i] = data.consumeInt(0, 1_000_000);
                }
            }
            else if (type == boolean.class || type == Boolean.class)
            {
                args[i] = data.consumeBoolean();
            }
            else if (type == java.time.Duration.class)
            {
                // Jazzer will explore all 4 of these branches over thousands of iterations
                final int strategy = data.consumeInt(0, 3);

                args[i] = switch (strategy)
                {
                    case 0 -> java.time.Duration.ZERO;
                    // Test negatives and typical small limits (-10s to 10m)
                    case 1 -> java.time.Duration.ofSeconds(data.consumeInt(-10, 600));
                    // Test massive/overflow durations
                    case 2 -> java.time.Duration.ofMillis(data.consumeLong());
                    // Test your specific boundary request (10s)
                    default -> java.time.Duration.ofSeconds(10);
                };
            }
            else if (type.getSimpleName().equals("DataSize"))
            {
                // Assuming your r7 DataSize class has a static factory like DataSize.ofBytes(long)
                final int strategy = data.consumeInt(0, 3);

                final long fuzzedBytes = switch (strategy)
                {
                    case 0 -> 0L;
                    // Test negative sizes and typical payload sizes (-1KB to 1GB)
                    case 1 -> data.consumeLong(-1024L, 1073741824L);
                    // Extreme Long boundaries
                    case 2 -> data.consumeLong();
                    // Test your specific boundary request (200MB)
                    default -> 209715200L;
                };

                try
                {
                    // Dynamically invoke DataSize.ofBytes() so we don't break the test
                    // if you move the class to a different package later.
                    final java.lang.reflect.Method ofBytesMethod = type.getMethod("ofBytes", long.class);
                    args[i] = ofBytesMethod.invoke(null, fuzzedBytes);
                }
                catch (final Exception exception)
                {
                    // If the custom class uses a different factory method (like parse(String)),
                    // you can catch it here and fall back to that.
                    args[i] = null;
                }
            }
            else if (java.util.Map.class.isAssignableFrom(type))
            {
                // Fuzzing generic maps (like adding random headers)
                final java.util.Map<String, String> fuzzedMap = new java.util.HashMap<>();
                final int mapSize = data.consumeInt(0, 5);
                for (int j = 0; j < mapSize; j++)
                {
                    fuzzedMap.put(data.consumeString(10), data.consumeString(20));
                }
                args[i] = java.util.Collections.unmodifiableMap(fuzzedMap);
            }
            else if (java.util.List.class.isAssignableFrom(type))
            {
                // Fuzzing generic lists (like lists of allowed origins for CORS)
                final java.util.List<String> fuzzedList = new java.util.ArrayList<>();
                final int listSize = data.consumeInt(0, 5);
                for (int j = 0; j < listSize; j++)
                {
                    fuzzedList.add(data.consumeString(20));
                }
                args[i] = java.util.Collections.unmodifiableList(fuzzedList);
            }
            else
            {
                // Ultimate fallback for completely unknown types
                args[i] = null;
            }
        }

        final Constructor<?> constructor = recordClass.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return (ValidatableConfig) constructor.newInstance(args);
    }

    private ClientRequestGatewayExchange createSafeRequestExchange(final FuzzedDataProvider data)
    {
        final ClientRequestGatewayExchange exchange = Mockito.mock(ClientRequestGatewayExchange.class);
        final GatewayRequest request = buildFuzzedRequest(data);
        final GatewayRouteInfo routeInfo = Mockito.mock(GatewayRouteInfo.class);

        Mockito.when(exchange.clientRequest()).thenReturn(request);
        Mockito.when(exchange.route()).thenReturn(routeInfo);

        Mockito.when(routeInfo.id()).thenReturn(data.consumeString(10));

        Mockito.when(exchange.getAttachment(Mockito.any())).thenReturn(null);
        Mockito.when(exchange.isShortCircuited()).thenReturn(false);

        return exchange;
    }

    private GatewayRequest buildPredicateRequest(final FuzzedDataProvider data)
    {
        final GatewayRequest request = Mockito.mock(GatewayRequest.class);

        // Include OPTIONS to trigger CORS preflight branches
        Mockito.when(request.method()).thenAnswer(inv ->
                data.pickValue(new String[]{"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD", "TRACE"})
        );

        // Fuzz the full URI
        Mockito.when(request.uri()).thenAnswer(inv -> data.consumeString(100));

        // Generate paths that help reach static content and regex resolution branches
        Mockito.when(request.path()).thenAnswer(inv -> {
            final int strategy = data.consumeInt(0, 3);
            return switch (strategy)
            {
                case 0 -> "/static/" + data.consumeString(10) + ".html";
                case 1 -> "/assets/" + data.consumeString(10) + ".css";
                case 2 -> "/api/v1/" + data.consumeString(15);
                default -> data.consumeString(50);
            };
        });

        // Bind complex fuzzed objects using the existing helper methods
        final GatewayHeaders headers = mockMultiAttributes(GatewayHeaders.class, data);
        Mockito.when(request.headers()).thenReturn(headers);

        final QueryParams queryParams = mockQueryParams(data);
        Mockito.when(request.queryParams()).thenReturn(queryParams);

        final Cookies cookies = mockCookies(data);
        Mockito.when(request.cookies()).thenReturn(cookies);

        // Provide safe InetAddresses. Returning a small pool of valid IPs allows stateful
        // filters (like RateLimiter) to group requests without triggering JVM DNS crashes.
        try
        {
            final String[] safeIps = {"127.0.0.1", "192.168.1.100", "10.0.0.5", "172.16.0.10"};
            final InetAddress safeAddress = InetAddress.getByName(data.pickValue(safeIps));
            Mockito.when(request.remoteAddress()).thenReturn(safeAddress);
        }
        catch (final UnknownHostException e)
        {
            throw new RuntimeException(e);
        }

        return request;
    }

    private UpstreamRequestGatewayExchange createSafeUpstreamExchange(final FuzzedDataProvider data)
    {
        final UpstreamRequestGatewayExchange exchange = Mockito.mock(UpstreamRequestGatewayExchange.class);
        final GatewayRequest clientRequest = buildFuzzedRequest(data);
        final GatewayRouteInfo routeInfo = Mockito.mock(GatewayRouteInfo.class);

        // Define a mutable upstream request mock so filters can freely manipulate headers
        final MutableGatewayRequest upstreamRequest = Mockito.mock(MutableGatewayRequest.class);
        final MutableGatewayHeaders upstreamHeaders = mockMultiAttributes(MutableGatewayHeaders.class, data);
        Mockito.when(upstreamRequest.headers()).thenReturn(upstreamHeaders);
        Mockito.when(upstreamRequest.queryParams()).thenReturn(Mockito.mock(MutableQueryParams.class));
        Mockito.when(upstreamRequest.cookies()).thenReturn(Mockito.mock(MutableCookies.class));
        Mockito.when(upstreamRequest.method()).thenAnswer(inv -> data.pickValue(new String[]{"GET", "POST", "PUT", "DELETE", "PATCH"}));
        Mockito.when(upstreamRequest.uri()).thenAnswer(inv -> data.consumeString(100));
        Mockito.when(upstreamRequest.path()).thenAnswer(inv -> data.consumeString(50));

        Mockito.when(exchange.clientRequest()).thenReturn(clientRequest);
        Mockito.when(exchange.upstreamRequest()).thenReturn(upstreamRequest);
        Mockito.when(exchange.route()).thenReturn(routeInfo);
        Mockito.when(routeInfo.id()).thenReturn(data.consumeString(10));
        Mockito.when(exchange.getAttachment(Mockito.any())).thenReturn(null);
        Mockito.when(exchange.isShortCircuited()).thenReturn(false);

        return exchange;
    }

    private ClientResponseGatewayExchange createSafeResponseExchange(final FuzzedDataProvider data)
    {
        final ClientResponseGatewayExchange exchange = Mockito.mock(ClientResponseGatewayExchange.class);

        final GatewayRequest clientReq = buildFuzzedRequest(data);
        final GatewayRequest upstreamReq = buildFuzzedRequest(data);

        final MutableGatewayResponse clientRes = Mockito.mock(MutableGatewayResponse.class);
        final GatewayResponse upstreamRes = Mockito.mock(GatewayResponse.class);

        final MutableGatewayHeaders mutableHeaders = mockMultiAttributes(MutableGatewayHeaders.class, data);
        final GatewayHeaders immutableHeaders = mockMultiAttributes(GatewayHeaders.class, data);

        Mockito.when(clientRes.headers()).thenReturn(mutableHeaders);
        Mockito.when(clientRes.status()).thenAnswer(inv -> data.consumeInt(200, 503));

        Mockito.when(upstreamRes.headers()).thenReturn(immutableHeaders);
        Mockito.when(upstreamRes.status()).thenAnswer(inv -> data.consumeInt(200, 503));

        Mockito.when(exchange.clientRequest()).thenReturn(clientReq);
        Mockito.when(exchange.upstreamRequest()).thenReturn(upstreamReq);
        Mockito.when(exchange.clientResponse()).thenReturn(clientRes);
        Mockito.when(exchange.upstreamResponse()).thenReturn(upstreamRes);
        Mockito.when(exchange.wasProxied()).thenAnswer(inv -> data.consumeBoolean());
        Mockito.when(exchange.getAttachment(Mockito.any())).thenReturn(null);

        return exchange;
    }

    private CompletedGatewayExchange createSafeCompletedResponseExchange(final FuzzedDataProvider data)
    {
        final CompletedGatewayExchange exchange = Mockito.mock(CompletedGatewayExchange.class);

        final GatewayRequest clientReq = buildFuzzedRequest(data);
        final GatewayRequest upstreamReq = buildFuzzedRequest(data);

        final MutableGatewayResponse clientRes = Mockito.mock(MutableGatewayResponse.class);
        final GatewayResponse upstreamRes = Mockito.mock(GatewayResponse.class);

        final MutableGatewayHeaders mutableHeaders = mockMultiAttributes(MutableGatewayHeaders.class, data);
        final GatewayHeaders immutableHeaders = mockMultiAttributes(GatewayHeaders.class, data);

        Mockito.when(clientRes.headers()).thenReturn(mutableHeaders);
        Mockito.when(clientRes.status()).thenAnswer(inv -> data.consumeInt(200, 503));

        Mockito.when(upstreamRes.headers()).thenReturn(immutableHeaders);
        Mockito.when(upstreamRes.status()).thenAnswer(inv -> data.consumeInt(200, 503));

        Mockito.when(exchange.clientRequest()).thenReturn(clientReq);
        Mockito.when(exchange.upstreamRequest()).thenReturn(upstreamReq);
        Mockito.when(exchange.clientResponse()).thenReturn(clientRes);
        Mockito.when(exchange.upstreamResponse()).thenReturn(upstreamRes);
        Mockito.when(exchange.wasProxied()).thenAnswer(inv -> data.consumeBoolean());
        Mockito.when(exchange.getAttachment(Mockito.any())).thenReturn(null);

        return exchange;
    }

    /**
     * Constructs a fully fuzzed GatewayRequest
     */
    private GatewayRequest buildFuzzedRequest(final FuzzedDataProvider data)
    {
        final GatewayRequest request = Mockito.mock(GatewayRequest.class);

        // Fuzz standard HTTP properties
        Mockito.when(request.method()).thenAnswer(inv -> data.pickValue(new String[]{"GET", "POST", "PUT", "DELETE", "PATCH"}));
        Mockito.when(request.uri()).thenAnswer(inv -> data.consumeString(100));
        Mockito.when(request.path()).thenAnswer(inv -> data.consumeString(50));

        // Bind complex fuzzed objects
        final GatewayHeaders headers = mockMultiAttributes(GatewayHeaders.class, data);
        Mockito.when(request.headers()).thenReturn(headers);

        final QueryParams queryParams = mockQueryParams(data);
        Mockito.when(request.queryParams()).thenReturn(queryParams);

        final com.ethlo.r7.api.Cookies cookies = mockCookies(data);
        Mockito.when(request.cookies()).thenReturn(cookies);

        // Provide a valid InetAddress to prevent low-level JVM networking crashes in tests
        try
        {
            final InetAddress safeAddress = InetAddress.getByName("127.0.0.1");
            Mockito.when(request.remoteAddress()).thenReturn(safeAddress);
        }
        catch (final UnknownHostException e)
        {
            throw new RuntimeException(e);
        }

        return request;
    }

    /**
     * Universal fuzzer for any interface extending MultiAttributes (GatewayHeaders, MutableGatewayHeaders)
     */
    private <T extends MultiAttributes> T mockMultiAttributes(final Class<T> clazz, final FuzzedDataProvider data)
    {
        final T mock = Mockito.mock(clazz);

        Mockito.when(mock.getFirst(Mockito.anyString())).thenAnswer(inv -> {
            final String requestedHeader = inv.getArgument(0);

            if (data.consumeBoolean())
            {
                return null;
            }

            return switch (requestedHeader)
            {
                case "Authorization" -> data.pickValue(new String[]{"Bearer token123", "Basic dXNlcjpwYXNz", ""});
                case "X-Forwarded-For" ->
                        data.pickValue(new String[]{"192.168.1.1", "10.0.0.5, 185.45.12.3", "invalid-ip"});
                case "X-Rate-Limit-Key" -> data.consumeString(15);
                default -> data.consumeString(30);
            };
        });

        Mockito.when(mock.contains(Mockito.anyString())).thenAnswer(inv -> data.consumeBoolean());
        return mock;
    }

    /**
     * Specific fuzzer for QueryParams
     */
    private QueryParams mockQueryParams(final FuzzedDataProvider data)
    {
        final QueryParams mock = Mockito.mock(QueryParams.class);

        Mockito.when(mock.getFirst(Mockito.anyString())).thenAnswer(inv ->
                data.consumeBoolean() ? null : data.consumeString(50)
        );

        Mockito.when(mock.getAll(Mockito.anyString())).thenAnswer(inv ->
                data.consumeBoolean() ? Collections.emptyList() : List.of(data.consumeString(50))
        );

        Mockito.when(mock.contains(Mockito.anyString())).thenAnswer(inv -> data.consumeBoolean());

        return mock;
    }

    /**
     * Specific fuzzer for Cookies
     */
    private com.ethlo.r7.api.Cookies mockCookies(final FuzzedDataProvider data)
    {
        final com.ethlo.r7.api.Cookies mock = Mockito.mock(com.ethlo.r7.api.Cookies.class);

        Mockito.when(mock.get(Mockito.anyString())).thenAnswer(inv -> {
            // 50% chance to simulate a missing cookie
            if (data.consumeBoolean())
            {
                return null;
            }

            if (inv.getMethod().getReturnType().equals(String.class))
            {
                return data.consumeString(50);
            }

            return Mockito.mock(inv.getMethod().getReturnType(), Mockito.RETURNS_DEEP_STUBS);
        });

        return mock;
    }
}