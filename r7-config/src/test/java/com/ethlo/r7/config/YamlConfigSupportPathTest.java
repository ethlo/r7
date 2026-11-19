package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.ObjectMapper;

/**
 * The property path in a config error must read the way the YAML is navigated. A property
 * inside a list item used to lose its dot ({@code routes[0]upstrem}), so the error meant to
 * point at a typo looked like one itself.
 */
class YamlConfigSupportPathTest
{
    private static final ObjectMapper MAPPER = YamlConfigSupport.baseMapperBuilder().build();

    @TempDir
    Path dir;

    public record Root(List<Item> items, Map<String, Item> byName)
    {
    }

    public record Item(String name, Integer weight, List<Item> children)
    {
    }

    @Test
    void unknownPropertyAtTheTopLevel()
    {
        assertUnknown("""
                bogus: 1
                """, "bogus");
    }

    @Test
    void unknownPropertyInAListItem()
    {
        assertUnknown("""
                items:
                  - name: a
                  - name: b
                    bogus: 1
                """, "items[1].bogus");
    }

    @Test
    void unknownPropertyInANestedList()
    {
        assertUnknown("""
                items:
                  - name: a
                    children:
                      - name: b
                      - name: c
                        bogus: 1
                """, "items[0].children[1].bogus");
    }

    @Test
    void unknownPropertyInAMapValue()
    {
        assertUnknown("""
                by_name:
                  alpha:
                    children:
                      - bogus: 1
                """, "by_name.alpha.children[0].bogus");
    }

    @Test
    void mappingErrorPathInAListItem()
    {
        assertThatThrownBy(() -> load("""
                items:
                  - name: a
                    children:
                      - weight: heavy
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Property path: items[0].children[0].weight" + System.lineSeparator());
    }

    @Test
    void positionsOfScalarsByPointer()
    {
        final YamlSourcePositions positions = new YamlSourcePositions(MAPPER, """
                items:
                  - name: a
                    children:
                      - name: b
                by_name:
                  alpha:
                    weight: 3
                """);

        assertThat(positions.of(JsonPointer.compile("/items/0/name"))).contains(new YamlSourcePositions.Position(2, 11));
        assertThat(positions.of(JsonPointer.compile("/items/0/children/0/name"))).contains(new YamlSourcePositions.Position(4, 15));
        assertThat(positions.of(JsonPointer.compile("/by_name/alpha/weight"))).contains(new YamlSourcePositions.Position(7, 13));
        assertThat(positions.of(JsonPointer.compile("/items/0"))).isEmpty();
    }

    private void assertUnknown(final String yaml, final String path)
    {
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Unknown configuration option: 'bogus' at [" + path + "]");
    }

    private Root load(final String yaml) throws Exception
    {
        final Path file = dir.resolve("config.yaml");
        Files.writeString(file, yaml);
        return YamlConfigSupport.load(MAPPER, file, Root.class);
    }
}
