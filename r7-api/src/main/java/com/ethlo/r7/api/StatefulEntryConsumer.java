package com.ethlo.r7.api;

@FunctionalInterface
public interface StatefulEntryConsumer<S>
{
    void accept(S state, String name, String value);
}