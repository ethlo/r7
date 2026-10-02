package com.ethlo.r7.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.ethlo.r7.docs.DocSnippet;
import com.ethlo.r7.docs.DocSnippets;

/**
 * Every reaper.yaml snippet in the docs loads and validates the way ReaperMain loads it at startup.
 */
class DocsConfigSnippetsTest
{
    @TempDir
    Path dir;

    static List<DocSnippet> snippets()
    {
        return DocSnippets.of(DocSnippets.REAPER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snippets")
    void loads(final DocSnippet snippet)
    {
        try
        {
            assertThat(ReaperMain.loadConfig(snippet.writeTo(this.dir, DocSnippets.REAPER))).isNotNull();
        }
        catch (final RuntimeException e)
        {
            throw new AssertionError(snippet + " does not load: " + e.getMessage(), e);
        }
    }
}
