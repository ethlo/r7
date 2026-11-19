package com.ethlo.r7.condition;

import java.util.List;

/**
 * True if any child is; stops at the first that is.
 */
public final class OrCondition implements Condition
{
    private final Condition[] children;

    public OrCondition(final List<Condition> children)
    {
        this.children = children.toArray(new Condition[0]);
    }

    @Override
    public boolean test(final ConditionInput input)
    {
        for (final Condition child : this.children)
        {
            if (child.test(input))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Condition> children()
    {
        return List.of(this.children);
    }

    @Override
    public String name()
    {
        return "or";
    }

    @Override
    public String summary()
    {
        return "or";
    }
}
