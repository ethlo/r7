package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.r7f.JournalFiles;
import com.ethlo.r7.r7f.R7fJournalProvider;

/**
 * Journal files hold request data; whatever the process umask, they must not be readable by
 * everyone. Asserted as upper bounds, since a stricter umask may narrow them further.
 */
class JournalFilePermissionsTest
{
    @TempDir
    Path dir;

    private static void assertNoWiderThan(final Path path, final Set<PosixFilePermission> allowed) throws IOException
    {
        assertThat(allowed).containsAll(Files.getPosixFilePermissions(path));
    }

    @Test
    void segmentsAndTheSequenceMarkerAreCreatedOwnerReadWriteGroupRead() throws Exception
    {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));

        final R7fJournalProvider provider = new R7fJournalProvider(dir, 0, 1024 * 1024, false);
        try
        {
            provider.getNextSegment();
        }
        finally
        {
            provider.close();
        }

        final List<Path> files;
        try (Stream<Path> listing = Files.list(dir))
        {
            files = listing.filter(Files::isRegularFile).toList();
        }
        assertThat(files).isNotEmpty();
        for (final Path file : files)
        {
            assertNoWiderThan(file, JournalFiles.FILE_PERMISSIONS);
        }
    }

    @Test
    void theWorkDirectoryIsCreatedWithoutAccessForOthers() throws Exception
    {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));

        final Path workDir = JournalFiles.createDirectories(dir.resolve("a/work"));

        assertNoWiderThan(workDir, JournalFiles.DIRECTORY_PERMISSIONS);
        assertNoWiderThan(workDir.getParent(), JournalFiles.DIRECTORY_PERMISSIONS);
    }

    /**
     * A marker temp file an older version left behind with a wider mode must not pass that mode
     * on to the marker.
     */
    @Test
    void aLeftoverWideMarkerTempFileDoesNotWidenTheMarker() throws Exception
    {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));

        final Path leftover = dir.resolve("shard-0.seq.tmp");
        Files.writeString(leftover, "0");
        Files.setPosixFilePermissions(leftover, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));

        final R7fJournalProvider provider = new R7fJournalProvider(dir, 0, 1024 * 1024, false);
        try
        {
            provider.getNextSegment();
        }
        finally
        {
            provider.close();
        }

        assertNoWiderThan(dir.resolve("shard-0.seq"), JournalFiles.FILE_PERMISSIONS);
    }

    /**
     * An empty work_dir passes validation and means the current directory, where a marker path
     * has no parent. The provider therefore picks the file system from its own directory, which
     * is never null - and that directory, even when empty, must be usable for the attributes.
     */
    @Test
    void attributesAreAvailableForAnEmptyRelativeWorkDir()
    {
        final Path relative = java.nio.file.Paths.get("");
        assertThat(relative.resolve("shard-0.seq").getParent()).isNull();
        assertThat(JournalFiles.fileAttributes(relative)).isNotNull();
    }

    @Test
    void thePermissionSetsCannotBeWidened()
    {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JournalFiles.FILE_PERMISSIONS.add(PosixFilePermission.OTHERS_READ))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JournalFiles.DIRECTORY_PERMISSIONS.add(PosixFilePermission.OTHERS_READ))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
