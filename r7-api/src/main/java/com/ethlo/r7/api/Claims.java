package com.ethlo.r7.api;

import java.util.List;
import java.util.Optional;

/**
 * The verified claims of an {@link Identity}, such as a JWT's payload. Only strings and arrays of
 * strings are read: a claim of any other type reads as absent, so a filter never has to guess how
 * a number or an object would have been turned into text.
 */
public interface Claims
{
    Claims NONE = new Claims()
    {
        @Override
        public Optional<String> string(final String name)
        {
            return Optional.empty();
        }

        @Override
        public List<String> strings(final String name)
        {
            return List.of();
        }

        @Override
        public boolean has(final String name)
        {
            return false;
        }
    };

    /**
     * @return the claim when it is a string, otherwise empty
     */
    Optional<String> string(String name);

    /**
     * @return the claim when it is an array of strings, such as roles or groups; a single string
     * reads as a list of one; otherwise empty
     */
    List<String> strings(String name);

    /**
     * @return whether the claim is present, whatever its type
     */
    boolean has(String name);
}
