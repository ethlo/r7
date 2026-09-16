package com.ethlo.r7.undertow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.InvalidTextValueException;
import io.undertow.util.HeaderMap;

/**
 * The header view that filters actually mutate at runtime enforces the same text contract
 * as the standalone containers.
 * <p>
 * This test exists because validating only the containers in {@code r7-utils} left the
 * guarantee true in the unit tests and false in production: filters mutate this class,
 * whose writes went straight to Undertow's {@link HeaderMap}. A value that reaches the
 * journal from here is truncated to latin-1 by the writer, so it has to be refused here.
 */
class UndertowGatewayHeadersValidationTest
{
    private final UndertowGatewayHeaders headers = new UndertowGatewayHeaders(new HeaderMap());

    @Test
    void latin1ValuesAreAccepted()
    {
        assertDoesNotThrow(() -> headers.set("X-Subject", "Ærlig søknad - naïve café"));
        assertEquals("Ærlig søknad - naïve café", headers.getFirst("X-Subject"));
    }

    @Test
    void valuesOutsideLatin1AreRefusedOnSet()
    {
        final InvalidTextValueException e = assertThrows(InvalidTextValueException.class,
                () -> headers.set("X-Subject", "en dash – here"));

        assertEquals("X-Subject", e.getField());
        assertEquals(8, e.getIndex());
    }

    @Test
    void valuesOutsideLatin1AreRefusedOnAdd()
    {
        assertThrows(InvalidTextValueException.class, () -> headers.add("X-Subject", "Ā"));
    }

    @Test
    void namesOutsideLatin1AreRefused()
    {
        assertThrows(InvalidTextValueException.class, () -> headers.set("X-Ünïcode–Name", "fine"));
    }

    @Test
    void nullValuesAreRefused()
    {
        assertThrows(InvalidTextValueException.class, () -> headers.set("X-Subject", (String) null));
        assertThrows(InvalidTextValueException.class, () -> headers.add("X-Subject", null));
    }

    @Test
    void multiValueSetReplacesEveryValue()
    {
        headers.add("Accept", "text/plain");
        headers.add("Accept", "text/html");

        headers.set("Accept", List.of("application/json", "application/xml"));

        assertIterableEquals(List.of("application/json", "application/xml"), headers.getAll("Accept"));
    }

    /**
     * A rejected multi-value set must leave the header map as it was, rather than remove
     * the old values and then fail partway through adding the new ones.
     */
    @Test
    void rejectedMultiValueSetLeavesTheMapUntouched()
    {
        headers.set("X-Subject", "original");

        assertThrows(InvalidTextValueException.class,
                () -> headers.set("X-Subject", List.of("fine", "en dash – here")));

        assertIterableEquals(List.of("original"), headers.getAll("X-Subject"));
    }
}
