package com.ethlo.r7.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * What holds for every snippet, whichever file it is: it parses, it names a file some module
 * checks, and it uses no environment variable the docs tests cannot fill in. The modules that
 * own each file load the snippets with their real loader.
 */
class DocSnippetsTest
{
    private static final Pattern REQUIRED_VARIABLE = Pattern.compile("(?<!\\$)\\$\\{([^}:]+)}");

    static List<DocSnippet> snippets()
    {
        return DocSnippets.all();
    }

    @Test
    void findsTheSnippets()
    {
        assertThat(DocSnippets.of(null)).isNotEmpty();
        assertThat(DocSnippets.of(DocSnippets.ROUTES)).isNotEmpty();
        assertThat(DocSnippets.of(DocSnippets.SERVER)).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snippets")
    void parses(final DocSnippet snippet)
    {
        assertThat(new YAMLMapper().readTree(snippet.yaml())).as(snippet.toString()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snippets")
    void namesAFileThatIsChecked(final DocSnippet snippet)
    {
        if (snippet.title() != null)
        {
            assertThat(DocSnippets.FILES).as(snippet + " is titled with a file no docs test checks").contains(snippet.title());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snippets")
    void hasAValueForEveryRequiredVariable(final DocSnippet snippet)
    {
        final Matcher matcher = REQUIRED_VARIABLE.matcher(snippet.yaml());
        while (matcher.find())
        {
            assertThat(DocSnippets.VARIABLES).as(snippet + " uses ${" + matcher.group(1) + "}; give it a value in DocSnippets.VARIABLES")
                    .containsKey(matcher.group(1));
        }
    }
}
