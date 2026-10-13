package com.ethlo.r7.condition;

/**
 * A share of exchanges: true when the exchange's one draw is below {@code rate}. The draw comes
 * from a random source, never from a value a client chooses, and is made once per exchange, so
 * every {@code Sample} of an exchange agrees and {@code rate: 0.01} selects a subset of
 * {@code rate: 0.1}.
 */
public record SampleCondition(double rate) implements Condition
{
    @Override
    public boolean test(final ConditionInput input)
    {
        return input.sampleDraw() < this.rate;
    }

    @Override
    public String name()
    {
        return "Sample";
    }

    @Override
    public String summary()
    {
        return "Sample: " + this.rate;
    }
}
