package com.ethlo.r7.undertow;

import java.net.InetAddress;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableCookies;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.MutableQueryParams;
import com.ethlo.r7.api.TextValues;
import com.ethlo.r7.util.PathEncoder;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;

/**
 * A plain carrier for an in-flight request's Undertow-backed state. It does not decide
 * anything about the request (e.g. which address it is attributed to) — that is resolved
 * up front by {@link com.ethlo.r7.server.RemoteAddressResolver} and handed in, keeping this class free of config.
 */
public final class UndertowGatewayRequest implements MutableGatewayRequest
{
    private final HttpServerExchange exchange;
    private final MutableGatewayHeaders headers;
    private final InetAddress remoteAddress;
    private final IpSource remoteAddressSource;

    public UndertowGatewayRequest(final HttpServerExchange exchange, final InetAddress remoteAddress, final IpSource remoteAddressSource)
    {
        this.exchange = exchange;
        this.headers = new UndertowGatewayHeaders(exchange.getRequestHeaders());
        this.remoteAddress = remoteAddress;
        this.remoteAddressSource = remoteAddressSource;
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
    public String protocol()
    {
        return exchange.getProtocol().toString();
    }

    @Override
    public void path(final String path)
    {
        final String newPath = TextValues.requireNoControlCharacters("path", path);
        this.exchange.setRequestPath(newPath);     // The general path
        this.exchange.setRelativePath(newPath);    // Used by ProxyHandler to build upstream URL
        // The request URI is what ProxyHandler writes to the upstream verbatim, so it must be
        // the encoded form: the decoded path would turn a client's %3F into a query and its
        // %25 into a second round of decoding. It is also a bare path now, so the absolute-form
        // flag has to go - left set, ProxyHandler skips past the first "//" it finds in this
        // path looking for a host that is no longer there.
        this.exchange.setRequestURI(PathEncoder.encode(newPath), false);
    }

    @Override
    public void uri(final String uri)
    {
        // Storable as well as free of control characters. The URI is journaled as the upstream
        // request's start line, and StartLineBuilder copies each char's low byte — so a
        // character above ISO-8859-1 was not refused here but silently rewritten there (an em
        // dash became 0x14, a control character) in the audit record. path(String) cannot hit
        // this, because it percent-encodes; this setter takes an already-encoded URI, where
        // such a character has no business being in the first place.
        final String newUri = TextValues.requireNoControlCharacters("uri", TextValues.requireStorable("uri", uri));
        this.exchange.setRequestURI(newUri, isAbsoluteForm(newUri));
    }

    /**
     * Whether the target starts with {@code scheme "://"} (RFC 3986 §3.1: a letter, then letters,
     * digits, '+', '-' or '.'). Only then does it carry a host; a bare path - even one with
     * "://" further on, as in {@code /redirect?next=http://a/} - and the asterisk-form do not,
     * and flagging them would have ProxyHandler cut the path at the embedded authority.
     */
    static boolean isAbsoluteForm(final String uri)
    {
        final int len = uri.length();
        if (len == 0 || !isAsciiLetter(uri.charAt(0)))
        {
            return false;
        }
        for (int i = 1; i < len; i++)
        {
            final char c = uri.charAt(i);
            if (c == ':')
            {
                return uri.startsWith("//", i + 1);
            }
            if (!isAsciiLetter(c) && !(c >= '0' && c <= '9') && c != '+' && c != '-' && c != '.')
            {
                return false;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(final char c)
    {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
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
}
