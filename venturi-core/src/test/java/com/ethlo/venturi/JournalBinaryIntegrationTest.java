package com.ethlo.venturi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.MockGatewayExchange;
import com.ethlo.venturi.core.ServerDirection;
import com.ethlo.venturi.core.storage.mmap.Journal;
import com.ethlo.venturi.core.storage.mmap.JournalAnalyzer;

class JournalBinaryIntegrationTest
{

    @TempDir
    Path tempDir;

    private Journal journal;
    private Path journalPath;

    @BeforeEach
    void setup() throws IOException
    {
        journalPath = tempDir.resolve("test-journal.raw");
        // 1MB is plenty for this test
        journal = new Journal(journalPath, 1024 * 1024);
    }

    @Test
    void testSingleRequestIntegrity() throws IOException
    {
        String reqId = "req-123";
        ByteBuffer startLine = ByteBuffer.wrap("GET /test HTTP/1.1".getBytes());

        GatewayHeaders headers = new MockGatewayExchange.MockHeaders();
        headers.add("X-Test", "Value1");
        headers.add("X-High-Load", "True");

        // 1. Write the full lifecycle
        journal.writeBegin(ServerDirection.REQUEST.ordinal(), reqId, startLine, headers);
        journal.writeBody(reqId, ByteBuffer.wrap("Small Body".getBytes()));
        journal.writeEnd(reqId);
        journal.force(); // Ensure the OS sees the bytes

        // 2. Use the Analyzer logic to verify
        JournalAnalyzer.Stats stats = analyze(journalPath);

        // 3. Assertions
        try
        {
            assertThat(stats.begins).as("Should have exactly 1 BEGIN marker").isEqualTo(1);
            assertThat(stats.bodies).as("Should have exactly 1 BODY marker").isEqualTo(1);
            assertThat(stats.ends).as("Should have exactly 1 END marker").isEqualTo(1);
        }
        catch (AssertionError e)
        {
            printHexDump(journalPath);
            throw e;
        }
    }

    @Test
    void testMultipleRequestsInSequence() throws IOException
    {
        int count = 100;
        for (int i = 0; i < count; i++)
        {
            String id = "id-" + i;
            journal.writeBegin(0, id, ByteBuffer.wrap("L".getBytes()), new MockGatewayExchange.MockHeaders());
            journal.writeEnd(id);
        }
        journal.force();

        JournalAnalyzer.Stats stats = analyze(journalPath);
        assertThat(stats.begins).isEqualTo(count);
        assertThat(stats.ends).isEqualTo(count);
    }

    private JournalAnalyzer.Stats analyze(Path path)
    {
        // We use your JournalAnalyzer logic here
        return JournalAnalyzer.analyzeFile(path);
    }

    private void printHexDump(Path path) throws IOException
    {
        byte[] data = Files.readAllBytes(path);
        System.err.println("--- HEX DUMP (First 256 bytes) ---");
        for (int i = 0; i < Math.min(data.length, 256); i++)
        {
            System.err.printf("%02X ", data[i]);
            if ((i + 1) % 16 == 0) System.err.println();
        }
        System.err.println("\n----------------------------------");
    }
}