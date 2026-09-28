package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StaticContentHiddenFilesTest
{
    @ParameterizedTest
    @ValueSource(strings = {"/.env", "/.git/config", "/assets/.htpasswd", ".env", "/a/.b/c", "/.well-known-x/y", "/.well"})
    void hiddenPathsAreDetected(final String path)
    {
        assertThat(StaticContentFactory.StaticServeRequest.isHidden(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "", "/index.html", "/a/b.c", "/.well-known/acme-challenge/token", "/.well-known", "/a..b", "/a/b."})
    void ordinaryPathsAndWellKnownAreServed(final String path)
    {
        assertThat(StaticContentFactory.StaticServeRequest.isHidden(path)).isFalse();
    }
}
