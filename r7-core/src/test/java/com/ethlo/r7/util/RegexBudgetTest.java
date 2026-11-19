package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RegexBudgetTest
{
    /**
     * A repeated group containing .* backtracks super-linearly on a near-miss: without a budget
     * this match runs for longer than any request should. (Recent JDKs memoise the textbook
     * ^(a+)+$, but not repetition counts like this, nor backreferences.)
     */
    @Test
    @Timeout(10)
    void aCatastrophicPatternFailsInsteadOfHanging()
    {
        final Pattern evil = Pattern.compile("^(.*a){12}$");
        final String input = "a".repeat(40) + "!";

        // Warm-up, so JIT compilation does not count against the bound below
        assertThatThrownBy(() -> RegexBudget.matcher(evil, input).matches())
                .isInstanceOf(RegexBudget.RegexBudgetExceededException.class)
                .hasMessageContaining("budget")
                .hasMessageNotContaining("(.*a)");

        for (int i = 0; i < 10; i++)
        {
            final long start = System.nanoTime();
            assertThatThrownBy(() -> RegexBudget.matcher(evil, input).matches())
                    .isInstanceOf(RegexBudget.RegexBudgetExceededException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
        }
    }

    @Test
    @Timeout(10)
    void aCatastrophicBackreferenceFailsInsteadOfHanging()
    {
        assertThatThrownBy(() -> RegexBudget.matcher(Pattern.compile("^(\\w+)*\\1$"), "a".repeat(30) + "!").matches())
                .isInstanceOf(RegexBudget.RegexBudgetExceededException.class);
    }

    /**
     * Linear patterns on the largest input the listener admits stay far inside the budget:
     * about one read per character, against a budget of a million.
     */
    @Test
    void ordinaryPatternsOnLargeInputsAreUnaffected()
    {
        final String path = "/api/" + "segment/".repeat(1000) + "end";

        assertThat(RegexBudget.matcher(Pattern.compile("^/api/(.*)$"), path).matches()).isTrue();
        assertThat(RegexBudget.matcher(Pattern.compile("[0-9]+"), path).find()).isFalse();
        assertThat(RegexBudget.matcher(Pattern.compile("(?i)^/API/.*END$"), path).matches()).isTrue();
    }

    /**
     * RewritePath and TemplateRedirect replace through the matcher; the budget must not change
     * what they produce.
     */
    @Test
    void replacementWorksThroughTheBudget()
    {
        final Matcher matcher = RegexBudget.matcher(Pattern.compile("^/api/v1/(.*)$"), "/api/v1/users/42");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.replaceFirst("/v2/$1")).isEqualTo("/v2/users/42");
        assertThat(RegexBudget.matcher(Pattern.compile("/+"), "/a//b///c").replaceAll("/")).isEqualTo("/a/b/c");
    }
}
