package com.ethlo.r7.condition;

import java.util.List;

/**
 * True if every child is; stops at the first that is not.
 */
public final class AndCondition implements Condition
{
    private final Condition[] children;

    public AndCondition(final List<Condition> children)
    {
        this.children = children.toArray(new Condition[0]);
    }

    @Override
    public boolean test(final ConditionInput input)
    {
        for (final Condition child : this.children)
        {
            if (!child.test(input))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<Condition> children()
    {
        return List.of(this.children);
    }

    @Override
    public String name()
    {
        return "and";
    }

    @Override
    public String summary()
    {
        return "and";
    }
}
