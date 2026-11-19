package com.ethlo.r7.server.blocking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config check fails on everything the gateway's startup fails on, says why in the words
 * startup uses, and leaves nothing behind: no work directory, no journal.
 */
class ConfigCheckTest
{
    private static final String ROUTES = """
            routes:
              - id: echo
                upstream:
                  targets:
                    - url: http://127.0.0.1:9
                match:
                  - PathPrefix:
                      prefix: /
                filters:
                  - SimpleMetrics: {}
            """;

    @TempDir
    Path dir;

    private Path journals;
    private Path server;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void writeServerFile() throws IOException
    {
        this.journals = this.dir.resolve("journals");
        this.server = Files.writeString(this.dir.resolve("server.yaml"), """
                storage:
                  work_dir: %s
                """.formatted(this.journals));
    }

    @Test
    void aValidConfigurationExitsZeroAndNamesBothFiles() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES);

        assertThat(check(routes, this.server)).isEqualTo(ConfigCheck.VALID);
        assertThat(out()).isEqualTo(routes + " and " + this.server + " OK" + System.lineSeparator());
        assertThat(err()).isEmpty();
        assertThat(this.journals).doesNotExist();
        assertThat(this.dir.resolve("telemetry.json")).doesNotExist();
    }

    @Test
    void anAbsentServerFileIsCheckedAsTheBuiltInDefaults() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES);

        assertThat(ConfigCheck.run(Map.of(BlockingGateway.ROUTES_ENVIRONMENT_VARIABLE, routes.toString()),
                new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8)))
                .isEqualTo(ConfigCheck.VALID);
        assertThat(out()).contains(routes + " OK, with the built-in server defaults");
    }

    @Test
    void anUnknownPropertyFailsWithItsPath() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES.replace("    match:", "    matches:"));

        assertThat(check(routes, this.server)).isEqualTo(ConfigCheck.INVALID);
        assertThat(err()).startsWith(routes + ": ").contains("matches");
        assertThat(out()).isEmpty();
        assertThat(this.journals).doesNotExist();
    }

    @Test
    void aDuplicateRouteIdFails() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES + ROUTES.replace("routes:\n", ""));

        assertThat(check(routes, this.server)).isEqualTo(ConfigCheck.INVALID);
        assertThat(err()).contains("[routes.echo] id is not unique").doesNotContain("\tat ");
    }

    @Test
    void anInvalidFilterArgumentFails() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES.replace("SimpleMetrics: {}", """
                SimpleMetrics:
                          interval: 1x"""));

        assertThat(check(routes, this.server)).isEqualTo(ConfigCheck.INVALID);
        assertThat(err()).startsWith(routes + ": ").contains("interval");
    }

    @Test
    void anInvalidServerFileFailsWithItsPath() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES);
        Files.writeString(this.server, """
                storage:
                  work_dir: %s
                  shard_count: 3
                """.formatted(this.journals));

        assertThat(check(routes, this.server)).isEqualTo(ConfigCheck.INVALID);
        assertThat(err()).startsWith(this.server + ": ").contains("[storage.shard_count]");
        assertThat(this.journals).doesNotExist();
    }

    @Test
    void aMissingEnvironmentVariableWithoutADefaultFails() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"),
                ROUTES.replace("http://127.0.0.1:9", "${R7_CONFIG_CHECK_TEST_UNSET_UPSTREAM}"));

        assertThat(check(routes, this.server)).isEqualTo(ConfigCheck.INVALID);
        assertThat(err()).contains("Missing required environment variable 'R7_CONFIG_CHECK_TEST_UNSET_UPSTREAM'");
    }

    @Test
    void aMissingRoutesFileFails()
    {
        final Path routes = this.dir.resolve("routes.yaml");

        assertThat(check(routes, this.server)).isEqualTo(ConfigCheck.INVALID);
        assertThat(err()).contains("No routes file at " + routes.toAbsolutePath());
    }

    @Test
    void aNamedServerFileThatIsMissingFails() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES);

        assertThat(check(routes, this.dir.resolve("sever.yaml"))).isEqualTo(ConfigCheck.INVALID);
        assertThat(err()).contains("R7_SERVER_CONFIG names");
    }

    private int check(final Path routes, final Path server)
    {
        return ConfigCheck.run(Map.of(BlockingGateway.ROUTES_ENVIRONMENT_VARIABLE, routes.toString(),
                        BlockingGateway.SERVER_ENVIRONMENT_VARIABLE, server.toString()),
                new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8));
    }

    private String out()
    {
        return this.out.toString(StandardCharsets.UTF_8);
    }

    private String err()
    {
        return this.err.toString(StandardCharsets.UTF_8);
    }
}
