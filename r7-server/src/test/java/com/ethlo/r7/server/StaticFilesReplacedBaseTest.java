package com.ethlo.r7.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.filters.StaticContentFactory;

/**
 * A static directory bind-mounted into a container and then replaced on the host leaves the mount
 * on the deleted directory: it still opens, empty, with no links. Without a mount to hand, an open
 * directory reached through {@code /proc/self/fd} is the same thing.
 */
@EnabledOnOs(OS.LINUX)
class StaticFilesReplacedBaseTest
{
    @Test
    void aDeletedBaseDirectoryStillHeldOpenIs503NotAnEmptySite() throws IOException
    {
        final Path site = Files.createTempDirectory("r7-static-replaced-");
        Files.writeString(site.resolve("index.html"), "<h1>old</h1>");
        try (DirectoryStream<Path> held = Files.newDirectoryStream(site))
        {
            Files.delete(site.resolve("index.html"));
            Files.delete(site);
            final Path stale = heldOpen(site);
            assumeTrue(stale != null, "no /proc/self/fd entry for the deleted directory");
            assertThat(Files.isDirectory(stale)).isTrue();
            assertThat(StaticFiles.isUnlinked(stale)).isTrue();

            assertThat(serve(stale, "/")).isEqualTo(503);
            assertThat(serve(stale, "/index.html")).isEqualTo(503);
        }
    }

    @Test
    void aLiveBaseDirectoryIsNotUnlinked(@TempDir final Path site) throws IOException
    {
        Files.writeString(site.resolve("index.html"), "<h1>live</h1>");
        assertThat(StaticFiles.isUnlinked(site)).isFalse();
        assertThat(serve(site, "/")).isEqualTo(200);
    }

    private static Path heldOpen(final Path deleted) throws IOException
    {
        try (DirectoryStream<Path> fds = Files.newDirectoryStream(Path.of("/proc/self/fd")))
        {
            for (final Path fd : fds)
            {
                try
                {
                    if (Files.readSymbolicLink(fd).toString().equals(deleted + " (deleted)"))
                    {
                        return fd;
                    }
                }
                catch (final IOException ignored)
                {
                    // Closed in the meantime, or not a link.
                }
            }
        }
        return null;
    }

    private static int serve(final Path base, final String path) throws IOException
    {
        final int[] status = new int[1];
        final GatewayHeaders headers = TestHeaders.of();
        StaticFiles.serve(new StaticFiles.Target()
        {
            @Override
            public String method()
            {
                return "GET";
            }

            @Override
            public String path()
            {
                return path;
            }

            @Override
            public String clientTarget()
            {
                return path;
            }

            @Override
            public GatewayHeaders requestHeaders()
            {
                return headers;
            }

            @Override
            public void answer(final int code, final byte[] body)
            {
                status[0] = code;
            }

            @Override
            public void answerFile(final int code, final Path file, final long offset, final long length, final boolean headOnly)
            {
                status[0] = code;
            }

            @Override
            public void header(final String name, final String value)
            {
            }
        }, new StaticContentFactory.StaticServeRequest(base, false, false, false));
        return status[0];
    }
}
