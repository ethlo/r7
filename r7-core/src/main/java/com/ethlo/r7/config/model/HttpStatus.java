package com.ethlo.r7.config.model;

import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record HttpStatus(Integer code) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        if (this.code == null || this.code < 100 || this.code > 599)
        {
            result.addError("status", "Must be a valid HTTP status code (100-599), received " + this.code);
        }
    }
}