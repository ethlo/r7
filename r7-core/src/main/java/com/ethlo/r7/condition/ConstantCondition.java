package com.ethlo.r7.condition;

/**
 * Always or never: a group without {@code match}, a fallback-only route's {@code match: []}, a
 * leaf whose configuration was refused.
 */
public record ConstantCondition(boolean value) implements Condition
{
    @Override
    public boolean test(final ConditionInput input)
    {
        return this.value;
    }

    @Override
    public String name()
    {
        return Boolean.toString(this.value);
    }

    @Override
    public String summary()
    {
        return Boolean.toString(this.value);
    }
}
