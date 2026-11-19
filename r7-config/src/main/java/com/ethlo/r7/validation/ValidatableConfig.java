package com.ethlo.r7.validation;

public interface ValidatableConfig
{
    default void validate(ValidationResult result)
    {
    }
}