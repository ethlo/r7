package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reproduces a bug found in the round-2 gateway security review: {@link R7Main#stop()} stopped
 * the two Undertow listeners but never shut down the journal writer and never deregistered the
 * JVM shutdown hook registered in the constructor.
 * <p>
 * In a JVM that creates and stops several {@code R7Main} instances - every in-process integration
 * test in this module does exactly that, one instance per test class, all sharing the default
 * {@code journals} work directory - each stopped instance left its journal writer's active
 * segment open and its shutdown hook still registered. A later instance's recovery pass
 * (R7fRecoveryManager.cleanAndRecover) could then rename that still-open segment out from under
 * the old, still-live writer, and every accumulated hook fired at actual JVM exit trying to
 * finalize a file a later instance had already moved - producing sporadic
 * {@code NoSuchFileException}s at shutdown and, worse, journal entries that a tailer could never
 * find because the writer that should have flushed them was never told to stop. This is what made
 * {@code UnroutedJournalTest} fail intermittently in a full {@code mvn test -pl r7-undertow} run
 * while always passing alone.
 */
class R7MainShutdownTest
{
    @TempDir
    Path dir;

    @Test
    void stopFullyClosesTheJournalBeforeANewInstanceRecoversTheSameWorkDir() throws Exception
    {
        final Path workDir = dir.resolve("journals");

        final R7Main first = startInstance(workDir);
        try
        {
            // Write something so there is an active, unsealed segment to close.
            first.stop();
        }
        finally
        {
            // No-op if stop() already fully tore things down; guards against a test failure
            // leaking a listening port into later tests.
            safeStop(first);
        }

        // A clean stop() must have sealed the active segment into a .r7f file - not left a
        // .flux behind for the next instance to "recover" out from under a still-running writer.
        assertThat(listFluxFiles(workDir))
                .as("stop() must seal the active segment; a dangling .flux means the journal "
                        + "writer was never told to shut down")
                .isEmpty();

        // A second instance, sharing the same work dir, must be able to start cleanly: if the
        // first instance's shutdown hook is still registered and its writer still holds the
        // segment open, recovery here would be operating on a file another live writer still owns.
        final R7Main second = startInstance(workDir);
        try
        {
            second.stop();
        }
        finally
        {
            safeStop(second);
        }

        assertThat(listFluxFiles(workDir)).isEmpty();
    }

    private static void safeStop(final R7Main instance)
    {
        try
        {
            instance.stop();
        }
        catch (final RuntimeException ignored)
        {
            // Already stopped; stop() is idempotent, but ignore any listener-already-closed noise.
        }
    }

    private static List<Path> listFluxFiles(final Path workDir) throws IOException
    {
        if (!Files.isDirectory(workDir))
        {
            return List.of();
        }
        try (Stream<Path> files = Files.list(workDir))
        {
            return files.filter(p -> p.getFileName().toString().endsWith(".flux")).collect(Collectors.toList());
        }
    }

    private static R7Main startInstance(final Path workDir) throws Exception
    {
        final int dataPort = freePort();
        final int managementPort = freePort();

        final Path routesFile = Files.createTempFile("r7-main-shutdown-routes-", ".yaml");
        Files.writeString(routesFile, """
                version: test
                routes:
                  - id: only
                    match:
                      - Path:
                          path: /ok
                    upstream:
                      targets:
                        - url: http://localhost:1
                """, StandardCharsets.UTF_8);

        final Path serverFile = Files.createTempFile("r7-main-shutdown-server-", ".yaml");
        Files.writeString(serverFile, """
                server:
                  port: %d
                  host: 127.0.0.1
                management:
                  port: %d
                  host: 127.0.0.1
                storage:
                  work_dir: %s
                """.formatted(dataPort, managementPort, workDir.toAbsolutePath()), StandardCharsets.UTF_8);

        return new R7Main(routesFile, serverFile);
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
