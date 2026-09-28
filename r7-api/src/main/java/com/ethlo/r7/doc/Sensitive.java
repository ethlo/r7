package com.ethlo.r7.doc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a configuration value that must not be shown outside the configuration itself - on the
 * management dashboard, its JSON, or anywhere else config is rendered. Credentials and anything
 * a filter injects on the gateway's behalf belong here.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Sensitive
{
    /**
     * Name of a sibling component that holds a request header name. When that header is one the
     * journal already treats as safe to record, the value is shown; otherwise it is masked.
     * Empty (the default) masks the value always.
     */
    String unlessSafeHeaderIn() default "";
}
