package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.validation.ValidationResult;

/**
 * A refused request's body is never read, and after a bad Transfer-Encoding its boundaries are
 * unknown: FULL is refused at load, anything below it is accepted.
 */
class UnroutedDefinitionTest
{
    private static ValidationResult validate(final JournalDirectionDefinition request, final JournalDirectionDefinition response)
    {
        final ValidationResult result = new ValidationResult();
        new UnroutedDefinition(new JournalDefinition(request, response)).validate(result);
        return result;
    }

    @Test
    void levelsBelowFullAreAccepted()
    {
        assertThat(validate(new JournalDirectionDefinition(JournalLevel.HEADERS, Map.of("4xx", JournalLevel.METADATA)),
                new JournalDirectionDefinition(JournalLevel.METADATA, null)).getErrors()).isEmpty();
        assertThat(validate(new JournalDirectionDefinition(JournalLevel.METADATA, null), null).getErrors()).isEmpty();
    }

    @Test
    void fullIsRefusedAsALevel()
    {
        assertThat(validate(new JournalDirectionDefinition(JournalLevel.FULL, null), null).getErrors())
                .singleElement().asString().contains("FULL");
    }

    @Test
    void fullIsRefusedAsAnOverride()
    {
        assertThat(validate(null, new JournalDirectionDefinition(JournalLevel.NONE, Map.of("400", JournalLevel.FULL))).getErrors())
                .singleElement().asString().contains("FULL");
    }

    @Test
    void theJournalIsRequired()
    {
        final ValidationResult result = new ValidationResult();
        new UnroutedDefinition(null).validate(result);
        assertThat(result.getErrors()).singleElement().asString().contains("journal");
    }
}
