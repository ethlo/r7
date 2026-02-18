package com.ethlo.venturi.config;

import com.ethlo.venturi.core.AuditLevel;
import com.ethlo.venturi.validation.Validatable;
import com.ethlo.venturi.validation.ValidationResult;

public class AuditDefinition implements Validatable
{
    public AuditLevel request = AuditLevel.NONE;
    public AuditLevel response = AuditLevel.NONE;

    @Override
    public void validate(ValidationResult result)
    {
        // TODO: Implement me
    }
}