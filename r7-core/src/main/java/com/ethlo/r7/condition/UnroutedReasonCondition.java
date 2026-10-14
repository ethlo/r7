package com.ethlo.r7.condition;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * True when the request was refused before routing for one of {@code include}'s reasons.
 */
public record UnroutedReasonCondition(Set<UnroutedReason> include) implements Condition
{
    public UnroutedReasonCondition
    {
        include = EnumSet.copyOf(include);
    }

    @Override
    public boolean test(final ConditionInput input)
    {
        final UnroutedReason reason = input.unroutedReason();
        return reason != null && this.include.contains(reason);
    }

    @Override
    public String name()
    {
        return "UnroutedReason";
    }

    @Override
    public String summary()
    {
        return "UnroutedReason: " + this.include.stream().map(UnroutedReason::configName).collect(Collectors.joining(", "));
    }
}
