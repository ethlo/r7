package com.ethlo.venturi.vlf;

import static java.nio.ByteBuffer.wrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.ethlo.venturi.api.IpSource;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.chronograph.Chronograph;
import com.ethlo.venturi.api.MutableGatewayHeaders;
import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.util.MutableFastGatewayHeaders;

class VlfJournalTest
{
    @TempDir
    private Path tempDir;

    @RepeatedTest(5)
    void clientRequest() throws IOException
    {
        final int iterations = 2_000_000;

        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.set("user-agent", "Mozilla/5.0 (Venturi Bench)");
        headers.set("content-type", "application/json");
        headers.set("connection", "keep-alive");
        for (int i = 0; i < 10; i++)
        {
            headers.set("Header-" + i, "header-value" + i);
        }

        final ByteBuffer startLine = wrap("GET /test HTTP/1.1".getBytes());

        final VlfJournalProvider provider = new VlfJournalProvider(tempDir, 0, Integer.MAX_VALUE, true);
        final String id = "adasqwteyutqwet";
        try (final VlfJournal journal = new VlfJournal(provider))
        {
            Chronograph chronograph = Chronograph.create();
            chronograph.time("client-request encoding", () ->
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