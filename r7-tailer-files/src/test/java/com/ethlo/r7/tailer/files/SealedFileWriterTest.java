package com.ethlo.r7.tailer.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.r7f.JournalFiles;

class SealedFileWriterTest
{
    private static final long MIN = SealedFileWriter.MIN_ROLLOVER_SIZE;
    private static final long HOUR = 3_600_000L;

    @TempDir
    Path dir;

    /**
     * The records before a failed append were delivered, and the tailer checkpointed past them:
     * they must survive the failure. Only the failed append is cut back, and its retry lands
     * right after them in the same file.
     */
    @Test
    void aFailedAppendIsCutBackAndKeepsTheRecordsBeforeIt() throws IOException
    {
        final FailingChannel[] failing = new FailingChannel[1];
        try (SealedFileWriter files = new SealedFileWriter(dir, "r7", MIN, HOUR, new Lines(), channel ->
        {
            failing[0] = new FailingChannel(channel);
            return failing[0];
        }))
        {
            files.append(bytes("one\n"));
            files.append(bytes("two\n"));

            failing[0].failAfter = 3;
            assertThatThrownBy(() -> files.append(bytes("three\n"))).isInstanceOf(IOException.class);
            assertThat(Files.readString(only(".lines.open"))).as("cut back to before the failed append").isEqualTo("one\ntwo\n");

            failing[0].failAfter = -1;
            assertThat(files.append(bytes("three\n"))).as("the retry starts where the failed append did").isEqualTo(8);
        }

        assertThat(Files.readString(only(".lines"))).isEqualTo("one\ntwo\nthree\n");
    }

    /**
     * A caller whose unit of work spans more than this file takes an append back when a later
     * step fails; the retry lands where it began.
     */
    @Test
    void aDiscardedAppendIsTakenBack() throws IOException
    {
        try (SealedFileWriter files = new SealedFileWriter(dir, "r7", MIN, HOUR, new Lines()))
        {
            files.append(bytes("one\n"));
            final long two = files.append(bytes("two\n"));
            files.discardFrom(two);
            assertThat(files.size()).isEqualTo(two);
            assertThat(files.append(bytes("three\n"))).isEqualTo(two);
            assertThatThrownBy(() -> files.discardFrom(files.size() + 1)).isInstanceOf(IllegalStateException.class);
        }

        assertThat(Files.readString(only(".lines"))).isEqualTo("one\nthree\n");
    }

    /**
     * A header write that fails, and cannot even be cut back, reports the write's own failure
     * and leaves no file behind; the next append opens a fresh one.
     */
    @Test
    void aFailedHeaderWriteThatCannotBeCutBackReportsTheWriteFailure() throws IOException
    {
        final Lines withHeader = new Lines()
        {
            @Override
            public void opened(final SealedFileWriter writer, final String fileName) throws IOException
            {
                writer.append(bytes("# header\n"));
            }
        };
        final boolean[] broken = {true};
        try (SealedFileWriter files = new SealedFileWriter(dir, "r7", MIN, HOUR, withHeader, channel ->
        {
            final FailingChannel failing = new FailingChannel(channel);
            if (broken[0])
            {
                failing.failAfter = 2;
                failing.failTruncate = true;
            }
            return failing;
        }))
        {
            assertThatThrownBy(() -> files.append(bytes("one\n")))
                    .isInstanceOf(IOException.class)
                    .hasMessage("No space left on device");
            assertThat(files("")).isEmpty();

            broken[0] = false;
            files.append(bytes("one\n"));
        }
        assertThat(Files.readString(only(".lines"))).isEqualTo("# header\none\n");
    }

