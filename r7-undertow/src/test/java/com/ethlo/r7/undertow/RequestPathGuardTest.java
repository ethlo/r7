package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RequestPathGuardTest
{
    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "/public",
            "/public/",
            "/public/a/b.txt",
            "/public//a",
            "/.well-known/acme",
            "/a/.hidden",
            "/a/...",
            "/a/..b",
            "/a/b..",
            "/a;jsessionid=1/b",
            "/100%.txt",
            "/a%20b",
            "/%41",
            "/a/%2",
            "/a/%",
            "/café"
    })
    void acceptsUnambiguousPaths(final String path)
    {
        assertThat(RequestPathGuard.check(path)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/public/../admin        | DOT_SEGMENT",
            "/public/./admin         | DOT_SEGMENT",
            "/..                     | DOT_SEGMENT",
            "/.                      | DOT_SEGMENT",
            "/public/..              | DOT_SEGMENT",
            "/public/..;/admin       | DOT_SEGMENT",
            "/public/..;x=1/admin    | DOT_SEGMENT",
            "/public/.;/admin        | DOT_SEGMENT",
            "..                      | DOT_SEGMENT",
            "/public/..\\admin       | BACKSLASH",
            "/public\\admin          | BACKSLASH",
            "/public/%2e%2e/admin    | RESIDUAL_ENCODING",
            "/public/%2E/admin       | RESIDUAL_ENCODING",
            "/public/..%2fadmin      | RESIDUAL_ENCODING",
            "/public/..%2Fadmin      | RESIDUAL_ENCODING",
            "/public/..%5cadmin      | RESIDUAL_ENCODING",
            "/public/%25%32%65       | RESIDUAL_ENCODING"
    })
    void rejectsAmbiguousPaths(final String path, final RequestPathGuard.Violation expected)
    {
        assertThat(RequestPathGuard.check(path.strip())).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a\u0000b", "/a\nb", "/a\rb", "/a\tb", "/a\u007fb"})
    void rejectsControlCharacters(final String path)
    {
        assertThat(RequestPathGuard.check(path)).isEqualTo(RequestPathGuard.Violation.CONTROL_CHARACTER);
    }
}
