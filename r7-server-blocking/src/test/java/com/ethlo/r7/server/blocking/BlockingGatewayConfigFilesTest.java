package com.ethlo.r7.server.blocking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.config.ConfigurationException;
import com.ethlo.r7.server.config.ServerConfig;

/**
 * A routes.yaml is all r7 needs to start: server.yaml is optional, and the gateway runs on the
 * built-in defaults without it. A missing routes.yaml, or a server.yaml that was named but is not
 * there, stops it with an error that says which file and what to do.
 */
class BlockingGatewayConfigFilesTest
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

    @Test
    void startsOnTheBuiltInDefaultsWithOnlyARoutesFile() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES);
        try (BlockingGateway gateway = new BlockingGateway(routes, this.dir.resolve("server.yaml")))
        {
            final ServerConfig config = gateway.serverConfig();
            assertThat(config.server().port()).isEqualTo(8888);
            assertThat(config.management().host()).isEqualTo("127.0.0.1");
            assertThat(config.management().port()).isEqualTo(18888);
            assertThat(config.limits().trustedProxies()).isEmpty();
        }
    }

    @Test
    void aMissingRoutesFileNamesThePathAndHowToFixIt() throws IOException
    {
        final Path journals = this.dir.resolve("journals");
        final Path server = Files.writeString(this.dir.resolve("server.yaml"), """
                storage:
                  work_dir: %s
                """.formatted(journals));
        final Path routes = this.dir.resolve("routes.yaml");

        assertThatThrownBy(() -> new BlockingGateway(routes, server))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(routes.toAbsolutePath().toString())
                .hasMessageContaining("R7_ROUTES_CONFIG");
        assertThat(journals).doesNotExist();
    }

    @Test
    void aDirectoryIsNotARoutesFile()
    {
        assertThatThrownBy(() -> new BlockingGateway(this.dir, this.dir.resolve("server.yaml")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("No routes file at " + this.dir.toAbsolutePath());
    }

    @Test
    void theDefaultServerFileMayBeAbsent()
    {
        assertThat(BlockingGateway.serverFile(null, "R7_SERVER_CONFIG")).isEqualTo(Paths.get("server.yaml"));
        assertThat(BlockingGateway.serverFile(" ", "R7_SERVER_CONFIG")).isEqualTo(Paths.get("server.yaml"));
    }

    @Test
    void aNamedServerFileMustExist()
    {
        final Path missing = this.dir.resolve("sever.yaml");
        assertThatThrownBy(() -> BlockingGateway.serverFile(missing.toString(), "R7_SERVER_CONFIG"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("R7_SERVER_CONFIG names " + missing.toAbsolutePath());
    }

    @Test
    void aNamedServerFileThatExistsIsUsed() throws IOException
    {
        final Path server = Files.writeString(this.dir.resolve("server.yaml"), "server:\n  port: 9999\n");
        assertThat(BlockingGateway.serverFile(server.toString(), "R7_SERVER_CONFIG")).isEqualTo(server);
    }
}
