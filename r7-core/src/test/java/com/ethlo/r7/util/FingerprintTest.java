package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FingerprintTest
{
    private static final String KEY = TestFingerprints.KEY;
    private static final String BASIC = "Basic YWRtaW46cGFzc3dvcmQ="; // admin:password

    /**
     * Pinned against an implementation outside this codebase (Python's {@code hmac} and
     * {@code base64.urlsafe_b64encode} over the first eight bytes, padding stripped), so the
     * format consumers match on cannot drift unnoticed. A value outside ASCII is hashed as UTF-8,
     * and the last one covers both {@code -} and {@code _}.
     */
    @ParameterizedTest
    @CsvSource(value = {
            "Basic YWRtaW46cGFzc3dvcmQ=|fp:tRuoRFV7BYU",
            "''|fp:NQC0WQ9RSOg",
            "café|fp:BEdt6GXe5GM",
            "value-5|fp:xY-Stx0m0_M"
    }, delimiter = '|')
    void matchesTheReferenceEncoding(final String value, final String expected)
    {
        assertThat(Fingerprint.of(KEY).fingerprint(value)).isEqualTo(expected);
    }

    @Test
    void isTheShortPrefixAndElevenUrlSafeCharacters()
    {
        assertThat(Fingerprint.of(KEY).fingerprint(BASIC)).matches("fp:[A-Za-z0-9_-]{11}");
    }

    /**
     * The point of the key: a reader of the journal can hash a guessed credential, but cannot
     * get the fingerprint the journal holds from it.
     */
    @Test
    void cannotBeReproducedFromTheValueAlone() throws Exception
    {
        final String fingerprint = Fingerprint.of(KEY).fingerprint(BASIC);
        final byte[] plain = MessageDigest.getInstance("SHA-256").digest(BASIC.getBytes(StandardCharsets.UTF_8));

        assertThat(fingerprint).doesNotContain(HexFormat.of().formatHex(plain, 0, 3));
        assertThat(fingerprint).isNotEqualTo(Fingerprint.of(KEY.replace('a', 'b')).fingerprint(BASIC));
    }

    @Test
    void correlatesRepeatsUnderTheSameKey()
    {
        assertThat(Fingerprint.of(KEY).fingerprint(BASIC)).isEqualTo(Fingerprint.of(KEY).fingerprint(BASIC));
        assertThat(Fingerprint.of(KEY).fingerprint(BASIC)).isNotEqualTo(Fingerprint.of(KEY).fingerprint(BASIC + "x"));
    }

    @Test
    void aMissingKeyIsRefused()
    {
        assertThatThrownBy(() -> Fingerprint.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Fingerprint.of("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aShortKeyIsRefused()
    {
        assertThatThrownBy(() -> Fingerprint.of("x".repeat(Fingerprint.MIN_KEY_LENGTH - 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Fingerprint.of("x".repeat(Fingerprint.MIN_KEY_LENGTH))).isNotNull();
    }

    @Test
    void theKeyIsNeverPrinted()
    {
        assertThat(Fingerprint.of(KEY).toString()).doesNotContain(KEY);
    }
}
