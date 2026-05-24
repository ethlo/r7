package com.ethlo.r7.predicates;

import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public interface GenericMatchConfig extends ValidatableConfig
{
    String name();

    String regexp();

    @Override
    default void validate(final ValidationResult result)
    {
        new ValidatorUtils(result)
                .required("name", this.name())
                .requiredRegexp("regexp", this.regexp());
    }
}