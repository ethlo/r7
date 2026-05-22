package com.ethlo.r7.api;

public interface MutableCookies extends Cookies
{
    /**
     * Sets or overwrites a cookie.
     */
    void set(final Cookie cookie);

    /**
     * Removes a cookie by name.
     */
    void remove(final String name);
}