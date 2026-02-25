package com.ethlo.venturi.api;

@FunctionalInterface
public interface StatefulEntryConsumer<S>
{
    void accept(S state, CharSequence name, CharSequence value);
}