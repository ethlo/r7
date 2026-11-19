package com.ethlo.r7.doc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A config that takes exactly one of several sets of fields, such as a single {@code issuer} with
 * its {@code jwks_url}, or a list of {@code issuers}. The fields are named as they are in YAML. The
 * config schema then requires one set, and only one, as the config's own validation does.
 */
@Retention(value = RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiredOneOf
{
    /**
     * The alternatives; the first is the one an editor's insert snippet fills in.
     */
    Fields[] value();

    @Retention(value = RetentionPolicy.RUNTIME)
    @Target({})
    @interface Fields
    {
        String[] value();
    }
}
