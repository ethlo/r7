package com.ethlo.venturi.core.model;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayRoute;
import com.ethlo.venturi.constants.HttpHeaders;
import com.ethlo.venturi.core.GatewayExchangeDataReader;
import com.ethlo.venturi.core.ServerDirection;

public class WebExchangeDataProvider
{
    private static final Logger logger = LoggerFactory.getLogger(WebExchangeDataProvider.class);
    private final GatewayExchangeDataReader gatewayExchangeDataReader;
    private String requestId;
    private GatewayRoute route;
    private String method;
    private String path;
    private String uri;
    private int statusCode;
    private String protocol;
    private HeaderProvider requestHeaders;
    private HeaderProvider responseHeaders;
    private OffsetDateTime timestamp;
    private Duration duration;
    private InetSocketAddress remoteAddress;
    private RealmUser user;
    private Throwable exception;
    private Map<String, Object> metamap;

    public WebExchangeDataProvider(GatewayExchangeDataReader gatewayExchangeDataReader)
    {
        this.gatewayExchangeDataReader = Objects.requireNonNull(gatewayExchangeDataReader);
    }

    public WebExchangeDataProvider requestId(String requestId)
    {
        this.requestId = requestId;
        return this;
    }

    public WebExchangeDataProvider method(String method)
    {
        this.method = method;
        return this;
    }

    public WebExchangeDataProvider path(String path)
    {
        this.path = path;
        return this;
    }

    public WebExchangeDataProvider uri(String uri)
    {
        this.uri = uri;
        return this;
    }

    public WebExchangeDataProvider statusCode(int statusCode)
    {
        this.statusCode = statusCode;
        return this;
    }

    public WebExchangeDataProvider timestamp(OffsetDateTime timestamp)
    {
        this.timestamp = timestamp;
        return this;
    }

    public WebExchangeDataProvider duration(Duration duration)
    {
        this.duration = duration;
        return this;
    }

    public WebExchangeDataProvider remoteAddress(InetSocketAddress remoteAddress)
    {
        this.remoteAddress = remoteAddress;
        return this;
    }

    public String getRequestId()
    {
        return requestId;
    }

    public String getMethod()
    {
        return method;
    }

    public String getPath()
    {
        return path;
    }

    public String getUri()
    {
        return uri;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public GatewayHeaders getRequestHeaders()
    {
        if (requestHeaders == null)
        {
            requestHeaders = new HeaderProvider(gatewayExchangeDataReader, requestId, ServerDirection.REQUEST);
        }
        return requestHeaders.getHeaders();
    }

    public GatewayHeaders getResponseHeaders()
    {
        if (responseHeaders == null)
        {
            responseHeaders = new HeaderProvider(gatewayExchangeDataReader, requestId, ServerDirection.RESPONSE);
        }
        return responseHeaders.getHeaders();
    }

    public OffsetDateTime getTimestamp()
    {
        return timestamp;
    }

    public Duration getDuration()
    {
        return duration;
    }

    public InetSocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    public Map<String, Object> asMetaMap()
    {
        if (metamap == null)
        {
            Map<String, Object> m = new HashMap<>(20);

            // Standard Route metadata
            if (route != null)
            {
                m.put("route_id", route.id());
                m.put("route_uri", route.uri().toString());
            }

            // Identity
            getUser().ifPresent(u -> {
                m.put("realm_claim", u.realm());
                m.put("user_claim", u.principal());
            });

            GatewayHeaders reqH = getRequestHeaders();
            GatewayHeaders resH = getResponseHeaders();
            m.put("host", reqH.getFirst(HttpHeaders.HOST));
            m.put("user_agent", reqH.getFirst(HttpHeaders.USER_AGENT));
            m.put("request_content_type", reqH.getFirst(HttpHeaders.CONTENT_TYPE));
            m.put("response_content_type", resH.getFirst(HttpHeaders.CONTENT_TYPE));
            m.put("request_headers", reqH);
            m.put("response_headers", resH);

            // Timing & Status
            m.put("timestamp", timestamp);
            m.put("gateway_request_id", requestId);
            m.put("method", method);
            m.put("path", path);
            m.put("duration_ms", duration != null ? duration.toMillis() : 0);
            m.put("status", statusCode);
            m.put("is_error", statusCode >= 400);

            this.metamap = Collections.unmodifiableMap(m);
        }
        return metamap;
    }

    public GatewayRoute getRoute()
    {
        return route;
    }

    public WebExchangeDataProvider protocol(final String protocol)
    {
        this.protocol = protocol;
        return this;
    }

    public String getProtocol()
    {
        return protocol;
    }

    public WebExchangeDataProvider route(final GatewayRoute route)
    {
        this.route = route;
        return this;
    }

    public WebExchangeDataProvider user(RealmUser user)
    {
        this.user = user;
        return this;
    }

    public Optional<RealmUser> getUser()
    {
        return Optional.ofNullable(user);
    }

    public WebExchangeDataProvider exception(Throwable exc)
    {
        this.exception = exc;
        return this;
    }

    public Optional<Throwable> getException()
    {
        return Optional.ofNullable(exception);
    }
}
