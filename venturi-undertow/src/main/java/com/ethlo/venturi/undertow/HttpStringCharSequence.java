package com.ethlo.venturi.undertow;

import io.undertow.util.HttpString;

/**
 * A thin flyweight to allow HttpString to fulfill the CharSequence contract
 * without creating a new java.lang.String object.
 */
public final class HttpStringCharSequence implements CharSequence {
    private final HttpString httpString;

    public HttpStringCharSequence(final HttpString httpString) {
        this.httpString = httpString;
    }

    @Override
    public int length() {
        return httpString.length();
    }

    @Override
    public char charAt(final int index) {
        // HttpString uses byte-based Latin-1; this is O(1)
        return (char) (httpString.byteAt(index) & 0xFF);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
        return this.toString().substring(start, end);
    }

    @Override
    public String toString() {
        return httpString.toString();
    }
}