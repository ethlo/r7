package com.ethlo.r7.predicates;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
        final ValidatorUtils validator = new ValidatorUtils(result)
                .required("name", this.name())
                .required("regexp", this.regexp());

        if (this.regexp() != null)
        {
            try
            {
                Pattern.compile(this.regexp());
            }
            catch (final PatternSyntaxException e)
            {
                validator.invalid("regexp", this.regexp(), "Invalid regex format: " + e.getDescription());
            }
        }
    }
}