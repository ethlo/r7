package com.ethlo.r7.condition;

import java.util.List;

import com.ethlo.r7.api.ShortInfo;

/**
 * A condition as written in the configuration: a route's {@code match}, a journal rule's. The
 * tree is r7's own: {@code and}, {@code or}, {@code not} and {@code Sample} are its nodes, and
 * each predicate is a leaf that fetches its leg's request or response from the exchange and
 * hands it in. Built at load; evaluating allocates nothing.
 */
public sealed interface Condition extends ShortInfo
        permits AndCondition, OrCondition, NotCondition, SampleCondition, RequestCondition, ResponseCondition, ConstantCondition
{
    Condition TRUE = new ConstantCondition(true);
    Condition FALSE = new ConstantCondition(false);

    boolean test(ConditionInput input);

    /**
     * @return the nodes below this one, for display
     */
    default List<Condition> children()
    {
        return List.of();
    }
}
