package com.ethlo.r7.undertow;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableCookies;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.MutableQueryParams;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

public final class UndertowGatewayRequest implements MutableGatewayRequest
{
    private final HttpServerExchange exchange;
    private final MutableGatewayHeaders headers;
    private final InetAddress remoteAddress;
    private final IpSource remoteAddressSource;

    public UndertowGatewayRequest(final HttpServerExchange exchange)
    {
        this.exchange = exchange;
        this.headers = new UndertowGatewayHeaders(exchange.getRequestHeaders());

        final RemoteInfo info = resolveRemoteAddress(exchange);
        this.remoteAddress = info.address();
        this.remoteAddressSource = info.source();
    }

    private static RemoteInfo resolveRemoteAddress(final HttpServerExchange exchange)
    {
        // 1. Check X-Forwarded-For
        final String xff = exchange.getRequestHeaders().getFirst(Headers.X_FORWARDED_FOR);
        if (xff != null && !xff.isBlank())
        {
            final int commaIndex = xff.indexOf(',');
            final String rawIp = commaIndex > 0 ? xff.substring(0, commaIndex).trim() : xff.trim();
            try
            {
                return new RemoteInfo(InetAddress.getByName(rawIp), IpSource.X_FORWARDED_FOR);
            }
            catch (final UnknownHostException e)
            {
                // Fallthrough on malformed header
            }
        }

        // 2. Check X-Real-IP
        final String xRealIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank())
        {
            try
            {
                return new RemoteInfo(InetAddress.getByName(xRealIp.trim()), IpSource.X_REAL_IP);
            }
            catch (final UnknownHostException e)
            {
                // Fallthrough on malformed header
            }
        }

        // 3. Fallback to Raw Socket Address
        final InetSocketAddress sourceAddress = exchange.getSourceAddress();
        if (sourceAddress != null)
        {
            final InetAddress address = sourceAddress.getAddress();
            if (address != null)
            {
                return new RemoteInfo(address, IpSource.SOCKET);
            }
        }

        return new RemoteInfo(null, IpSource.UNKNOWN);
    }

    @Override
    public String method()
    {
        final HttpString hs = exchange.getRequestMethod();
        return hs.toString();
    }

    @Override
    public String uri()
    {
        return exchange.getRequestURI();
    }

    @Override
    public String path()
    {
        return exchange.getRequestPath();
    }

    @Override
    public MutableQueryParams queryParams()
    {
        return new UndertowMutableQueryParams(exchange);
    }

    @Override
    public MutableCookies cookies()
    {
        return new UndertowMutableCookies(exchange);
    }

    @Override
    public MutableGatewayHeaders headers()
    {
        return headers;
    }

    @Override
    public InetAddress remoteAddress()
    {
        return remoteAddress;
    }

    @Override
    public void path(final String path)
    {
        final String newPath = path.toString();
        this.exchange.setRequestPath(newPath);     // The general path
        this.exchange.setRelativePath(newPath);    // Used by ProxyHandler to build upstream URL
        this.exchange.setRequestURI(newPath);      // The full URI used for logging/matching
    }

    @Override
    public void uri(final String uri)
    {
        this.exchange.setRequestURI(uri.toString());
    }

    @Override
    public void method(final String method)
    {
        this.exchange.setRequestMethod(HttpString.tryFromString(method.toString()));
    }

    public IpSource getRemoteAddressSource()
    {
        return remoteAddressSource;
    }

    private record RemoteInfo(InetAddress address, IpSource source)
    {
    }
}