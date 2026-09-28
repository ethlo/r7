package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.spi.EngineContext;

class FallbackValidationTest
{
    @TempDir
    Path dir;

    private static String route(final String id, final String fallback)
    {
        return """
                  - id: %s
                    match:
                      - PathPrefix:
                          prefix: /%s
                    upstream:
                      targets:
                        - url: http://localhost:1
                %s
                """.formatted(id, id, fallback == null ? "" : """
                      fallback:
                        route_id: %s
                """.formatted(fallback));
    }

    private void load(final String... routes) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, "version: test\nroutes:\n" + String.join("", routes));
        final RoutesDefinition definition = ConfigurationManager.load(file, RoutesDefinition.class);
        new ConfigurationManager(new EngineContext(Map.of())).load(definition, new RouteRegistry());
    }

    @Test
    void acceptsAFallbackChain()
    {
        assertThatCode(() -> load(route("a", "b"), route("b", "c"), route("c", null))).doesNotThrowAnyException();
    }

    @Test
    void rejectsATwoRouteCycle()
    {
        assertThatThrownBy(() -> load(route("a", "b"), route("b", "a")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void rejectsALongerCycleAndNamesIt()
    {
        assertThatThrownBy(() -> load(route("entry", "a"), route("a", "b"), route("b", "c"), route("c", "a")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("a -> b -> c -> a");
    }

    @Test
    void stillRejectsSelfReference()
    {
        assertThatThrownBy(() -> load(route("a", "a")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("itself");
    }
}
