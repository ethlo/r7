package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.validation.ValidationResult;

/**
 * A refused request's body is never read, and after a bad Transfer-Encoding its boundaries are
 * unknown: FULL is refused at load, anything below it is accepted. Rules are refused: the answer
 * to a refused request is fixed by its reason, and on_request waits for that reason to gate it.
 */
class UnroutedDefinitionTest
{
    private static ValidationResult validate(final JournalDefinition journal)
    {
        final ValidationResult result = new ValidationResult();
        new UnroutedDefinition(journal).validate(result);
        return result;
    }

    private static JournalDefinition levels(final JournalLevel request, final JournalLevel response)
    {
        return new JournalDefinition(
                request != null ? new JournalDirectionDefinition(request) : null,
                response != null ? new JournalDirectionDefinition(response) : null,
                null, null);
    }

    private static JournalRuleDefinition rule()
    {
        return new JournalRuleDefinition(null, null, new JournalDirectionDefinition(JournalLevel.HEADERS), null);
    }

    @Test
    void levelsBelowFullAreAccepted()
    {
        assertThat(validate(levels(JournalLevel.HEADERS, JournalLevel.METADATA)).getErrors()).isEmpty();
        assertThat(validate(levels(JournalLevel.METADATA, null)).getErrors()).isEmpty();
    }

    @Test
    void fullIsRefusedAsALevel()
    {
        assertThat(validate(levels(JournalLevel.FULL, null)).getErrors())
                .singleElement().asString().contains("FULL");
    }

    @Test
    void onResponseIsRefused()
    {
        assertThat(validate(new JournalDefinition(null, null, null, List.of(rule()))).getErrors())
                .anySatisfy(e -> assertThat(e).contains("journal.on_response").contains("fixed by its reason"));
    }

    @Test
    void onRequestIsRefusedForNow()
    {
        assertThat(validate(new JournalDefinition(null, null, List.of(rule()), null)).getErrors())
                .anySatisfy(e -> assertThat(e).contains("on_request").contains("not supported under unrouted yet"));
    }

    @Test
    void theJournalIsRequired()
    {
        assertThat(validate(null).getErrors()).singleElement().asString().contains("journal");
    }

    @Test
    void theRouteIdIsReserved()
    {
        final ValidationResult result = new ValidationResult();
        new RouteDefinition(UnroutedDefinition.ROUTE_ID, null, null, null, List.of(new FilterDefinition("AddResponseHeader", Map.of()))).validate(result);
        assertThat(result.getErrors()).anySatisfy(e -> assertThat(e).contains("reserved"));
    }
}
