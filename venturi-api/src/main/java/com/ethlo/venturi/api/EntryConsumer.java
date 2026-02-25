package com.ethlo.venturi.api;

@FunctionalInterface
public interface EntryConsumer
{
    void accept(CharSequence name, CharSequence value);
}

