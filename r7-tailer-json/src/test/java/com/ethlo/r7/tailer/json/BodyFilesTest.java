package com.ethlo.r7.tailer.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.tailer.files.SealedFileWriter;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdInputStream;

class BodyFilesTest
{
    private static final long HOUR = 3_600_000L;

    @TempDir
    Path dir;

    /**
     * Each pointer names the sealed file, and the bytes there decompress to exactly that body.
     */
    @Test
    void aPointerReadsBackItsBody() throws IOException
    {
        final BodyFiles.Written first;
        final BodyFiles.Written second;
        try (BodyFiles files = bodyFiles(16))
        {
            first = files.write(body("request one"), body("response one"));
            files.commit(first);
            second = files.write(null, body("response two"));
            files.commit(second);
        }

        assertThat(read(first.request())).isEqualTo("request one");
        assertThat(read(first.response())).isEqualTo("response one");
        assertThat(second.request()).isNull();
        assertThat(read(second.response())).isEqualTo("response two");
        assertThat(first.request().offset()).isZero();
        assertThat(second.response().offset()).isEqualTo(first.response().offset() + first.response().length());
        assertThat(sealedFiles()).singleElement().satisfies(f -> assertThat(f.getFileName().toString()).endsWith(".bodies.zst"));
    }

    /**
     * A whole file is nothing but frames: {@code zstd -d} gives the bodies back to back.
     */
    @Test
    void aWholeFileDecompressesToTheBodiesBackToBack() throws IOException
    {
        try (BodyFiles files = bodyFiles(16))
        {
            files.commit(files.write(body("a"), body("b")));
            files.commit(files.write(body("c"), null));
        }
        try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(Files.readAllBytes(sealedFiles().getFirst()))))
        {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abc");
        }
    }

    @Test
    void noBodyWritesNothing() throws IOException
    {
        try (BodyFiles files = bodyFiles(16))
        {
            final BodyFiles.Written written = files.write(List.of(), List.of(ByteBuffer.allocate(0)));
            assertThat(written.request()).isNull();
            assertThat(written.response()).isNull();
            files.discard(written);
        }
        assertThat(sealedFiles()).isEmpty();
    }

    /**
     * A body already in the file is not written again; the line gets the earlier pointer. A
     * response that echoes its request is stored once too.
     */
    @Test
    void aRepeatedBodyPointsAtTheFirstCopy() throws IOException
    {
        try (BodyFiles files = bodyFiles(16))
        {
            final BodyFiles.Written first = files.write(body("same"), body("same"));
            assertThat(first.response()).isEqualTo(first.request());
            files.commit(first);
            final BodyFiles.Written second = files.write(body("same"), body("other"));
            files.commit(second);
            assertThat(second.request()).isEqualTo(first.request());
            assertThat(second.response().offset()).isEqualTo(first.request().length());
        }
        assertThat(frames(Files.readAllBytes(sealedFiles().getFirst()))).isEqualTo(2);
    }

    /**
     * A body is remembered only once its line is written: one taken back is stored afresh on
     * the retry, and the file holds it once.
     */
    @Test
    void aDiscardedBodyIsTakenBackAndNotReused() throws IOException
    {
        try (BodyFiles files = bodyFiles(16))
        {
            files.commit(files.write(null, body("kept")));
            final BodyFiles.Written failed = files.write(null, body("retried"));
            files.discard(failed);
            final BodyFiles.Written retry = files.write(null, body("retried"));
            assertThat(retry.response()).isEqualTo(failed.response());
            files.commit(retry);
            assertThat(readOpen(retry.response())).isEqualTo("retried");
        }
        assertThat(frames(Files.readAllBytes(sealedFiles().getFirst()))).isEqualTo(2);
    }

    @Test
    void onlyTheLastWriteCanBeDiscarded() throws IOException
    {
        try (BodyFiles files = bodyFiles(16))
        {
            final BodyFiles.Written first = files.write(null, body("first"));
            files.commit(first);
            files.commit(files.write(null, body("second")));
            assertThatThrownBy(() -> files.discard(first)).isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * A pointer never leaves its file: after a roll, a repeated body is stored again in the new
     * file.
     */
    @Test
    void deduplicationIsScopedToTheCurrentFile() throws IOException
    {
        final byte[] large = new byte[(int) SealedFileWriter.MIN_ROLLOVER_SIZE];
        new Random(1).nextBytes(large);
        final BodyFiles.Written before;
        final BodyFiles.Written after;
        try (BodyFiles files = bodyFiles(16))
        {
            before = files.write(null, body("repeated"));
            files.commit(before);
            files.commit(files.write(null, List.of(ByteBuffer.wrap(large))));
            after = files.write(null, body("repeated"));
            files.commit(after);
        }
        assertThat(after.response().file()).isNotEqualTo(before.response().file());
        assertThat(after.response().offset()).isZero();
        assertThat(read(after.response())).isEqualTo("repeated");
        assertThat(sealedFiles()).hasSize(2);
    }

    /**
     * A crash mid-write leaves a torn last frame; the next start cuts it and seals the rest.
     */
    @Test
    void aTornLastFrameIsCutOnTheNextStart() throws IOException
    {
        // Never closed: the process dies with the file open.
        final BodyFiles crashed = bodyFiles(16);
        final BodyFiles.Written kept = crashed.write(body("whole"), null);
        crashed.commit(kept);
        crashed.commit(crashed.write(body("torn away"), null));
        final Path open = openFile();
        try (FileChannel channel = FileChannel.open(open, StandardOpenOption.WRITE))
        {
            channel.truncate(channel.size() - 3);
        }

        try (BodyFiles ignored = bodyFiles(16))
        {
            final byte[] sealed = Files.readAllBytes(sealedFiles().getFirst());
            assertThat(sealed).hasSize((int) kept.request().length());
            assertThat(read(kept.request(), sealed)).isEqualTo("whole");
        }
    }

    @Test
    void aDamagedFrameEndsRecovery() throws IOException
    {
        final byte[] first = Zstd.compress("first".getBytes(StandardCharsets.UTF_8));
        final byte[] second = new ZstdCompressCtx().setChecksum(true).compress("second body".getBytes(StandardCharsets.UTF_8));
        second[second.length - 1] ^= 0x55; // the content checksum no longer matches
        final Path file = dir.resolve("r7-1-x.bodies.zst.open");
        Files.write(file, concat(first, second, first));
        try (var channel = FileChannel.open(file, StandardOpenOption.READ))
        {
            assertThat(BodyFiles.endOfLastCompleteFrame(channel)).isEqualTo(first.length);
        }
    }

    private BodyFiles bodyFiles(final int dedupEntries) throws IOException
    {
        return new BodyFiles(dir, "r7", SealedFileWriter.MIN_ROLLOVER_SIZE, HOUR, 3, dedupEntries);
    }

    private static List<ByteBuffer> body(final String text)
    {
        // Two fragments, as the journal hands bodies over.
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        final int half = bytes.length / 2;
        return List.of(ByteBuffer.wrap(bytes, 0, half), ByteBuffer.wrap(bytes, half, bytes.length - half));
    }

    private String read(final BodyFiles.BodyRef ref) throws IOException
    {
        return read(ref, Files.readAllBytes(dir.resolve(ref.file())));
    }

    /**
     * Reads at a pointer while its file is still open, as a reader may.
     */
    private String readOpen(final BodyFiles.BodyRef ref) throws IOException
    {
        return read(ref, Files.readAllBytes(dir.resolve(ref.file() + ".open")));
    }

    private static String read(final BodyFiles.BodyRef ref, final byte[] file) throws IOException
    {
        final byte[] frame = Arrays.copyOfRange(file, (int) ref.offset(), (int) (ref.offset() + ref.length()));
        try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(frame)))
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int frames(final byte[] file)
    {
        int count = 0;
        int position = 0;
        while (position < file.length)
        {
            position += (int) Zstd.findFrameCompressedSize(file, position);
            count++;
        }
        return count;
    }

    private static byte[] concat(final byte[]... parts)
    {
        final java.io.ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] part : parts)
        {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private Path openFile() throws IOException
    {
        try (Stream<Path> files = Files.list(dir))
        {
            return files.filter(p -> p.getFileName().toString().endsWith(".bodies.zst.open")).findFirst().orElseThrow();
        }
    }

    private List<Path> sealedFiles() throws IOException
    {
        try (Stream<Path> files = Files.list(dir))
        {
            return files.filter(p -> p.getFileName().toString().endsWith(".bodies.zst")).sorted().toList();
        }
    }
}
