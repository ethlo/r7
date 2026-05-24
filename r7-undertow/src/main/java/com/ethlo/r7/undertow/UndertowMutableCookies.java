package com.ethlo.r7.undertow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
        // Use Undertow's native API which handles RFC6265 validation internally
        this.exchange.setRequestCookie(new CookieImpl(cookie.name(), cookie.value()));
        this.syncCookieHeader();
    }

    @Override
    public void remove(final String name)
    {
        for (final Cookie cookie : this.exchange.requestCookies())
        {
            if (cookie.getName().equals(name))
            {
                exchange.setRequestCookie(new CookieImpl(name, null));
                break;
            }
        }
        this.syncCookieHeader();
    }

    @Override
    public com.ethlo.r7.api.Cookie get(final String name)
    {
        for (final Cookie cookie : this.exchange.requestCookies())
        {
            if (cookie.getName().equals(name))
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
            if (cookie.getName().equals(name))
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