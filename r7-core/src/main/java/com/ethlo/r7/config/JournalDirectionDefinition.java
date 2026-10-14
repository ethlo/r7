package com.ethlo.r7.config;

import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * One direction of a journal block or a rule. Every key is optional and inherited on its own: a
 * key left out takes no part at that level, so the raw value is kept and {@code null} means "not
 * named here", never a default.
 */
public record JournalDirectionDefinition(
        @Description("NONE, METADATA, HEADERS or FULL. Left out, the level is inherited from the group or the root, else NONE.")
        @Nullable JournalLevel level
) implements ValidatableConfig
{
    public boolean namesAnyKey()
    {
        return this.level != null;
    }

    @Override
    public void validate(final ValidationResult result)
    {
        if (!this.namesAnyKey())
        {
            result.addError(null, "names no key; set level, or leave the direction out");
        }
    }
}
