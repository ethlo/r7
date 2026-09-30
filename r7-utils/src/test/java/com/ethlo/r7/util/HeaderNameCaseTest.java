package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.MultiAttributes;

/**
 * HTTP header names are case-insensitive (RFC 9110), and the Undertow-backed view already
 * treats them that way. The standalone containers - what short-circuit responses carry and what
 * a journal consumer reads - must answer the same, or a filter or tailer that asks for
 * {@code content-type} misses a header written as {@code Content-Type}.
 */
class HeaderNameCaseTest
{
    @Test
    void lookupsIgnoreCase()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("Content-Type", "text/plain");
        headers.add("X-Multi", "a");
        headers.add("x-multi", "b");

        assertLookupsIgnoreCase(headers);
    }

    @Test
    void packedAndIndexedContainersAgree()
    {
        final MutableFastGatewayHeaders source = new MutableFastGatewayHeaders();
        source.add("Content-Type", "text/plain");
        source.add("X-Multi", "a");
        source.add("x-multi", "b");

        final IndexedGatewayHeaders indexed = new IndexedGatewayHeaders(0);
        source.forEach(indexed, IndexedGatewayHeaders::append);

        assertLookupsIgnoreCase(PackedGatewayHeaders.pack(source));
        assertLookupsIgnoreCase(indexed);
    }

    @Test
    void setReplacesEveryValueWhateverTheirCasing()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("Accept", "text/html");
        headers.add("X-Other", "kept");
        headers.add("ACCEPT", "application/json");

        headers.set("accept", "*/*");

        assertThat(entries(headers)).containsExactly("Accept=*/*", "X-Other=kept");
    }

    @Test
    void setWithValuesReplacesEveryValueWhateverTheirCasing()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("Accept", "text/html");
        headers.add("ACCEPT", "application/json");

        headers.set("accept", List.of("a", "b"));

        assertThat(entries(headers)).containsExactly("accept=a", "accept=b");
    }

    @Test
    void removeDropsEveryCasing()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("Cookie", "a=1");
        headers.add("X-Other", "kept");
        headers.add("COOKIE", "b=2");
        headers.add("cookie", "c=3");

        headers.remove("cOoKiE");

        assertThat(entries(headers)).containsExactly("X-Other=kept");
        assertThat(headers.contains("Cookie")).isFalse();
    }

    @Test
    void storedCasingIsPreservedForIteration()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("X-Request-ID", "1");
        headers.set("x-request-id", "2");
        headers.add("x-request-id", "3");

        assertThat(entries(headers)).containsExactly("X-Request-ID=2", "x-request-id=3");
        assertThat(entries(PackedGatewayHeaders.pack(headers))).containsExactly("X-Request-ID=2", "x-request-id=3");
    }

    /**
     * Under a Turkish locale {@code "I".toLowerCase()} is a dotless {@code ı}, which is the bug
     * a locale-sensitive fold would introduce. The fold must not depend on the default locale.
     */
    @Test
    void foldingIsIndependentOfTheDefaultLocale()
    {
        final Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try
        {
            final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
            headers.add("IF-MATCH", "\"v1\"");

            assertThat(headers.getFirst("if-match")).isEqualTo("\"v1\"");
            assertThat(PackedGatewayHeaders.pack(headers).getFirst("if-match")).isEqualTo("\"v1\"");
        }
        finally
        {
            Locale.setDefault(previous);
        }
    }

    /**
     * Only ASCII letters fold. {@link String#equalsIgnoreCase} would equate the Kelvin sign with
     * {@code k}, matching a name no header on the wire can carry.
     */
    @Test
    void onlyAsciiLettersFold()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("X-Key", "v");

        assertThat(headers.getFirst("X-Key")).isNull();
        assertThat(PackedGatewayHeaders.pack(headers).getFirst("X-Key")).isNull();
        assertThat(AsciiCase.equalsIgnoreCase("x-key", "X-KEY")).isTrue();
        assertThat(AsciiCase.equalsIgnoreCase("x-key", "x-keys")).isFalse();
        assertThat(AsciiCase.equalsIgnoreCase("x-key", "x_key")).isFalse();
    }

    /**
     * Exchange attributes share the storage base with headers but are not headers: journaled
     * keys like {@code gateway.route.id} stay exact-match.
     */
    @Test
    void attributesStayCaseSensitive()
    {
        final FastGatewayAttributes attributes = new FastGatewayAttributes();
        attributes.set("gateway.route.id", "a");
        attributes.set("Gateway.Route.Id", "b");

        assertThat(attributes.getFirst("gateway.route.id")).isEqualTo("a");
        assertThat(attributes.getFirst("Gateway.Route.Id")).isEqualTo("b");
        assertThat(attributes.getFirst("GATEWAY.ROUTE.ID")).isNull();

        attributes.remove("GATEWAY.ROUTE.ID");
        assertThat(entries(attributes)).containsExactly("gateway.route.id=a", "Gateway.Route.Id=b");
    }

    private static void assertLookupsIgnoreCase(final GatewayHeaders headers)
    {
        for (final String name : List.of("Content-Type", "content-type", "CONTENT-TYPE", "cOnTeNt-TyPe"))
        {
            assertThat(headers.getFirst(name)).as("getFirst(%s)", name).isEqualTo("text/plain");
            assertThat(headers.contains(name)).as("contains(%s)", name).isTrue();
        }
        for (final String name : List.of("X-Multi", "x-multi", "X-MULTI"))
        {
            assertThat(values(headers.getAll(name))).as("getAll(%s)", name).containsExactly("a", "b");
        }
        assertThat(headers.getFirst("Content-Typ")).isNull();
        assertThat(headers.contains("Content-Types")).isFalse();
        assertThat(values(headers.getAll("X-Mult"))).isEmpty();
    }

    private static List<String> values(final Iterable<String> iterable)
    {
        final List<String> out = new ArrayList<>();
        iterable.forEach(out::add);
        return out;
    }

    private static List<String> entries(final MultiAttributes attributes)
    {
        final List<String> out = new ArrayList<>();
        attributes.forEach((name, value) -> out.add(name + "=" + value));
        return out;
    }
}
