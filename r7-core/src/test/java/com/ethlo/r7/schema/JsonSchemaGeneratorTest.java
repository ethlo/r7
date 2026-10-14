package com.ethlo.r7.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.ethlo.r7.config.model.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * What the editor is told the configuration accepts, checked where it has been wrong: a schema
 * that rejects a form the loader takes, or offers one the loader refuses.
 */
class JsonSchemaGeneratorTest
{
    private static final JsonNode SCHEMA = schema();

    private static JsonNode schema()
    {
        final SimpleModule module = new SimpleModule();
        module.addSerializer(HttpStatus.class, new JsonSchemaGenerator.HttpStatusSerializer());
        final JsonMapper mapper = JsonMapper.builder().addModule(module).build();
        return mapper.valueToTree(new JsonSchemaGenerator().generateGatewaySchema());
    }

    @Test
    void aStatusListAcceptsCodesWrittenAsNumbersBesideClasses()
    {
        final JsonNode items = SCHEMA.at("/$defs/responsePredicate/anyOf").findValue("Status").findValue("include").get("items");

        final List<String> types = new ArrayList<>();
        items.get("anyOf").forEach(alternative -> types.add(alternative.get("type").asString()));
        assertThat(types).containsExactlyInAnyOrder("integer", "string");
        assertThat(items.get("anyOf").get(0).get("minimum").asInt()).isEqualTo(100);
        assertThat(items.get("anyOf").get(0).get("maximum").asInt()).isEqualTo(599);
    }

    @Test
    void anOnResponseRuleCannotSetTheRequestToFullButItsResponseCan()
    {
        final JsonNode rule = SCHEMA.at("/properties/journal/properties/on_response/items/properties");

        assertThat(levels(rule.at("/request/properties/level"))).containsExactly("NONE", "HEADERS", "METADATA");
        assertThat(levels(rule.at("/response/properties/level"))).contains("FULL");
    }

    @Test
    void anOnRequestRuleCanSetEitherToFull()
    {
        final JsonNode rule = SCHEMA.at("/properties/journal/properties/on_request/items/properties");

        assertThat(levels(rule.at("/request/properties/level"))).contains("FULL");
        assertThat(levels(rule.at("/response/properties/level"))).contains("FULL");
    }

    @Test
    void responsePredicatesAreOfferedOnlyWhereTheyCanBeUsed()
    {
        final JsonNode request = SCHEMA.at("/$defs/predicate");
        final JsonNode response = SCHEMA.at("/$defs/responsePredicate");

        assertThat(request.findValue("Status")).as("Status in a request predicate").isNull();
        assertThat(request.findValue("HasResponseHeader")).isNull();
        assertThat(request.findValue("PathPrefix")).isNotNull();
        assertThat(response.findValue("Status")).isNotNull();
        assertThat(response.findValue("PathPrefix")).as("a response rule can also test the request").isNotNull();

        assertThat(SCHEMA.at("/properties/journal/properties/on_request/items/properties/match/items/$ref").asString()).isEqualTo("#/$defs/predicate");
        assertThat(SCHEMA.at("/properties/journal/properties/on_response/items/properties/match/items/$ref").asString()).isEqualTo("#/$defs/responsePredicate");
        assertThat(SCHEMA.at("/$defs/route/properties/match/items/$ref").asString()).isEqualTo("#/$defs/predicate");
    }

    /**
     * A route-only filter is flagged at the root and on a group, as the loader refuses it there.
     */
    @Test
    void aRouteOnlyFilterIsRefusedAboveRoutes()
    {
        assertThat(SCHEMA.at("/properties/filters/items/$ref").asString()).isEqualTo("#/$defs/rootFilter");
        assertThat(SCHEMA.at("/$defs/group/properties/filters/items/$ref").asString()).isEqualTo("#/$defs/groupFilter");
        assertThat(SCHEMA.at("/$defs/route/properties/filters/items/$ref").asString()).isEqualTo("#/$defs/routeFilter");
        assertThat(SCHEMA.at("/$defs/routeFilter/$ref").asString()).as("no filter is refused on a route").isEqualTo("#/$defs/filter");

        assertThat(SCHEMA.at("/$defs/filter").findValue("CircuitBreaker")).isNotNull();
        for (final String level : List.of("rootFilter", "groupFilter"))
        {
            assertThat(SCHEMA.at("/$defs/" + level + "/allOf/0/$ref").asString()).isEqualTo("#/$defs/filter");
            final JsonNode refused = SCHEMA.at("/$defs/" + level + "/allOf/1/not/anyOf");
            assertThat(refused.get(0).get("required").get(0).asString()).as(level).isEqualTo("CircuitBreaker");
            assertThat(refused.toString()).as(level).doesNotContain("RateLimiter");
        }
    }

    /**
     * The upstream leg exists only in on_response: anywhere else the loader refuses it, so the
     * editor must not offer it.
     */
    @Test
    void theUpstreamLegIsOfferedOnlyInOnResponse()
    {
        assertThat(levels(SCHEMA.at("/$defs/predicate").findValue("PathPrefix").at("/properties/leg"))).containsExactly("client");
        assertThat(levels(SCHEMA.at("/$defs/unroutedPredicate").findValue("PathPrefix").at("/properties/leg"))).containsExactly("client");
        assertThat(levels(SCHEMA.at("/$defs/responsePredicate").findValue("PathPrefix").at("/properties/leg"))).containsExactly("client", "upstream");
        assertThat(levels(SCHEMA.at("/$defs/responsePredicate").findValue("RemoteAddr").at("/properties/leg"))).as("a client-only predicate").containsExactly("client");
    }

    /**
     * UnroutedReason exists only under unrouted, where its rules take the unrouted predicates.
     */
    @Test
    void unroutedReasonIsOfferedOnlyUnderUnrouted()
    {
        assertThat(SCHEMA.at("/$defs/predicate").findValue("UnroutedReason")).isNull();
        assertThat(SCHEMA.at("/$defs/responsePredicate").findValue("UnroutedReason")).isNull();
        assertThat(SCHEMA.at("/$defs/unroutedPredicate").findValue("UnroutedReason")).isNotNull();
        assertThat(SCHEMA.at("/properties/unrouted/properties/journal/properties/on_request/items/properties/match/items/$ref").asString()).isEqualTo("#/$defs/unroutedPredicate");
        assertThat(SCHEMA.at("/properties/unrouted/properties/journal/properties/on_response").isMissingNode()).isTrue();
    }

    private static List<String> levels(final JsonNode level)
    {
        final List<String> levels = new ArrayList<>();
        level.get("enum").forEach(value -> levels.add(value.asString()));
        return levels;
    }
}
