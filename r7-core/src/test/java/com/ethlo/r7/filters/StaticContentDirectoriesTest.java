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
 * What loading refuses in a {@code StaticContent}'s {@code directories} and {@code fallback}:
 * every directory must exist, the list must name one, and none twice; the fallback must be a
 * file one of them has.
 */
class StaticContentDirectoriesTest
{
    @TempDir
    Path dir;

    private static List<String> errors(final List<Path> directories)
    {
        return errors(directories, null);
    }

    private static List<String> errors(final List<Path> directories, final String fallback)
    {
        final ValidationResult result = new ValidationResult();
        new StaticContentFactory.Config(directories, null, null, fallback).validate(result);
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
    }

    @Test
    void theFallbackMustBeAFileADirectoryHas() throws IOException
    {
        final Path a = Files.createDirectories(this.dir.resolve("a"));
        final Path b = Files.createDirectories(this.dir.resolve("b"));
        Files.writeString(b.resolve("index.html"), "app");
        Files.writeString(b.resolve(".env"), "x");
        assertThat(errors(List.of(a, b), "index.html")).isEmpty();
        assertThat(errors(List.of(a, b), "/index.html")).isEmpty();
        assertThat(errors(List.of(a), "index.html")).singleElement().asString().contains("No directory has index.html");
        assertThat(errors(List.of(a, b), "../index.html")).singleElement().asString().contains("Must name a file under the directories");
        assertThat(errors(List.of(a, b), "app/")).singleElement().asString().contains("Must name a file under the directories");
        assertThat(errors(List.of(a, b), ".env")).singleElement().asString().contains("hidden file");
    }
}
