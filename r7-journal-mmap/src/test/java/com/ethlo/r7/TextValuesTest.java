package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.InvalidTextValueException;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.TextValues;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * The gateway refuses header and attribute text it cannot store faithfully.
 * <p>
 * This lives beside the journal tests because the journal is what the constraint exists
 * for. The boundary asserted here is the same one
 * {@code JournalLifecycleTest.latin1HeaderValuesRoundTrip} asserts from the other side:
 * everything within latin-1 round-trips byte-for-byte, and everything outside it is
 * refused before it can reach the journal. Keeping the two together means a change to the
 * stored encoding cannot move one without the other failing.
 *
 * @see com.ethlo.r7.api.TextValues
 */
class TextValuesTest
{
    @Test
    void latin1RangeIsStorable()
    {
        assertThat(TextValues.isStorable("plain ASCII")).isTrue();
        assertThat(TextValues.isStorable("Ærlig søknad - naïve café")).isTrue();
        assertThat(TextValues.isStorable("ÿ")).as("0xFF is the last single-byte code point").isTrue();
        assertThat(TextValues.isStorable("")).isTrue();
    }

    @Test
    void aboveLatin1IsNotStorable()
    {
        assertThat(TextValues.isStorable("Ā")).as("0x100 is the first that needs two bytes").isFalse();
        assertThat(TextValues.isStorable("en dash – here")).isFalse();
        assertThat(TextValues.isStorable("emoji 😀")).isFalse();
    }

    @Test
    void theOffendingCharacterIsIdentified()
    {
        assertThat(TextValues.firstUnstorableIndex("ok so far – not this")).isEqualTo(10);
        assertThat(TextValues.firstUnstorableIndex("all fine")).isEqualTo(-1);
    }

    @Test
    void settingAnUnstorableHeaderValueIsRefused()
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();

        assertThatExceptionOfType(InvalidTextValueException.class)
                .isThrownBy(() -> headers.set("X-Subject", "en dash – here"))
                .satisfies(e -> {
                    assertThat(e.getField()).isEqualTo("X-Subject");
                    assertThat(e.getIndex()).isEqualTo(8);
                    assertThat(e.getMessage()).contains("U+2013").contains("X-Subject");
                });
    }

    @Test
    void addingAnUnstorableHeaderValueIsRefused()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();

        assertThatExceptionOfType(InvalidTextValueException.class)
                .isThrownBy(() -> headers.add("X-Subject", "Ā"));
    }

    @Test
    void unstorableHeaderNamesAreRefused()
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();

        assertThatExceptionOfType(InvalidTextValueException.class)
                .isThrownBy(() -> headers.set("X-Ünïcode–Name", "fine"));
    }

    @Test
    void storableValuesAreStillAccepted()
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();

        assertThatCode(() -> headers.set("X-Subject", "Ærlig søknad - naïve café")).doesNotThrowAnyException();

        assertThat(CollectingSink.toMap(headers)).containsEntry("X-Subject", "Ærlig søknad - naïve café");
    }

    @Test
    void multiValueSetReplacesEveryValue()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("Accept", "text/plain");
        headers.add("Accept", "text/html");

        headers.set("Accept", List.of("application/json", "application/xml"));

        assertThat(headers.getAll("Accept")).containsExactly("application/json", "application/xml");
    }

    @Test
    void multiValueSetWithEmptyIterableRemovesTheEntry()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("Accept", "text/plain");

        headers.set("Accept", List.of());

        assertThat(headers.getAll("Accept")).isEmpty();
    }

    /**
     * A rejected multi-value set must leave the container as it was. Validating half the
     * values and then throwing would leave a filter's headers in a state neither the
     * filter nor the journal expects.
     */
    @Test
    void multiValueSetIsRejectedBeforeAnythingChanges()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.set("X-Subject", "original");

        assertThatExceptionOfType(InvalidTextValueException.class)
                .isThrownBy(() -> headers.set("X-Subject", List.of("fine", "en dash \u2013 here")));

        assertThat(headers.getAll("X-Subject"))
                .as("the rejected set must not have removed or replaced anything")
                .containsExactly("original");
    }

    @Test
    void nullValuesAreRefused()
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();

        assertThatExceptionOfType(InvalidTextValueException.class)
                .isThrownBy(() -> headers.set("X-Subject", (String) null))
                .satisfies(e -> {
                    assertThat(e.getField()).isEqualTo("X-Subject");
                    assertThat(e.getIndex()).isEqualTo(TextValues.ABSENT);
                    assertThat(e.getMessage()).contains("null");
                });

        assertThatExceptionOfType(InvalidTextValueException.class)
                .isThrownBy(() -> new MutableFastGatewayHeaders().add("X-Subject", null));

        assertThat(TextValues.isStorable(null)).isFalse();
    }

    @Test
    void nullNamesAreRefused()
    {
        final MutableGatewayHeaders headers = new MutableFastGatewayHeaders();

        assertThatExceptionOfType(InvalidTextValueException.class)
                .isThrownBy(() -> headers.set(null, "value"));
    }
}
