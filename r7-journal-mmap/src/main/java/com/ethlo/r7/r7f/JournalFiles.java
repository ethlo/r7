package com.ethlo.r7.r7f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Permissions for what the gateway writes to its work directory.
 * <p>
 * Journals hold request lines with their query strings, the headers the redaction policy lets
 * through and, at FULL level, bodies. Created with the process defaults they inherit the umask -
 * world-readable under the common {@code 022}. Owner read-write and group read is the narrowest
 * that still lets a sidecar tailer read them when it runs as another user in the same group;
 * everyone else is shut out. Applied when a file or directory is created; the umask can only
 * narrow it further, and existing files are left as they are.
 */
public final class JournalFiles
{
    // Immutable: fromString returns a mutable set, and a public one could be widened by anything
    // in the JVM for every file created after.
    public static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.copyOf(PosixFilePermissions.fromString("rw-r-----"));
    public static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.copyOf(PosixFilePermissions.fromString("rwxr-x---"));

    private static final FileAttribute<?>[] NONE = new FileAttribute<?>[0];

    private JournalFiles()
    {
    }

    /**
     * @return the attributes to create a journal file in {@code directory} with, or none on a file
     * system without POSIX permissions
     */
    public static FileAttribute<?>[] fileAttributes(final Path directory)
    {
        return isPosix(directory) ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)} : NONE;
    }

    /**
     * Creates {@code directory} and any missing parents with {@link #DIRECTORY_PERMISSIONS}.
     */
    public static Path createDirectories(final Path directory) throws IOException
    {
        return isPosix(directory)
                ? Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
                : Files.createDirectories(directory);
    }

    private static boolean isPosix(final Path path)
    {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
