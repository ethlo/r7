package com.ethlo.r7.api;

@FunctionalInterface
public interface EntryConsumer
{
    void accept(CharSequence name, CharSequence value);
}

