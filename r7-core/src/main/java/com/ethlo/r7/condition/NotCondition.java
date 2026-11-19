package com.ethlo.r7.condition;

import java.util.List;

/**
 * Inverts one condition, a leaf that is false for want of its leg included.
 */
public record NotCondition(Condition condition) implements Condition
{
    @Override
    public boolean test(final ConditionInput input)
    {
        return !this.condition.test(input);
    }

    @Override
    public List<Condition> children()
    {
        return List.of(this.condition);
    }

    @Override
    public String name()
    {
        return "not";
    }

    @Override
    public String summary()
    {
        return "not";
    }
}
