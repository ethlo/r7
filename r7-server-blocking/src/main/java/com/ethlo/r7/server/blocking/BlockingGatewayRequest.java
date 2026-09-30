package com.ethlo.r7.server.blocking;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.ethlo.r7.api.Cookie;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableCookies;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.MutableGatewayRequest;
import com.ethlo.r7.api.MutableQueryParams;
import com.ethlo.r7.api.TextValues;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.PathEncoder;

/**
 * The live request on a thread-per-request server: what route predicates match, what filters
 * change, and what {@link HttpUpstream} forwards. It holds a mutable copy of the request headers,
 * taken once off the wire (server header APIs are read-only); everything the upstream receives is
 * read from here.
 * <p>
 * The mutators follow {@code UndertowGatewayRequest}: a new path re-encodes the forwarded URI,
 * a new URI is taken verbatim once checked to be storable and free of control characters, and a
 * changed query or cookie is written back into what is forwarded.
 */
public final class BlockingGatewayRequest implements MutableGatewayRequest
{
    private final String protocol;
    private final MutableGatewayHeaders headers;
    private final InetAddress remoteAddress;
    private final IpSource remoteAddressSource;
    private final Query query;
    private final HeaderCookies cookies;
    private String method;
    private String path;
    private String uri;

    BlockingGatewayRequest(final String protocol, final String method, final String decodedPath, final String rawPath, final String rawQuery,
                          final MutableGatewayHeaders headers, final InetAddress remoteAddress, final IpSource remoteAddressSource)
    {
        this.protocol = protocol;
        this.method = method;
        this.path = decodedPath;
        this.uri = rawPath;
        this.headers = headers;
        this.remoteAddress = remoteAddress;
        this.remoteAddressSource = remoteAddressSource;
        this.query = new Query(rawQuery);
        this.cookies = new HeaderCookies(headers);
    }

    /**
     * The request target to send upstream: the raw path and query as they stand after filters.
     */
    String target()
    {
        final String q = this.query.toQueryString();
        return q == null || q.isEmpty() ? this.uri : this.uri + "?" + q;
    }

    IpSource remoteAddressSource()
    {
        return this.remoteAddressSource;
    }

    @Override
    public String method()
    {
        return this.method;
    }

    @Override
    public String uri()
    {
        return this.uri;
    }

    @Override
    public String path()
    {
        return this.path;
    }

    @Override
    public MutableQueryParams queryParams()
    {
        return this.query;
    }

    @Override
    public MutableCookies cookies()
    {
        return this.cookies;
    }

    @Override
    public MutableGatewayHeaders headers()
    {
        return this.headers;
    }

    @Override
    public InetAddress remoteAddress()
    {
        return this.remoteAddress;
    }

    @Override
    public String protocol()
    {
        return this.protocol;
    }

    @Override
    public void path(final String newPath)
    {
        this.path = TextValues.requireNoControlCharacters("path", newPath);
        // The forwarded URI is the encoded form: the decoded path would turn a client's %3F into
        // a query and its %25 into a second round of decoding.
        this.uri = PathEncoder.encode(this.path);
    }

    @Override
    public void uri(final String newUri)
    {
        this.uri = TextValues.requireNoControlCharacters("uri", TextValues.requireStorable("uri", newUri));
    }

    @Override
    public void method(final String newMethod)
    {
        this.method = newMethod;
    }

    /**
     * Query parameters decoded once from the raw query. The raw query is forwarded untouched
     * until a filter changes a parameter; only then is it rebuilt, encoded the way the Undertow
     * implementation encodes it.
     */
    static final class Query implements MutableQueryParams
    {
        private final List<String[]> pairs = new ArrayList<>();
        private String raw;
        private boolean changed;

        Query(final String rawQuery)
        {
            this.raw = rawQuery;
            if (rawQuery == null || rawQuery.isEmpty())
            {
                return;
            }
            for (final String part : rawQuery.split("&"))
            {
                if (part.isEmpty())
                {
                    continue;
                }
                final int eq = part.indexOf('=');
                final String name = eq < 0 ? part : part.substring(0, eq);
                final String value = eq < 0 ? "" : part.substring(eq + 1);
                pairs.add(new String[]{decode(name), decode(value)});
            }
        }

