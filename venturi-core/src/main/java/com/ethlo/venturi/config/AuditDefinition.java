package com.ethlo.venturi.config;

import com.ethlo.venturi.core.AuditLevel;

public class AuditDefinition
{
    public AuditLevel request = AuditLevel.NONE;
    public AuditLevel response = AuditLevel.NONE;
}