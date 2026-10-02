package com.ethlo.r7.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One YAML snippet from the docs.
 *
 * @param source the Markdown file, relative to the repository root
 * @param line   the line of the opening fence, so a failure points at the snippet
 * @param title  the file the snippet is, from its fence's {@code title="..."}, or {@code null}
 * @param yaml   the snippet's text
 */
public record DocSnippet(String source, int line, String title, String yaml)
{
    /**
     * Writes the snippet to {@code dir} under {@code name}, for loaders that read a file.
     */
    public Path writeTo(final Path dir, final String name)
    {
        try
        {
            return Files.writeString(dir.resolve(name), this.yaml);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString()
    {
        return this.source + ":" + this.line;
    }
}
