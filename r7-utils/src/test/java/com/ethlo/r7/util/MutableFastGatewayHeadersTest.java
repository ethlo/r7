package com.ethlo.r7.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.InvalidTextValueException;

/**
 * The standalone container holds the same line as the Undertow-backed view: a value that
 * could split a message is refused when set, not discovered on the wire.
 */
class MutableFastGatewayHeadersTest
{
    @Test
    void controlCharactersAreRefusedAndTabIsAllowed()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();

        assertThrows(InvalidTextValueException.class, () -> headers.set("X-Subject", "a\r\nInjected: yes"));
        assertThrows(InvalidTextValueException.class, () -> headers.add("X-Subject", "a\nb"));
        assertThrows(InvalidTextValueException.class, () -> headers.set("X-Subject", List.of("a\u0000b")));
        assertThrows(InvalidTextValueException.class, () -> headers.set("X-Sub\nject", "fine"));
        assertDoesNotThrow(() -> headers.set("X-Subject", "a\tb"));
    }
}
