package com.ethlo.venturi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ethlo.venturi.core.helpers.SimpleGatewayHeaders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.core.ServerDirection;
import com.ethlo.venturi.core.storage.mmap.Journal;
import com.ethlo.venturi.core.storage.mmap.JournalAnalyzer;

class JournalBinaryIntegrationTest
{
    private static final Logger logger = LoggerFactory.getLogger(JournalBinaryIntegrationTest.class);

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

        GatewayHeaders headers = new SimpleGatewayHeaders();
        headers.add("X-Test", "Value1");
        headers.add("X-High-Load", "True");

        // 1. Write the full lifecycle
        journal.writeBegin(ServerDirection.REQUEST, reqId, startLine, headers);
        journal.writeBody(ServerDirection.REQUEST, reqId, ByteBuffer.wrap("Small Body".getBytes()));
        journal.writeEnd(reqId, 204, 122, 211, 120_009_999);
        journal.force(); // Ensure the OS sees the bytes

        // 2. Use the Analyzer logic to verify
        // The analyzer expects a directory, not a file
        JournalAnalyzer.Stats stats = analyze(tempDir);

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
            journal.writeBegin(ServerDirection.REQUEST, id, ByteBuffer.wrap("L".getBytes()), new SimpleGatewayHeaders());
            journal.writeEnd(id, 204, 122, 211, 120_009_999);
        }
        journal.force();

        // The analyzer expects a directory, not a file
        JournalAnalyzer.Stats stats = analyze(tempDir);
        assertThat(stats.begins).isEqualTo(count);
        assertThat(stats.ends).isEqualTo(count);
    }

    private JournalAnalyzer.Stats analyze(Path path) throws IOException
    {
        // We use your JournalAnalyzer logic here
        return new JournalAnalyzer(path).analyze();
    }

    private void printHexDump(Path path) throws IOException
    {
        byte[] data = Files.readAllBytes(path);
        logger.error("--- HEX DUMP (First 256 bytes) ---");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 256); i++)
        {
            sb.append(String.format("%02X ", data[i]));
            if ((i + 1) % 16 == 0)
            {
                logger.error(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0)
        {
            logger.error(sb.toString());
        }
        logger.error("\n----------------------------------");
    }
}