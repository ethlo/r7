package com.ethlo.r7.warc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.luben.zstd.ZstdInputStream;

class WarcFileWriterTest
{
    private static final long HOUR = 3_600_000L;

    @TempDir
    Path dir;

    /**
     * A crash leaves the current file under its .open name, and nothing sealed it: the exchanges
     * in it had been checkpointed past, so they were never written again, and no consumer looks
     * at a .open file. The next start seals it, cut back to its last complete frame.
     */
    @Test
    void aFileLeftOpenByACrashIsSealedOnTheNextStart() throws IOException
    {
        final WarcFileWriter crashed = new WarcFileWriter(dir, "r7", WarcFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3);
        crashed.writeRecord(WarcFields.newRecordId(), "resource",
                List.of(Map.entry("Content-Type", "text/plain")), "first".getBytes(StandardCharsets.UTF_8));
        crashed.writeRecord(WarcFields.newRecordId(), "resource",
                List.of(Map.entry("Content-Type", "text/plain")), "second".getBytes(StandardCharsets.UTF_8));
        // No close(): the process died here, partway through a third frame.
        final Path open = only(".warc.zst.open");
        Files.write(open, new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x00, 0x01}, StandardOpenOption.APPEND);

        new WarcFileWriter(dir, "r7", WarcFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3).close();

        assertThat(Files.exists(open)).isFalse();
        final Path sealed = dir.resolve(open.getFileName().toString().replace(".open", ""));
        final String warc = decompress(sealed);
        assertThat(warc).contains("WARC-Type: warcinfo").contains("first").contains("second");
        assertThat(warc.split("WARC/1.1\r\n", -1)).as("warcinfo and the two records").hasSize(4);
    }

    @Test
    void aFileLeftOpenWithNoCompleteFrameIsRemoved() throws IOException
    {
        final Path torn = dir.resolve("r7-1-abc.warc.zst.open");
        Files.write(torn, new byte[]{0x28, (byte) 0xB5, 0x2F});

        new WarcFileWriter(dir, "r7", WarcFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3).close();

        try (Stream<Path> files = Files.list(dir))
        {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.startsWith("r7-1-abc"));
        }
    }

    private Path only(final String suffix) throws IOException
    {
        try (Stream<Path> files = Files.list(dir))
        {
            final List<Path> matching = files.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
            assertThat(matching).hasSize(1);
            return matching.getFirst();
        }
    }

    private static String decompress(final Path file) throws IOException
    {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(Files.readAllBytes(file))))
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
