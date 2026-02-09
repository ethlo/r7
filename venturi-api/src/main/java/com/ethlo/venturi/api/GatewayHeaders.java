package com.ethlo.venturi.api;

public interface Headers {

    /**
     * Returns the first header value or null
     */
    String getFirst(String name);

    /**
     * All values for a header (lazy view, not copied)
     */
    Iterable<String> getAll(String name);

    /**
     * Iterate over all header names (lazy)
     */
    Iterable<String> names();

    /**
     * True if header exists
     */
    default boolean contains(String name) {
        return getFirst(name) != null;
    }
}