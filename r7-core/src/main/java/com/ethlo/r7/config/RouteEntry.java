package com.ethlo.r7.config;

import java.util.List;

import com.ethlo.r7.validation.ValidatableConfig;

/**
 * An entry of a {@code routes} list: a {@link RouteDefinition} ({@code id:}) or, at the top level
 * only, a {@link GroupDefinition} ({@code group:}). The two share their keys, so {@code match},
 * {@code filters} and {@code upstream} mean the same wherever they are written; which one an
 * entry is, is decided by the key it names ({@link RoutesTreeCheck} refuses anything in between).
 */
public sealed interface RouteEntry extends ValidatableConfig permits RouteDefinition, GroupDefinition
{
    /**
     * @return the entry's {@code id} or {@code group}: one namespace for both
     */
    String entryId();

    ConditionDefinition match();

    List<FilterDefinition> filters();

    UpstreamConfig upstream();
}
