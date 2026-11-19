package com.ethlo.r7.doc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A list of strings whose items the loader also accepts written as integers within a range, such as
 * {@code 404} beside {@code "5xx"} in a status list, so the config schema accepts both forms.
 */
@Retention(value = RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface AcceptsInteger
{
    int minimum();

    int maximum();
}
