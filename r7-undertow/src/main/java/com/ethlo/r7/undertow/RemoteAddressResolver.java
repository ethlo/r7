package com.ethlo.r7.undertow;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.util.CidrRange;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

/**
 * Decides which address a request is attributed to. Holds the trusted-proxy configuration and
 * is the single caller-facing entry point for that decision, so {@link UndertowGatewayRequest}
 * stays a plain carrier of request data rather than a config consumer.
 */
public final class RemoteAddressResolver
{
    private final List<CidrRange> trustedProxies;

    public RemoteAddressResolver(final List<CidrRange> trustedProxies)
    {
        this.trustedProxies = List.copyOf(trustedProxies);
    }

    public RemoteInfo resolve(final HttpServerExchange exchange)
    {
        final InetAddress socketAddress = socketAddress(exchange);

        // X-Forwarded-For/X-Real-IP are client-controlled: anything can put whatever it
        // likes in them. Only a request whose immediate peer is a configured, trusted proxy
        // gets to have those headers believed; otherwise a direct client could spoof its own
        // address and bypass IP-based access control (RemoteAddr predicate) or rotate rate
        // limit buckets by lying about who it is.
        if (!isTrustedProxy(socketAddress))
        {
            return socketAddress != null
                    ? new RemoteInfo(socketAddress, IpSource.SOCKET)
                    : new RemoteInfo(null, IpSource.UNKNOWN);
        }

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
        return socketAddress != null
                ? new RemoteInfo(socketAddress, IpSource.SOCKET)
                : new RemoteInfo(null, IpSource.UNKNOWN);
    }

    private boolean isTrustedProxy(final InetAddress socketAddress)
    {
        if (socketAddress == null || this.trustedProxies.isEmpty())
        {
            return false;
        }
        for (final CidrRange range : this.trustedProxies)
        {
            if (range.contains(socketAddress))
            {
                return true;
            }
        }
        return false;
    }

    private static InetAddress socketAddress(final HttpServerExchange exchange)
    {
        final InetSocketAddress sourceAddress = exchange.getSourceAddress();
        return sourceAddress != null ? sourceAddress.getAddress() : null;
    }

    public record RemoteInfo(InetAddress address, IpSource source)
    {
    }
}
