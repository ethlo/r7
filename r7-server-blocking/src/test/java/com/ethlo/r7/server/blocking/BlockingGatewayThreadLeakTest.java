package com.ethlo.r7.server.blocking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.config.ConfigurationException;

/**
 * A servlet container creates a gateway on every deploy and closes it on every undeploy, in a
 * JVM that lives on, so a closed gateway must leave no thread of its own behind - nor must one
 * that failed to start.
 */
class BlockingGatewayThreadLeakTest
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
    void startingAndClosingGatewaysLeavesNoThreadsBehind() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES);
        final Path server = serverFile();

        // The first gateway starts what is shared by every gateway in the JVM, once
        new BlockingGateway(routes, server).close();
        final Set<Thread> before = liveThreads();

        for (int i = 0; i < 5; i++)
        {
            new BlockingGateway(routes, server).close();
        }

        assertNoThreadsBeyond(before);
    }

    @Test
    void aGatewayThatFailsToStartLeavesNoThreadsBehind() throws IOException
    {
        final Path routes = Files.writeString(this.dir.resolve("routes.yaml"), ROUTES);
        final Path broken = Files.writeString(this.dir.resolve("broken.yaml"), "routes:\n  - id: echo\n    upstrem: {}\n");
        final Path server = serverFile();

        new BlockingGateway(routes, server).close();
        final Set<Thread> before = liveThreads();

        for (int i = 0; i < 5; i++)
        {
            assertThatThrownBy(() -> new BlockingGateway(broken, server)).isInstanceOf(ConfigurationException.class);
        }

        assertNoThreadsBeyond(before);
    }

    private Path serverFile() throws IOException
    {
        return Files.writeString(this.dir.resolve("server.yaml"), """
                storage:
                  work_dir: %s
                """.formatted(this.dir.resolve("journals")));
    }

    private static Set<Thread> liveThreads()
    {
        // Fork/join workers, the virtual-thread carriers among them, belong to JDK-wide pools that
        // grow and shrink on their own; a carrier started while a gateway ran is not its leak
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> !(thread instanceof ForkJoinWorkerThread))
                .collect(Collectors.toSet());
    }

    private static void assertNoThreadsBeyond(final Set<Thread> before)
    {
        // A thread that has been told to stop may take a moment to finish
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Set<String> extra;
        do
        {
            extra = liveThreads().stream()
                    .filter(thread -> !before.contains(thread))
                    .map(Thread::getName)
                    .collect(Collectors.toSet());
        }
        while (!extra.isEmpty() && System.nanoTime() < deadline && pause());
        assertThat(extra).as("threads left running by closed gateways").isEmpty();
    }

    private static boolean pause()
    {
        try
        {
            Thread.sleep(50);
            return true;
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
