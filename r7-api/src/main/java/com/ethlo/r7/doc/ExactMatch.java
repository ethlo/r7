package com.ethlo.r7.doc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a config value that is compared literally against request data. When such a value looks
 * like a regular expression, configuration loading warns and points at {@link #alternative()}:
 * {@code Path: /api/.*} is valid, matches only a client that sends those exact characters, and is
 * almost never what was meant.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface ExactMatch
{
    /**
     * What to use instead to match a pattern, as a sentence for the warning.
     */
    String alternative();

    /**
     * Also warn on wildcard ({@code *}, {@code **}) and template ({@code {id}}) syntax. Only for
     * paths and host names, where Spring Cloud Gateway uses that syntax and a literal {@code *}
     * or brace is never meant; in a header value (an Accept list, say) it can be.
     */
    boolean wildcards() default false;
}
