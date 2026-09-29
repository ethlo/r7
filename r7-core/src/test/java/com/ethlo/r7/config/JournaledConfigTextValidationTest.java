package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.validation.ValidationResult;

/**
 * Configuration the gateway copies into every exchange's journal attributes must be storable
 * as ISO-8859-1, or the attribute container refuses it in the completion listener and the
 * exchange never gets its end record.
 */
class JournaledConfigTextValidationTest
{
    private static List<String> errors(final RouteDefinition route)
    {
        final ValidationResult result = new ValidationResult();
        route.validate(result);
        return result.getErrors();
    }

    private static RouteDefinition route(final String id, final String targetUrl)
    {
        return new RouteDefinition(id,
                new UpstreamConfig(null, null, null, List.of(new TargetConfig(targetUrl)), null),
                null, null, List.of(new FilterDefinition("SetStatus", java.util.Map.of())));
    }

    @Test
    void aRouteIdOutsideLatin1IsRefusedAtStartup()
    {
        assertThat(errors(route("订单-api", "http://localhost:1")))
                .anySatisfy(error -> assertThat(error).contains("id").contains("U+8BA2").contains("ISO-8859-1"));
    }

    @Test
    void aTargetUrlOutsideLatin1IsRefusedAtStartup()
    {
        assertThat(errors(route("orders", "http://localhost:1/订单")))
                .anySatisfy(error -> assertThat(error).contains("url").contains("U+8BA2"));
    }

    @Test
    void latin1RouteIdsAndTargetsAreAccepted()
    {
        assertThat(errors(route("bestellungen-äöü", "http://localhost:1/caf%C3%A9"))).isEmpty();
    }
}
