package com.ethlo.r7.undertow.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PathEncoderTest
{
    @Test
    void returnsTheSameInstanceWhenNothingNeedsEncoding()
    {
        final String path = "/api/v1/users/42;v=2/a-b_c.d~e!$&'()*+,=:@";
        assertThat(PathEncoder.encode(path)).isSameAs(path);
    }

    @Test
    void encodesCharactersThatWouldOtherwiseBecomeUriSyntax()
    {
        assertThat(PathEncoder.encode("/users?admin=true#frag")).isEqualTo("/users%3Fadmin=true%23frag");
        assertThat(PathEncoder.encode("/100%.txt")).isEqualTo("/100%25.txt");
        assertThat(PathEncoder.encode("/a%2e%2e/b")).isEqualTo("/a%252e%252e/b");
        assertThat(PathEncoder.encode("/a b")).isEqualTo("/a%20b");
        assertThat(PathEncoder.encode("/a\"<>\\^`{|}")).isEqualTo("/a%22%3C%3E%5C%5E%60%7B%7C%7D");
    }

    @Test
    void encodesNonAsciiAsUtf8()
    {
        assertThat(PathEncoder.encode("/café/ø")).isEqualTo("/caf%C3%A9/%C3%B8");
        assertThat(PathEncoder.encode("/😀")).isEqualTo("/%F0%9F%98%80");
    }

    @Test
    void keepsTheUnencodedPrefixIntact()
    {
        assertThat(PathEncoder.encode("/some/long/prefix/then space")).isEqualTo("/some/long/prefix/then%20space");
    }
}