    @Test
    void aFileHoldingOnlyItsHeaderIsNotSealed() throws IOException
    {
        final Lines withHeader = new Lines()
        {
            @Override
            public void opened(final SealedFileWriter writer, final String fileName) throws IOException
            {
                writer.append(bytes("# " + fileName + "\n"));
            }
        };
        try (SealedFileWriter files = new SealedFileWriter(dir, "r7", MIN, 1, withHeader))
        {
            files.ensureOpen();
            sleepPastAgeLimit();
            files.rollIfStale();
            assertThat(only(".lines.open")).as("a header alone is not worth sealing on age").exists();
        }
        assertThat(files("")).as("nor on close").isEmpty();
    }

    @Test
    void filesAreNoMoreReadableThanAJournalSegment() throws IOException
    {
        try (SealedFileWriter files = new SealedFileWriter(dir.resolve("out"), "r7", MIN, HOUR, new Lines()))
        {
            files.append(bytes("x\n"));
        }
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix"))
        {
            try (Stream<Path> s = Files.list(dir.resolve("out")))
            {
                final Set<PosixFilePermission> actual = Files.getPosixFilePermissions(s.findFirst().orElseThrow());
                assertThat(JournalFiles.FILE_PERMISSIONS).containsAll(actual);
            }
        }
    }

    private static ByteBuffer bytes(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    private Path only(final String suffix) throws IOException
    {
        final List<Path> matching = files(suffix);
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private List<Path> files(final String suffix) throws IOException
    {
        try (Stream<Path> s = Files.list(dir))
        {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
        }
    }

    private static void sleepPastAgeLimit()
    {
        try
        {
            Thread.sleep(5);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static class Lines implements SealedFileWriter.Format
    {
        @Override
        public String extension()
        {
            return ".lines";
        }

        @Override
        public long endOfLastCompleteRecord(final FileChannel file) throws IOException
        {
            return file.size();
        }
    }

    /**
     * Lets {@code failAfter} bytes of the next write through, then fails it, as a full disk
     * does partway through a write.
     */
    private static final class FailingChannel extends FileChannel
    {
        private final FileChannel delegate;
        int failAfter = -1;
        boolean failTruncate;

        FailingChannel(final FileChannel delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public int write(final ByteBuffer src) throws IOException
        {
            if (failAfter >= 0)
            {
                if (failAfter == 0 || !src.hasRemaining())
                {
                    throw new IOException("No space left on device");
                }
                final ByteBuffer part = src.slice(src.position(), Math.min(failAfter, src.remaining()));
                final int written = delegate.write(part);
                src.position(src.position() + written);
                failAfter = 0;
                return written;
            }
            return delegate.write(src);
        }

        @Override
        public int read(final ByteBuffer dst) throws IOException
        {
            return delegate.read(dst);
        }

        @Override
        public long read(final ByteBuffer[] dsts, final int offset, final int length) throws IOException
        {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public long write(final ByteBuffer[] srcs, final int offset, final int length) throws IOException
        {
            return delegate.write(srcs, offset, length);
        }

        @Override
        public long position() throws IOException
        {
            return delegate.position();
        }

        @Override
        public FileChannel position(final long newPosition) throws IOException
        {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException
        {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(final long size) throws IOException
        {
            if (failTruncate)
            {
                throw new IOException("Input/output error");
            }
            delegate.truncate(size);
            return this;
        }

        @Override
        public void force(final boolean metaData) throws IOException
        {
            delegate.force(metaData);
        }

        @Override
        public long transferTo(final long position, final long count, final WritableByteChannel target) throws IOException
        {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(final ReadableByteChannel src, final long position, final long count) throws IOException
        {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public int read(final ByteBuffer dst, final long position) throws IOException
        {
            return delegate.read(dst, position);
        }

        @Override
        public int write(final ByteBuffer src, final long position) throws IOException
        {
            return delegate.write(src, position);
        }

        @Override
        public MappedByteBuffer map(final MapMode mode, final long position, final long size) throws IOException
        {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(final long position, final long size, final boolean shared) throws IOException
        {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(final long position, final long size, final boolean shared) throws IOException
        {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException
        {
            delegate.close();
        }
    }
}
