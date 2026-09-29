package com.ethlo.r7.tailer.jsonld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.r7f.JournalFiles;

/**
 * The JSON tailer's output file carries the same request/response data the binary journals
 * do, so it must not be left more permissive than a journal segment is (see
 * {@link JournalFiles}).
 */
class JsonLdTailerMainTest
{
    @TempDir
    Path dir;

    @Test
    void outputFileIsCreatedOwnerReadWriteGroupReadOnly() throws Exception
    {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));

        final Path outputPath = dir.resolve("out.jsonld");

        try (OutputStream out = JsonLdTailerMain.openOutputFile(outputPath))
        {
            out.write('{');
        }

        final Set<PosixFilePermission> actual = Files.getPosixFilePermissions(outputPath);
        assertThat(JournalFiles.FILE_PERMISSIONS)
                .as("no wider than a journal segment's own permissions - actual: %s", actual)
                .containsAll(actual);
    }

    @Test
    void parentDirectoriesAreCreatedWithoutAccessForOthers() throws Exception
    {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));

        final Path outputPath = dir.resolve("nested/output/out.jsonld");

        try (OutputStream out = JsonLdTailerMain.openOutputFile(outputPath))
        {
            out.write('{');
        }

        assertThat(Files.exists(outputPath)).isTrue();
    }
}
