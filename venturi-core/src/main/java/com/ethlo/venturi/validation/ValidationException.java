package com.ethlo.venturi.validation;

public class ValidationException extends RuntimeException
{
    private final String fieldName;

    public ValidationException(String fieldName, String message)
    {
        super(message);
        this.fieldName = fieldName;
    }

    public String getFieldName()
    {
        return fieldName;
    }

    @Override
    public String getMessage()
    {
        return String.format("Field '%s': %s", fieldName, super.getMessage());
    }
}