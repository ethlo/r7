package com.ethlo.r7.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class RedactingQueryTest
{
    private static final HeaderFingerprint FP = HeaderFingerprint.UNKEYED;
    private static final QueryParameterNameSet SAFE = QueryParameterNameSet.of(List.of("page", "sort", "user id"), false);

    private static String fp(final String value)
    {
        return FP.fingerprint(value);
    }

    private static String redact(final String query, final QueryParameterNameSet safe)
    {
        return RedactingQuery.redact(query, safe, FP);
    }

    @Test
    void safeValuesAreKeptAndUnsafeOnesAreFingerprinted()
    {
        assertThat(redact("page=2&api_key=s3cret", SAFE)).isEqualTo("page=2&api_key=" + fp("s3cret"));
    }

    @Test
    void withNothingConfiguredEveryValueIsFingerprinted()
    {
        assertThat(redact("page=2&q=hello", QueryParameterNameSet.NONE)).isEqualTo("page=" + fp("2") + "&q=" + fp("hello"));
    }

    @Test
    void everyOccurrenceOfARepeatedParameterIsJudgedOnItsOwn()
    {
        assertThat(redact("token=a&page=1&token=b&page=2", SAFE))
                .isEqualTo("token=" + fp("a") + "&page=1&token=" + fp("b") + "&page=2");
    }

    @Test
    void namesAreCaseSensitiveByDefault()
    {
        assertThat(redact("Page=2&PAGE=3&page=4", SAFE)).isEqualTo("Page=" + fp("2") + "&PAGE=" + fp("3") + "&page=4");
    }

    @Test
    void ignoreCaseMatchesNamesWhateverTheirCase()
    {
        final QueryParameterNameSet safe = QueryParameterNameSet.of(List.of("page"), true);
        assertThat(redact("Page=2&PAGE=3&token=x", safe)).isEqualTo("Page=2&PAGE=3&token=" + fp("x"));
    }

    @Test
    void ignoreCaseDoesNotDependOnTheDefaultLocale()
    {
        final Locale original = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.of("tr", "TR"));
            final QueryParameterNameSet safe = QueryParameterNameSet.of(List.of("id"), true);
            assertThat(redact("ID=7", safe)).isEqualTo("ID=7");
        }
        finally
        {
            Locale.setDefault(original);
        }
    }

    /**
     * Names are compared as the upstream reads them, so an encoded spelling of a safe name is
     * the safe name - and it is journaled as it was sent.
     */
    @Test
    void anEncodedNameMatchesItsDecodedForm()
    {
        assertThat(redact("p%61ge=2&user%20id=42&user+id=43", SAFE)).isEqualTo("p%61ge=2&user%20id=42&user+id=43");
    }

    @Test
    void anEncodedSpellingOfAnUnsafeNameIsStillRedacted()
    {
        assertThat(redact("api%5Fkey=s3cret", SAFE)).isEqualTo("api%5Fkey=" + fp("s3cret"));
    }

    /**
     * A filter that changes the query re-encodes it, so the client's and the upstream's
     * spelling of one value can differ. The fingerprint is of the decoded value, so they still
     * correlate.
     */
    @Test
    void differentlyEncodedSpellingsOfOneValueGetOneFingerprint()
    {
        assertThat(redact("q=a%20b", SAFE)).isEqualTo(redact("q=a+b", SAFE)).isEqualTo("q=" + fp("a b"));
    }

    @Test
    void aMalformedEscapeIsFingerprintedAsSent()
    {
        assertThat(redact("q=100%&pa%ge=1", SAFE)).isEqualTo("q=" + fp("100%") + "&pa%ge=" + fp("1"));
    }

    /**
     * Only the first '=' separates name from value; the rest belongs to the value, as base64
     * padding does.
     */
    @Test
    void aValueContainingEqualsSignsIsFingerprintedWhole()
    {
        assertThat(redact("token=abc==", SAFE)).isEqualTo("token=" + fp("abc=="));
    }

    @Test
    void aBareParameterIsHiddenUnlessItIsSafe()
    {
        assertThat(redact("s3cret-token&sort&page=1", SAFE)).isEqualTo(fp("s3cret-token") + "&sort&page=1");
    }

    @Test
    void emptyValuesAndEmptyPairsAreKeptAsTheyAre()
    {
        assertThat(redact("a=&&page=1&", SAFE)).isEqualTo("a=&&page=1&");
    }

    @Test
    void theKeyedFingerprintIsUsedWhenConfigured()
    {
        final HeaderFingerprint keyed = HeaderFingerprint.of("k".repeat(48));
        assertThat(RedactingQuery.redact("api_key=s3cret", SAFE, keyed)).isEqualTo("api_key=" + keyed.fingerprint("s3cret")).contains("id:hmac:");
    }

    @Test
    void theQueryOfARequestLineIsRedactedAndTheRestKept()
    {
        assertThat(redactLine("GET /a/b?page=2&api_key=s3cret HTTP/1.1"))
                .isEqualTo("GET /a/b?page=2&api_key=" + fp("s3cret") + " HTTP/1.1");
    }

    @Test
    void aRequestLineWithoutAQueryIsReturnedUntouched()
    {
        final ByteBuffer line = latin1("GET /a/b HTTP/1.1");
        assertThat(RedactingQuery.redactRequestLine(line, SAFE, FP)).isSameAs(line);
    }

    @Test
    void anEmptyQueryStaysEmpty()
    {
        assertThat(redactLine("GET /a? HTTP/1.1")).isEqualTo("GET /a? HTTP/1.1");
    }

    @Test
    void onlyTheFirstQuestionMarkStartsTheQuery()
    {
        assertThat(redactLine("GET /a?next=/b?c=d HTTP/1.1")).isEqualTo("GET /a?next=" + fp("/b?c=d") + " HTTP/1.1");
    }

    @Test
    void theBufferPositionIsHonouredAndLeftAlone()
    {
        final ByteBuffer line = latin1("xxGET /a?q=1 HTTP/1.1");
        line.position(2);
        final ByteBuffer redacted = RedactingQuery.redactRequestLine(line, SAFE, FP);
        assertThat(line.position()).isEqualTo(2);
        assertThat(latin1(redacted)).isEqualTo("GET /a?q=" + fp("1") + " HTTP/1.1");
    }

    private static String redactLine(final String line)
    {
        return latin1(RedactingQuery.redactRequestLine(latin1(line), SAFE, FP));
    }

    private static ByteBuffer latin1(final String s)
    {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String latin1(final ByteBuffer buffer)
    {
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
