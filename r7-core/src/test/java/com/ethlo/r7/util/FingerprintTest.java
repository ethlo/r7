package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

    /**
     * The truncation and encoding hold for any key and value: checked against the full
     * {@link Mac} output across keys longer than a SHA-256 block, which HMAC hashes first, and
     * values that span several blocks.
     */
    @Test
    void agreesWithTheJdkHmac() throws Exception
    {
        final Random random = new Random(42);
        for (final int keyLength : new int[]{Fingerprint.MIN_KEY_LENGTH, 63, 64, 65, 200})
        {
            final String key = randomText(random, keyLength);
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            final Fingerprint fingerprint = Fingerprint.of(key);
            for (final int valueLength : new int[]{0, 1, 55, 56, 64, 119, 1000})
            {
                final String value = randomText(random, valueLength);
                final byte[] expected = Arrays.copyOf(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)), 8);
                assertThat(fingerprint.fingerprint(value))
                        .as("key length %d, value length %d", keyLength, valueLength)
                        .isEqualTo(Fingerprint.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(expected));
            }
        }
    }

    private static String randomText(final Random random, final int length)
    {
        final StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++)
        {
            text.append((char) (' ' + random.nextInt(95)));
        }
        return text.toString();
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
