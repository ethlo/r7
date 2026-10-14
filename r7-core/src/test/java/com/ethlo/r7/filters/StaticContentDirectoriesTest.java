package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ethlo.r7.validation.ValidationResult;

/**
 * What loading refuses in a {@code StaticContent}'s {@code directories}: every directory must
 * exist, the list must name one, and none twice.
 */
class StaticContentDirectoriesTest
{
    @TempDir
    Path dir;

    private static List<String> errors(final List<Path> directories)
    {
        final ValidationResult result = new ValidationResult();
        new StaticContentFactory.Config(directories, null, null).validate(result);
        return result.getErrors();
    }

    @Test
    void existingDirectoriesAreAccepted() throws IOException
    {
        final Path a = Files.createDirectories(this.dir.resolve("a"));
        final Path b = Files.createDirectories(this.dir.resolve("b"));
        assertThat(errors(List.of(a, b))).isEmpty();
    }

    @Test
    void aMissingEmptyOrRepeatedDirectoryIsRefused() throws IOException
    {
        final Path a = Files.createDirectories(this.dir.resolve("a"));
        assertThat(errors(List.of(a, this.dir.resolve("missing")))).singleElement().asString().contains("not found");
        assertThat(errors(List.of())).singleElement().asString().contains("List at least one directory");
        assertThat(errors(List.of(a, a.resolve(".")))).singleElement().asString().contains("is listed more than once");
        final Path current = Files.createSymbolicLink(this.dir.resolve("current"), a);
        assertThat(errors(List.of(current, a))).singleElement().asString().contains("is listed more than once");
    }
}
