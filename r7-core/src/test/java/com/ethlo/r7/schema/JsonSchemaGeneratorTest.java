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
        final JsonNode items = SCHEMA.at("/$defs/predicate/anyOf").findValue("Status").findValue("include").get("items");

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

    private static List<String> levels(final JsonNode level)
    {
        final List<String> levels = new ArrayList<>();
        level.get("enum").forEach(value -> levels.add(value.asString()));
        return levels;
    }
}
