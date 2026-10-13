package com.ethlo.r7.helidon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --validate-only} checks the configuration and returns an exit status without starting
 * anything: neither port is bound, and no journal directory is created.
 */
class R7HelidonCommandTest
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
            """;

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void validateOnlyBindsNoPortAndCreatesNoJournal() throws IOException
    {
        final int port = freePort();
        final int managementPort = freePort();
        final Path journals = this.dir.resolve("journals");
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES);
        final Path server = Files.writeString(this.dir.resolve("server.yaml"), """
                server:
                  host: 127.0.0.1
                  port: %d
                management:
                  port: %d
                storage:
                  work_dir: %s
                """.formatted(port, managementPort, journals));

        assertThat(run(env(routes, server), "--validate-only")).isEqualTo(0);
        assertThat(this.out.toString(StandardCharsets.UTF_8)).contains("OK");
        assertThat(journals).doesNotExist();
        assertBindable(port);
        assertBindable(managementPort);
    }

    @Test
    void validateOnlyExitsOneOnAnInvalidConfiguration() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES + ROUTES.replace("routes:\n", ""));
        final Path server = Files.writeString(this.dir.resolve("server.yaml"), "storage:\n  work_dir: %s\n".formatted(this.dir.resolve("journals")));

        assertThat(run(env(routes, server), "--validate-only")).isEqualTo(1);
        assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("[routes.echo] id is not unique");
    }

    @Test
    void anUnknownArgumentIsAUsageError()
    {
        assertThat(run(Map.of(), "--validate")).isEqualTo(R7Helidon.USAGE_ERROR);
        assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("Unknown arguments: --validate").contains("--validate-only");
    }

    @Test
    void validateOnlyTakesNoFurtherArguments()
    {
        assertThat(run(Map.of(), "--validate-only", "routes.yaml")).isEqualTo(R7Helidon.USAGE_ERROR);
    }

    private int run(final Map<String, String> environment, final String... args)
    {
        return R7Helidon.command(args, environment, new PrintStream(this.out, true, StandardCharsets.UTF_8), new PrintStream(this.err, true, StandardCharsets.UTF_8));
    }

    private static Map<String, String> env(final Path routes, final Path server)
    {
        return Map.of("R7_ROUTES_CONFIG", routes.toString(), "R7_SERVER_CONFIG", server.toString());
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        {
            return socket.getLocalPort();
        }
    }

    private static void assertBindable(final int port) throws IOException
    {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress()))
        {
            assertThat(socket.getLocalPort()).isEqualTo(port);
        }
    }
}
