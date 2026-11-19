package com.ethlo.r7.api;

import java.util.Collection;

public interface Cookies
{
    Cookie get(final String name);

    boolean contains(final String name);

    Collection<Cookie> all();
}