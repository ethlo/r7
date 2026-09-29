package com.ethlo.r7.undertow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.api.MutableCookies;
import com.ethlo.r7.core.SimpleCookie;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.Headers;

public final class UndertowMutableCookies implements MutableCookies
{
    private final HttpServerExchange exchange;

    public UndertowMutableCookies(final HttpServerExchange exchange)
    {
        this.exchange = exchange;
    }

    @Override
    public void set(final com.ethlo.r7.api.Cookie cookie)
    {
        // A client can send a cookie-pair with OWS around the name (RFC 6265 doesn't forbid it,
        // and Undertow's parser keeps it verbatim: "tenant =666" is parsed as name "tenant "
        // with a trailing space). Left alone, that cookie would sit unchanged in the outgoing
        // header next to the clean "tenant" cookie set below - and a lenient upstream parser
        // (Node, Go) trims the name and reads it as "tenant" too, so the value the filter meant
        // to replace still wins there. Stray entries are removed first so only the clean cookie
        // this call sets survives under that name.
        this.removeMatching(cookie.name());
        this.exchange.setRequestCookie(new CookieImpl(cookie.name(), cookie.value()));
        this.syncCookieHeader();
    }

    @Override
    public void remove(final String name)
    {
        this.removeMatching(name);
        this.syncCookieHeader();
    }

    /**
     * Removes every cookie whose name equals {@code name} once surrounding whitespace is
     * stripped, using {@link HttpServerExchange#getRequestCookies()} (a {@link Map} view backed
     * by the same storage {@link HttpServerExchange#requestCookies()} reads) so the entry is
     * actually deleted. Setting the cookie's value to {@code null} - the previous approach - does
     * not remove it: {@code syncCookieHeader()} then string-concatenates that null while
     * rebuilding the Cookie header, literally sending {@code name=null} to the upstream.
     */
    private void removeMatching(final String name)
    {
        final Map<String, Cookie> cookies = this.exchange.getRequestCookies();
        for (final String key : new ArrayList<>(cookies.keySet()))
        {
            if (key.trim().equals(name))
            {
                cookies.remove(key);
            }
        }
    }

    @Override
    public com.ethlo.r7.api.Cookie get(final String name)
    {
        for (final Cookie cookie : this.exchange.requestCookies())
        {
            if (cookie.getName().trim().equals(name))
            {
                return new SimpleCookie(cookie.getName(), cookie.getValue());
            }
        }
        return null;
    }

    @Override
    public boolean contains(final String name)
    {
        for (final Cookie cookie : this.exchange.requestCookies())
        {
            if (cookie.getName().trim().equals(name))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public Collection<com.ethlo.r7.api.Cookie> all()
    {
        final List<com.ethlo.r7.api.Cookie> cookies = new ArrayList<>();

        for (final io.undertow.server.handlers.Cookie undertowCookie : this.exchange.requestCookies())
        {
            cookies.add(new SimpleCookie(undertowCookie.getName(), undertowCookie.getValue()));
        }

        return cookies;
    }

    /**
     * Rebuilds the raw HTTP header required by the ProxyClient.
     */
    private void syncCookieHeader()
    {
        final StringBuilder sb = new StringBuilder();

        for (final Cookie cookie : this.exchange.requestCookies())
        {
            if (!sb.isEmpty())
            {
                sb.append("; ");
            }
            sb.append(cookie.getName()).append('=').append(cookie.getValue());
        }

        if (sb.isEmpty())
        {
            this.exchange.getRequestHeaders().remove(Headers.COOKIE);
        }
        else
        {
            this.exchange.getRequestHeaders().put(Headers.COOKIE, sb.toString());
        }
    }
}