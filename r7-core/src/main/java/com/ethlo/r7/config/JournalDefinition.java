package com.ethlo.r7.config;

import com.ethlo.r7.validation.ValidatableConfig;

public record JournalDefinition(JournalDirectionDefinition request,
                                JournalDirectionDefinition response) implements ValidatableConfig
{
}
