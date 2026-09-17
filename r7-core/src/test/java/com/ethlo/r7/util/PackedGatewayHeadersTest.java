package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayHeaders;

/**
 * The packed container replaces a String-backed one inside an exchange a consumer later reads,
 * so what it must get right is not compactness but being indistinguishable: same order, same
 * multiplicity, same lookups, same bytes back.
 */
class PackedGatewayHeadersTest
{
    @Test
    void roundTripsNamesAndValuesInArrivalOrder()
    {
        final GatewayHeaders packed = PackedGatewayHeaders.pack(source(
                "user-agent", "Mozilla/5.0",
                "accept", "text/html",
                "cookie", "session=abc"));

        assertThat(entries(packed)).containsExactly(
                "user-agent=Mozilla/5.0",
                "accept=text/html",
                "cookie=session=abc");
    }

    /**
     * Order and multiplicity are part of the record being audited, so a repeated header must
     * come back repeated, in the order it arrived.
     */
    @Test
    void keepsEveryValueOfARepeatedHeader()
    {
        final GatewayHeaders packed = PackedGatewayHeaders.pack(source(
                "set-cookie", "a=1",
                "set-cookie", "b=2",
                "set-cookie", "c=3"));

        assertThat(entries(packed)).containsExactly(
                "set-cookie=a=1",
                "set-cookie=b=2",
                "set-cookie=c=3");
        assertThat(packed.getAll("set-cookie")).containsExactly("a=1", "b=2", "c=3");
        assertThat(packed.getFirst("set-cookie")).isEqualTo("a=1");
    }

    @Test
    void lookupsMatchTheStringBackedContainerItReplaces()
    {
        final GatewayHeaders original = source("user-agent", "curl/8.0", "accept", "*/*");
        final GatewayHeaders packed = PackedGatewayHeaders.pack(original);

        assertThat(packed.getFirst("user-agent")).isEqualTo(original.getFirst("user-agent"));
        assertThat(packed.getFirst("absent")).isEqualTo(original.getFirst("absent"));
        assertThat(packed.getFirst(null)).isNull();
        assertThat(packed.contains("accept")).isTrue();
        assertThat(packed.contains("accep")).isFalse();
        assertThat(packed.contains("user-agentx")).isFalse();
        assertThat(packed.getAll("absent")).isEmpty();
    }

    /**
     * Exact match, as the container it replaces used. Case-insensitive lookup would be a
     * behaviour change smuggled in with a representation change.
     */
    @Test
    void lookupIsCaseSensitiveJustAsBefore()
    {
        final GatewayHeaders original = source("User-Agent", "curl/8.0");
        final GatewayHeaders packed = PackedGatewayHeaders.pack(original);

        assertThat(packed.getFirst("user-agent")).isEqualTo(original.getFirst("user-agent"));
        assertThat(packed.getFirst("User-Agent")).isEqualTo("curl/8.0");
    }

    /**
     * Header values are ISO-8859-1 on the wire and in the journal, so every byte in that range
     * has to survive the round trip rather than being mangled into a replacement character.
     */
    @Test
    void preservesEveryLatin1Character()
    {
        final StringBuilder sb = new StringBuilder();
        for (int c = 0; c <= 0xFF; c++)
        {
            sb.append((char) c);
        }
        final String value = sb.toString();

        final GatewayHeaders packed = PackedGatewayHeaders.pack(source("x-binary", value));
        assertThat(packed.getFirst("x-binary")).isEqualTo(value);
    }

    /**
     * Lengths are varint-encoded, so a value crossing the single-byte boundary is where a
     * framing mistake would show up.
     */
    @Test
    void handlesValuesAcrossTheVarIntLengthBoundary()
    {
        for (final int length : new int[]{0, 1, 126, 127, 128, 129, 16383, 16384})
        {
            final String value = "v".repeat(length);
            final GatewayHeaders packed = PackedGatewayHeaders.pack(source("x-len", value));
            assertThat(packed.getFirst("x-len")).as("length %d", length).isEqualTo(value);
            assertThat(entries(packed)).hasSize(1);
        }
    }

    @Test
    void skipsEntriesThatCannotBeRepresented()
    {
        final MutableFastGatewayHeaders withNulls = new MutableFastGatewayHeaders()
        {
            @Override
            public <S> int forEach(final S state, final com.ethlo.r7.api.StatefulEntryConsumer<S> consumer)
            {
                consumer.accept(state, "good", "kept");
                consumer.accept(state, null, "dropped");
                consumer.accept(state, "also-dropped", null);
                return 3;
            }
        };

        assertThat(entries(PackedGatewayHeaders.pack(withNulls))).containsExactly("good=kept");
    }

    @Test
    void emptyAndNullInputsBehaveAsBefore()
    {
        assertThat(PackedGatewayHeaders.pack(null)).isNull();
        assertThat(entries(PackedGatewayHeaders.pack(new MutableFastGatewayHeaders()))).isEmpty();
        assertThat(entries(PackedGatewayHeaders.empty())).isEmpty();
        assertThat(PackedGatewayHeaders.empty().getFirst("anything")).isNull();
    }

    /**
     * Equality is what lets a holder keep one copy where two sets are the same. It has to mean
     * "same names and values, same order, same count" and nothing looser.
     */
    @Test
    void equalityIsSequenceEquality()
    {
        final GatewayHeaders a = PackedGatewayHeaders.pack(source("x", "1", "y", "2"));
        final GatewayHeaders same = PackedGatewayHeaders.pack(source("x", "1", "y", "2"));
        final GatewayHeaders reordered = PackedGatewayHeaders.pack(source("y", "2", "x", "1"));
        final GatewayHeaders extra = PackedGatewayHeaders.pack(source("x", "1", "y", "2", "z", "3"));
        final GatewayHeaders differentValue = PackedGatewayHeaders.pack(source("x", "1", "y", "9"));

        assertThat(a).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(a).isNotEqualTo(reordered);
        assertThat(a).isNotEqualTo(extra);
        assertThat(a).isNotEqualTo(differentValue);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isEqualTo(a);
    }

    /**
     * A name/value boundary must not be able to move without changing the bytes, or two
     * different header sets could compare equal and one would be dropped for the other.
     */
    @Test
    void aShiftedNameValueBoundaryIsNotEqual()
    {
        assertThat(PackedGatewayHeaders.pack(source("ab", "cd")))
                .isNotEqualTo(PackedGatewayHeaders.pack(source("abc", "d")));
    }

    @Test
    void statefulTraversalSeesTheSameEntries()
    {
        final GatewayHeaders packed = PackedGatewayHeaders.pack(source("a", "1", "b", "2"));

        final List<String> collected = new ArrayList<>();
        final int count = packed.forEach(collected, (state, name, value) -> state.add(name + "=" + value));

        assertThat(collected).containsExactly("a=1", "b=2");
        assertThat(count).isEqualTo(2);
    }

    private static GatewayHeaders source(final String... namesAndValues)
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        for (int i = 0; i < namesAndValues.length; i += 2)
        {
            headers.add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return headers;
    }

    private static List<String> entries(final GatewayHeaders headers)
    {
        final List<String> out = new ArrayList<>();
        final int count = headers.forEach((name, value) -> out.add(name + "=" + value));
        assertThat(count).isEqualTo(out.size());
        return out;
    }
}
