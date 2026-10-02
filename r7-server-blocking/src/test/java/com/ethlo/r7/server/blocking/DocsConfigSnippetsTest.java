package com.ethlo.r7.server.blocking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.ethlo.r7.docs.DocSnippet;
import com.ethlo.r7.docs.DocSnippets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Every routes.yaml and server.yaml snippet in the docs starts a gateway, through the same
 * loading and validation as a real start. See {@link DocSnippets} for how a snippet says which
 * file it is.
 */
class DocsConfigSnippetsTest
{
    private static final YAMLMapper YAML = new YAMLMapper();

    private static final Set<String> ROUTES_KEYS = Set.of("version", "global_filters", "routes", "unrouted");
    private static final Set<String> ROUTE_KEYS = Set.of("id", "match", "upstream", "filters", "journal");

    /**
     * Directories the snippets name that a filter requires to exist on the host, as a real
     * deployment has them. Each is swapped for an empty directory the test creates.
     */
    private static final Set<String> HOST_DIRECTORIES = Set.of("/var/www/html/");

    private static final String PLACEHOLDER_ROUTE = """
            id: docs-example
            match:
              - PathPrefix:
                  prefix: /
            upstream:
              targets:
                - url: http://127.0.0.1:9
            """;

    @TempDir
    Path dir;

    @BeforeAll
    static void setVariables()
    {
        DocSnippets.setVariables();
    }

    @AfterAll
    static void clearVariables()
    {
        DocSnippets.clearVariables();
    }

    static List<DocSnippet> routes()
    {
        final List<DocSnippet> routes = new ArrayList<>(DocSnippets.of(DocSnippets.ROUTES));
        routes.addAll(DocSnippets.of(null));
        return routes;
    }

    static List<DocSnippet> servers()
    {
        return DocSnippets.of(DocSnippets.SERVER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void routesSnippetStartsTheGateway(final DocSnippet snippet) throws Exception
    {
        String routes = snippet.title() != null ? snippet.yaml() : YAML.writeValueAsString(completed(snippet));
        for (final String directory : HOST_DIRECTORIES)
        {
            routes = routes.replace(directory, Files.createDirectories(this.dir.resolve("host" + directory)) + "/");
        }
        start(snippet, Files.writeString(this.dir.resolve("routes.yaml"), routes), serverFile(YAML.createObjectNode()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servers")
    void serverSnippetStartsTheGateway(final DocSnippet snippet) throws Exception
    {
        final JsonNode server = YAML.readTree(snippet.yaml());
        assertThat(server.isObject()).as(snippet + " is not a YAML mapping").isTrue();
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), "routes:\n  - " + PLACEHOLDER_ROUTE.indent(4).strip() + "\n");
        start(snippet, routes, serverFile((ObjectNode) server));
    }

    /**
     * An untitled snippet is part of a routes.yaml: its route keys go into a placeholder route,
     * and a file with no routes gets that placeholder route.
     */
    private static ObjectNode completed(final DocSnippet snippet)
    {
        final JsonNode parsed = YAML.readTree(snippet.yaml());
        if (!parsed.isObject())
        {
            fail(snippet + " is not a YAML mapping");
        }

        final ObjectNode file = YAML.createObjectNode();
        final ObjectNode route = (ObjectNode) YAML.readTree(PLACEHOLDER_ROUTE);
        boolean routeKeys = false;
        for (final var entry : parsed.properties())
        {
            if (ROUTES_KEYS.contains(entry.getKey()))
            {
                file.set(entry.getKey(), entry.getValue());
            }
            else if (ROUTE_KEYS.contains(entry.getKey()))
            {
                route.set(entry.getKey(), entry.getValue());
                routeKeys = true;
            }
            else
            {
                fail(snippet + ": '" + entry.getKey() + "' is neither a routes.yaml key nor a route's. If the snippet is another file, "
                        + "say which with title=\"...\" on its fence (one of " + DocSnippets.FILES + ")");
            }
        }

        if (routeKeys && file.has("routes"))
        {
            fail(snippet + " mixes the keys of one route with a list of routes");
        }
        if (!file.has("routes"))
        {
            file.putArray("routes").add(route);
        }
        return file;
    }

    /**
     * Journals go to the test's directory whatever the snippet says, so a snippet naming a path
     * like /journals still starts.
     */
    private Path serverFile(final ObjectNode server) throws Exception
    {
        final JsonNode storage = server.get("storage");
        final ObjectNode writableStorage = storage instanceof ObjectNode object ? object : server.putObject("storage");
        writableStorage.put("work_dir", this.dir.resolve("journals").toString());
        return Files.writeString(this.dir.resolve("server.yaml"), YAML.writeValueAsString(server));
    }

    private static void start(final DocSnippet snippet, final Path routes, final Path server) throws Exception
    {
        try (BlockingGateway gateway = new BlockingGateway(routes, server))
        {
            assertThat(gateway.serverConfig()).isNotNull();
        }
        catch (final RuntimeException e)
        {
            throw new AssertionError(snippet + " does not start the gateway: " + e.getMessage(), e);
        }
    }
}
