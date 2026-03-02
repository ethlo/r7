package com.ethlo.venturi.validation;

public interface ValidatableConfig
{
    default void validate(ValidationResult result)
    {
    }
}