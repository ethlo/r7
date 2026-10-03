package com.ethlo.r7.warc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.tailer.files.SealedFileWriter;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
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
        final WarcFileWriter crashed = new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3);
        crashed.writeRecord(WarcFields.newRecordId(), "resource",
                List.of(Map.entry("Content-Type", "text/plain")), "first".getBytes(StandardCharsets.UTF_8));
        crashed.writeRecord(WarcFields.newRecordId(), "resource",
                List.of(Map.entry("Content-Type", "text/plain")), "second".getBytes(StandardCharsets.UTF_8));
        // No close(): the process died here, partway through a third frame.
        final Path open = only(".warc.zst.open");
        Files.write(open, new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x00, 0x01}, StandardOpenOption.APPEND);

        new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3).close();

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

        new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3).close();

        try (Stream<Path> files = Files.list(dir))
        {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.startsWith("r7-1-abc"));
        }
    }

    /**
     * An exchange's records are appended together, but a process killed partway through can
     * leave some of them complete. Recovery drops the whole group: its exchange was never
     * checkpointed, and is written again in full.
     */
    @Test
    void aTornExchangeGroupIsDroppedWhole() throws IOException
    {
        final WarcFileWriter crashed = new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3);
        crashed.writeRecords(group("first"));
        crashed.writeRecords(group("second"));
        // No close(): cut the file back to the middle of the second group, at a frame boundary.
        final Path open = only(".warc.zst.open");
        final List<Long> frameEnds = frameEnds(Files.readAllBytes(open));
        assertThat(frameEnds).as("warcinfo and two groups of three").hasSize(7);
        try (FileChannel file = FileChannel.open(open, StandardOpenOption.WRITE))
        {
            file.truncate(frameEnds.get(5));
        }

        new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3).close();

        final String warc = decompress(dir.resolve(open.getFileName().toString().replace(".open", "")));
        assertThat(warc).contains("first-0").contains("first-2").doesNotContain("second");
        assertThat(warc.split("WARC/1.1\r\n", -1)).as("warcinfo and the first group").hasSize(5);
    }

    /**
     * A frame whose length is intact but whose content cannot be decoded (here, a damaged
     * checksum) ends recovery at the group before it, rather than failing every start.
     */
    @Test
    void anUndecodableFrameEndsRecoveryAtTheGroupBeforeIt() throws IOException
    {
        final WarcFileWriter crashed = new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3);
        crashed.writeRecords(group("first"));
        crashed.writeRecords(group("second"));
        final Path open = only(".warc.zst.open");
        final byte[] bytes = Files.readAllBytes(open);
        bytes[bytes.length - 1] ^= (byte) 0xFF;
        Files.write(open, bytes);

        new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3).close();

        final String warc = decompress(dir.resolve(open.getFileName().toString().replace(".open", "")));
        assertThat(warc).contains("first-2").doesNotContain("second");
    }

    /**
     * A file written before group ends were marked is cut back to its last complete frame.
     */
    @Test
    void aFileWithoutGroupMarkersIsCutBackToItsLastCompleteFrame() throws IOException
    {
        final Path leftover = dir.resolve("r7-1-abc.warc.zst.open");
        try (ZstdCompressCtx zstd = new ZstdCompressCtx())
        {
            final byte[] warcinfo = zstd.compress("WARC/1.1\r\nWARC-Type: warcinfo\r\nContent-Length: 0\r\n\r\n\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            final byte[] resource = zstd.compress("WARC/1.1\r\nWARC-Type: resource\r\nContent-Length: 3\r\n\r\nold\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            Files.write(leftover, warcinfo);
            Files.write(leftover, resource, StandardOpenOption.APPEND);
        }

        new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3).close();

        assertThat(decompress(dir.resolve("r7-1-abc.warc.zst"))).contains("old");
    }

    private static List<WarcFileWriter.PendingRecord> group(final String name)
    {
        final List<WarcFileWriter.PendingRecord> records = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            records.add(new WarcFileWriter.PendingRecord(WarcFields.newRecordId(), "resource",
                    List.of(Map.entry("Content-Type", "text/plain")), (name + "-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        return records;
    }

    private static List<Long> frameEnds(final byte[] file)
    {
        final List<Long> ends = new ArrayList<>();
        long end = 0;
        while (end < file.length)
        {
            end += Zstd.findFrameCompressedSize(file, (int) end);
            ends.add(end);
        }
        return ends;
    }

    /**
     * A crash between opening a file and writing its first exchange leaves only the warcinfo
     * record: nothing worth sealing, so the next start removes it.
     */
    @Test
    void aFileLeftOpenWithOnlyItsWarcinfoIsRemoved() throws IOException
    {
        final WarcFileWriter crashed = new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3);
        crashed.writeRecord(WarcFields.newRecordId(), "resource",
                List.of(Map.entry("Content-Type", "text/plain")), "first".getBytes(StandardCharsets.UTF_8));
        // No close(): cut the file back to its warcinfo frame, as if the process died before the
        // exchange was written.
        final Path open = only(".warc.zst.open");
        final long warcinfoEnd = Zstd.findFrameCompressedSize(Files.readAllBytes(open), 0);
        try (FileChannel file = FileChannel.open(open, StandardOpenOption.WRITE))
        {
            file.truncate(warcinfoEnd);
        }

        new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3).close();

        try (Stream<Path> files = Files.list(dir))
        {
            assertThat(files).isEmpty();
        }
    }

    /**
     * A file that crossed the size limit is sealed from the tailer's loop, not only when the
     * next exchange arrives: traffic may stop right after it.
     */
    @Test
    void aFullFileIsSealedWithoutWaitingForTheNextExchange() throws IOException
    {
        try (WarcFileWriter writer = new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 1))
        {
            final byte[] incompressible = new byte[(int) SealedFileWriter.MIN_ROLLOVER_SIZE];
            new Random(7).nextBytes(incompressible);
            writer.writeRecord(WarcFields.newRecordId(), "resource", List.of(Map.entry("Content-Type", "application/octet-stream")), incompressible);
            assertThat(only(".warc.zst.open")).exists();

            writer.rollIfStale();

            assertThat(only(".warc.zst")).exists();
        }
    }

    /**
     * A file is opened by the first record, so a tailer that saw no traffic leaves nothing
     * behind, not a file holding only its warcinfo record.
     */
    @Test
    void aWriterThatWroteNothingLeavesNoFile() throws IOException
    {
        new WarcFileWriter(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3).close();

        try (Stream<Path> files = Files.list(dir))
        {
            assertThat(files).isEmpty();
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
