package com.ethlo.r7.undertow;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.util.CidrRange;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

/**
 * Decides which address a request is attributed to. Holds the trusted-proxy configuration and
 * is the single caller-facing entry point for that decision, so {@link UndertowGatewayRequest}
 * stays a plain carrier of request data rather than a config consumer.
 */
public final class RemoteAddressResolver
{
    private static final HttpString X_REAL_IP = HttpString.tryFromString("X-Real-IP");

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
            return toRemoteInfo(socketAddress, IpSource.SOCKET);
        }

        // A header can legally arrive as several field-lines; per RFC 9110 §5.3 that is
        // equivalent to one comma-joined list in the order received, so all lines must be
        // combined before walking the chain. Using only the first line would let a client
        // supply its own forged line ahead of the one line a trusted proxy appends.
        final HeaderValues xffLines = exchange.getRequestHeaders().get(Headers.X_FORWARDED_FOR);
        if (xffLines != null && !xffLines.isEmpty())
        {
            final String joined = String.join(",", xffLines);
            if (!joined.isBlank())
            {
                final RemoteInfo fromChain = resolveFromForwardedFor(joined);
                if (fromChain != null)
                {
                    return fromChain;
                }
                // The chain is present but unparsable: fail closed to the socket address
                // rather than consult X-Real-IP. Otherwise a client could pair a malformed
                // XFF with a forged X-Real-IP and have the latter accepted even though a
                // trusted proxy only ever touched XFF for this request.
                return toRemoteInfo(socketAddress, IpSource.SOCKET);
            }
        }

        // X-Real-IP is single-valued by contract; more than one field-line is ambiguous (a
        // client-supplied line could be preserved alongside one a trusted proxy adds) and is
        // rejected outright rather than guessing which line to believe.
        final HeaderValues xRealIpLines = exchange.getRequestHeaders().get(X_REAL_IP);
        if (xRealIpLines != null && xRealIpLines.size() == 1)
        {
            final String xRealIp = xRealIpLines.getFirst();
            if (xRealIp != null && !xRealIp.isBlank())
            {
                final InetAddress candidate = parseLiteral(xRealIp.trim());
                if (candidate != null)
                {
                    return new RemoteInfo(candidate, IpSource.X_REAL_IP);
                }
            }
        }

        return toRemoteInfo(socketAddress, IpSource.SOCKET);
    }

    /**
     * Walks the X-Forwarded-For chain from right to left. Each proxy appends the address of
     * whoever connected to it, so the rightmost entry is what our already-trusted immediate
     * peer reports for its own peer. As long as that reported address is itself a trusted
     * proxy, keep walking left; the first entry that is not a trusted proxy is the address
     * to attribute the request to.
     * <p>
     * Taking the leftmost entry instead (the original, naive implementation) is spoofable: a
     * direct client can prepend an arbitrary value before a trusted proxy appends the real
     * one, producing e.g. {@code "forged, actual-client"}, and the leftmost entry would then
     * be the attacker's own claim rather than anything a trusted party vouched for.
     *
     * @return the resolved address, or {@code null} if any entry in the chain is not a
     * literal IPv4/IPv6 address — a malformed chain must not be guessed at, since skipping
     * past it could let an attacker hide their real value behind it
     */
    private RemoteInfo resolveFromForwardedFor(final String xff)
    {
        final String[] parts = xff.split(",", -1);
        final InetAddress[] parsed = new InetAddress[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            parsed[i] = parseLiteral(parts[i].trim());
            if (parsed[i] == null)
            {
                return null;
            }
        }

        for (int i = parts.length - 1; i >= 0; i--)
        {
            if (!isTrustedProxy(parsed[i]))
            {
                return new RemoteInfo(parsed[i], IpSource.X_FORWARDED_FOR);
            }
        }

        // Every hop in the chain is itself a trusted proxy: there is nothing further to peel
        // back, so the original (leftmost) claim is the closest thing to a client address.
        return new RemoteInfo(parsed[0], IpSource.X_FORWARDED_FOR);
    }

    private boolean isTrustedProxy(final InetAddress address)
    {
        if (address == null || this.trustedProxies.isEmpty())
        {
            return false;
        }
        for (final CidrRange range : this.trustedProxies)
        {
            if (range.contains(address))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a literal IPv4/IPv6 address, never resolving a hostname. XFF/X-Real-IP values
     * are client-controlled; parsing them with {@link InetAddress#getByName} would perform a
     * blocking DNS lookup on the request thread for any value that is not already a literal
     * address, letting an attacker stall the thread with a hostname.
     */
    private static InetAddress parseLiteral(final String value)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }
        try
        {
            return InetAddress.ofLiteral(value);
        }
        catch (final IllegalArgumentException e)
        {
            return null;
        }
    }

    private static RemoteInfo toRemoteInfo(final InetAddress address, final IpSource source)
    {
        return address != null ? new RemoteInfo(address, source) : new RemoteInfo(null, IpSource.UNKNOWN);
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