        private static String decode(final String s)
        {
            try
            {
                return URLDecoder.decode(s, StandardCharsets.UTF_8);
            }
            catch (final IllegalArgumentException e)
            {
                // Malformed escapes stay as sent; the raw query is what is forwarded anyway.
                return s;
            }
        }

        @Override
        public String getFirst(final String name)
        {
            for (final String[] pair : pairs)
            {
                if (pair[0].equals(name))
                {
                    return pair[1];
                }
            }
            return null;
        }

        @Override
        public Iterable<String> getAll(final String name)
        {
            final List<String> out = new ArrayList<>();
            for (final String[] pair : pairs)
            {
                if (pair[0].equals(name))
                {
                    out.add(pair[1]);
                }
            }
            return out;
        }

        @Override
        public void set(final String name, final String value)
        {
            remove(name);
            add(name, value);
        }

        @Override
        public void add(final String name, final String value)
        {
            pairs.add(new String[]{name, value});
            changed = true;
        }

        @Override
        public void remove(final String name)
        {
            changed |= pairs.removeIf(pair -> pair[0].equals(name));
        }

        @Override
        public String toQueryString()
        {
            if (!changed)
            {
                return raw;
            }
            final StringBuilder sb = new StringBuilder();
            for (final String[] pair : pairs)
            {
                if (!sb.isEmpty())
                {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(pair[0], StandardCharsets.UTF_8));
                if (pair[1] != null && !pair[1].isEmpty())
                {
                    sb.append('=').append(URLEncoder.encode(pair[1], StandardCharsets.UTF_8));
                }
            }
            raw = sb.toString();
            changed = false;
            return raw;
        }
    }

    /**
     * Cookies read from, and written back to, the live Cookie header.
     */
    static final class HeaderCookies implements MutableCookies
    {
        private static final String COOKIE = "Cookie";
        private final MutableGatewayHeaders headers;

        HeaderCookies(final MutableGatewayHeaders headers)
        {
            this.headers = headers;
        }

        private List<Cookie> parse()
        {
            final List<Cookie> out = new ArrayList<>();
            for (final String line : headers.getAll(COOKIE))
            {
                for (final String pair : line.split(";"))
                {
                    final int eq = pair.indexOf('=');
                    if (eq > 0)
                    {
                        final String name = pair.substring(0, eq).trim();
                        final String value = pair.substring(eq + 1).trim();
                        out.add(new SimpleCookie(name, value));
                    }
                }
            }
            return out;
        }

        private void write(final List<Cookie> all)
        {
            headers.remove(COOKIE);
            if (!all.isEmpty())
            {
                final StringBuilder sb = new StringBuilder();
                for (final Cookie cookie : all)
                {
                    if (!sb.isEmpty())
                    {
                        sb.append("; ");
                    }
                    sb.append(cookie.name()).append('=').append(cookie.value());
                }
                headers.set(COOKIE, sb.toString());
            }
        }

        @Override
        public Cookie get(final String name)
        {
            for (final Cookie cookie : parse())
            {
                if (cookie.name().equals(name))
                {
                    return cookie;
                }
            }
            return null;
        }

        @Override
        public boolean contains(final String name)
        {
            return get(name) != null;
        }

        @Override
        public Collection<Cookie> all()
        {
            return parse();
        }

        @Override
        public void set(final Cookie cookie)
        {
            // Names are compared trimmed, so a client's "tenant =x" cannot survive next to the
            // "tenant" a filter sets and win at a lenient upstream parser.
            final List<Cookie> all = parse();
            all.removeIf(c -> c.name().equals(cookie.name()));
            all.add(new SimpleCookie(cookie.name(), cookie.value()));
            write(all);
        }

        @Override
        public void remove(final String name)
        {
            final List<Cookie> all = parse();
            if (all.removeIf(c -> c.name().equals(name)))
            {
                write(all);
            }
        }
    }

    record SimpleCookie(String name, String value) implements Cookie
    {
    }

    static MutableGatewayHeaders newHeaders()
    {
        return new MutableFastGatewayHeaders();
    }
}
