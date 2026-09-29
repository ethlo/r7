package com.ethlo.r7.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.RedactUtil;

class HeaderFingerprintTest
{
    private static final String KEY = "0123456789abcdef0123456789abcdef-deployment-a";
    private static final String BASIC = "Basic YWRtaW46cGFzc3dvcmQ="; // admin:password

    /**
     * The unkeyed form is kept byte-for-byte, so nothing downstream changes until an operator
     * opts in.
     */
    @Test
    void unkeyedIsTheExistingFingerprint()
    {
        assertThat(HeaderFingerprint.of(null).fingerprint(BASIC)).isEqualTo(RedactUtil.fingerprint(BASIC));
        assertThat(HeaderFingerprint.of("").isKeyed()).isFalse();
    }

    /**
     * The point of the key: a reader of the journal can compute the unkeyed fingerprint of a
     * guessed credential and compare; it cannot compute the keyed one.
     */
    @Test
    void aKeyedFingerprintCannotBeReproducedFromTheValueAlone()
    {
        final String keyed = HeaderFingerprint.of(KEY).fingerprint(BASIC);

        assertThat(keyed).startsWith("id:hmac:").hasSize("id:hmac:".length() + 16);
        assertThat(keyed).isNotEqualTo(RedactUtil.fingerprint(BASIC));
        assertThat(keyed).isNotEqualTo(HeaderFingerprint.of(KEY.replace('a', 'b')).fingerprint(BASIC));
    }

    @Test
    void aKeyedFingerprintStillCorrelatesRepeatsWithinADeployment()
    {
        assertThat(HeaderFingerprint.of(KEY).fingerprint(BASIC)).isEqualTo(HeaderFingerprint.of(KEY).fingerprint(BASIC));
        assertThat(HeaderFingerprint.of(KEY).fingerprint(BASIC)).isNotEqualTo(HeaderFingerprint.of(KEY).fingerprint(BASIC + "x"));
    }

    @Test
    void aShortKeyIsRefused()
    {
        assertThatThrownBy(() -> HeaderFingerprint.of("short")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redactedHeadersUseTheConfiguredFingerprint()
    {
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        headers.add("Authorization", BASIC);
        headers.add("User-Agent", "curl/8");
        final HeaderFingerprint fingerprint = HeaderFingerprint.of(KEY);

        final List<String> written = new ArrayList<>();
        new RedactingHeaders(headers, JournalSecurity.SAFE_REQUEST_HEADERS, new FingerprintMemo(fingerprint))
                .forEach((name, value) -> written.add(name + "=" + value));

        assertThat(written).containsExactly("Authorization=" + fingerprint.fingerprint(BASIC), "User-Agent=curl/8");
    }

    @Test
    void theKeyIsNeverPrinted()
    {
        assertThat(HeaderFingerprint.of(KEY).toString()).doesNotContain(KEY);
    }
}
