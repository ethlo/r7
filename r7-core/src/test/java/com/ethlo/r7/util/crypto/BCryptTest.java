package com.ethlo.r7.util.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BCryptTest
{
    private static final String ABC_COST_06 = "$2a$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i";

    /**
     * The published OpenBSD/jBCrypt vectors. They are here above all to pin the Blowfish P-array
     * and S-boxes, which are 1042 words of the hexadecimal expansion of pi. A table that is short
     * by even one source line still compiles and still looks entirely plausible, but then fails
     * every verification with an {@code ArrayIndexOutOfBoundsException} from inside the key
     * schedule - which the request pipeline turns into a 500, so no user can ever log in. Verifying
     * a real hash is the only thing that exercises all 1024 S-box entries.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "a|$2a$06$m0CrhHm10qJ3lXRY.5zDGO3rS2KdeeWLuGmsfGlMfOxih58VYVfxe",
            "a|$2a$08$cfcvVd2aQ8CMvoMpP2EBfeodLEkkFJ9umNEfPD18.hUF62qqlC/V.",
            "abc|$2a$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i",
            "abc|$2a$10$WvvTPHKwdBJ3uk0Z37EMR.hLA2W6N9AEBhEgrAOljy2Ae5MtaSIUi",
            "abcdefghijklmnopqrstuvwxyz|$2a$06$.rCVZVOThsIa97pEDOxvGuRRgzG64bvtJ0938xuqzv18d3ZpQhstC",
            "abcdefghijklmnopqrstuvwxyz|$2a$10$fVH8e28OQRj9tqiDXs1e1uxpsjN0c7II7YPKXua2NAKYvM6iQk7dq",
            "~!@#$%^&*()      ~!@#$%^&*()PNBFRD|$2a$06$fPIsBO8qRqkjj273rfaOI.HtSV9jLDpTbZn782DC6/t7qT67P6FfO",
            "~!@#$%^&*()      ~!@#$%^&*()PNBFRD|$2a$10$LgfYWkbzEvQ4JakH7rOvHe0y8pHKF9OaFgwUZ2q7W2FFZmZzJYlfS"
    })
    void knownVectorsVerify(final String password, final String hash)
    {
        assertThat(BCrypt.checkPassword(password, hash)).isTrue();
    }

    @Test
    void theEmptyPasswordVerifiesAgainstItsKnownVector()
    {
        assertThat(BCrypt.checkPassword("", "$2a$06$DCq7YPn5Rq63x1Lad4cll.TV4S6ytwfsfvkgY8jIucDrjc8deX1s.")).isTrue();
        assertThat(BCrypt.checkPassword("", "$2a$10$k1wbIrmNyFAPwPVPSVa/zecw2BCEnBwVS2GbrmgzxFUOqW9dk4TCW")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abd", "ABC", "abcd", "ab", "", "abc "})
    void wrongPasswordsAreRejected(final String password)
    {
        assertThat(BCrypt.checkPassword(password, ABC_COST_06)).isFalse();
    }

    /**
     * {@code $2x} differs from {@code $2y} only in the sign-extension bug and {@code $2a} only in
     * the 0x10000 safety mask, and neither can be triggered by a key without a high bit set. A
     * purely ASCII password must therefore verify identically under all three, which is what makes
     * hashes portable between {@code htpasswd} versions.
     */
    @ParameterizedTest
    @ValueSource(strings = {"$2a", "$2b", "$2x", "$2y"})
    void asciiPasswordsVerifyUnderEveryRevision(final String revision)
    {
        assertThat(BCrypt.checkPassword("abc", revision + ABC_COST_06.substring(3))).isTrue();
        assertThat(BCrypt.checkPassword("abd", revision + ABC_COST_06.substring(3))).isFalse();
    }

    @Test
    void costIsReadFromTheHash()
    {
        assertThat(BCrypt.costOf(ABC_COST_06)).isEqualTo(6);
        assertThat(BCrypt.costOf("$2y$12" + ABC_COST_06.substring(6))).isEqualTo(12);
    }

    @Test
    void wellFormedHashesAreAccepted()
    {
        assertThatNoException().isThrownBy(() -> BCrypt.requireValidHash(ABC_COST_06));
        assertThatNoException().isThrownBy(() -> BCrypt.requireValidHash("$2y$31" + ABC_COST_06.substring(6)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "notahash",
            "plaintext-password",
            "$2a$10$tooshort",
            "$apr1$abcdefgh$1234567890123456789012",
            "$2c$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i",
            "$2$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i",
            "$2a$1x$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i",
            "$2a$03$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i",
            "$2a$32$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i",
            "$2a$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i!"
    })
    void unusableHashesAreRejectedUpFront(final String hash)
    {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BCrypt.requireValidHash(hash));
    }

    @Test
    void aNullHashIsRejectedRatherThanThrowingNullPointer()
    {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> BCrypt.requireValidHash(null));
    }
}
