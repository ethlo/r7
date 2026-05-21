package com.ethlo.r7.r7f;

import static java.nio.ByteBuffer.wrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.ethlo.r7.api.IpSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.chronograph.Chronograph;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

class R7fJournalPerformanceTest
{
    @TempDir
    private Path tempDir;

    @Test
    void clientRequest() throws IOException
    {
        final int iterations = 2_000_000;

        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.set("user-agent", "Mozilla/5.0 (R7 Bench)");
        headers.set("content-type", "application/json");
        headers.set("connection", "keep-alive");
        for (int i = 0; i < 10; i++)
        {
            headers.set("Header-" + i, "header-value" + i);
        }

        final ByteBuffer startLine = wrap("GET /test HTTP/1.1".getBytes());

        final R7fJournalProvider provider = new R7fJournalProvider(tempDir, 0, Integer.MAX_VALUE, true);
        final String id = "adasqwteyutqwet";
        try (final R7fJournal journal = new R7fJournal(provider))
        {
            Chronograph chronograph = Chronograph.create();
            final String taskName = "client-request encoding (" + iterations + ")";
            chronograph.time(taskName, () ->
                    {
                        for (int i = 0; i < iterations; i++)
                        {
                            try
                            {
                                journal.clientRequest(JournalLevel.FULL, id, startLine, headers, InetAddress.getLocalHost(), IpSource.SOCKET);
                            }
                            catch (UnknownHostException e)
                            {
                                throw new RuntimeException(e);
                            }
                        }
                    }
            );
            System.out.println(chronograph);
        } finally
        {
            deleteDirectory(tempDir);
        }
    }

    private void deleteDirectory(Path path) throws IOException
    {
        if (!Files.exists(path))
        {
            return;
        }

        // walk returns a Stream of Path objects
        try (Stream<Path> walk = Files.walk(path))
        {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try
                        {
                            Files.delete(p);
                        }
                        catch (IOException e)
                        {
                            System.err.printf("Failed to delete %s: %s%n", p, e.getMessage());
                        }
                    });
        }
    }
}