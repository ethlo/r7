package com.ethlo.r7.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What r7 writes in place of a value it must not disclose: a redacted header or query
 * parameter in the journal, and the user name {@code BasicAuth} accepted.
 * <p>
 * The form is {@code fp:} and eleven base64url characters, the first 64 bits of
 * HMAC-SHA-256 under the deployment's {@code storage.journal_security.fingerprint_key}. Two
 * equal values give the same fingerprint, so repeats still correlate across requests,
 * restarts and replicas that share the key. Without the key a reader cannot check a guess,
 * which matters because the values hidden here are often low-entropy: {@code Authorization:
 * Basic} with a known user name and a common password, a short API key, a {@code role=admin}
 * cookie.
 * <p>
 * There is deliberately one form and no unkeyed fallback. An unkeyed hash is only as strong as
 * the entropy of the value, and a key generated at startup would silently stop fingerprints
 * correlating across a restart. So the key is required, and the gateway does not start
 * without it.
 * <p>
 * 64 bits because, once a value cannot be guessed, the only thing the length does is keep two
 * different values from looking like the same one; a deployment would need billions of
 * distinct values of one header before that becomes likely. Base64url so the fingerprint can
 * stand in a query string, a JSON string or a WARC header without escaping.
 */
public final class Fingerprint
{
    /**
     * Shortest key accepted. The key is the entire secret, and HMAC-SHA-256 wants at least
     * as many bits of key as the security it is expected to give.
     */
    public static final int MIN_KEY_LENGTH = 32;

    public static final String PREFIX = "fp:";

    private static final int DIGEST_BYTES = 8;
    private static final int ENCODED_LENGTH = 11;
    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private final ThreadLocal<Mac> mac;

    private Fingerprint(final byte[] key)
    {
        final SecretKeySpec spec = new SecretKeySpec(key, ALGORITHM);
        // Tried once here so that a JVM without HmacSHA256 fails at startup, not on the first
        // redacted value, where it would be a runtime failure on the request path.
        newMac(spec);
        this.mac = ThreadLocal.withInitial(() -> newMac(spec));
    }

    /**
     * @param key the per-deployment secret, at least {@link #MIN_KEY_LENGTH} characters
     * @throws IllegalArgumentException if the key is missing or too short
     */
    public static Fingerprint of(final String key)
    {
        if (key == null || key.length() < MIN_KEY_LENGTH)
        {
            throw new IllegalArgumentException("A fingerprint key of at least " + MIN_KEY_LENGTH + " characters is required");
        }
        return new Fingerprint(key.getBytes(StandardCharsets.UTF_8));
    }

    public String fingerprint(final String value)
    {
        Objects.requireNonNull(value, "value");
        final byte[] digest = this.mac.get().doFinal(value.getBytes(StandardCharsets.UTF_8));
        final char[] out = new char[PREFIX.length() + ENCODED_LENGTH];
        PREFIX.getChars(0, PREFIX.length(), out, 0);

        // 64 bits as eleven 6-bit groups, most significant first; the last group carries the
        // final four bits shifted up, as unpadded base64url encodes eight bytes.
        long bits = 0;
        for (int i = 0; i < DIGEST_BYTES; i++)
        {
            bits = (bits << 8) | (digest[i] & 0xFF);
        }
        int pos = PREFIX.length();
        for (int shift = 58; shift >= 4; shift -= 6)
        {
            out[pos++] = BASE64URL[(int) (bits >>> shift) & 0x3F];
        }
        out[pos] = BASE64URL[(int) (bits << 2) & 0x3F];
        return new String(out);
    }

    private static Mac newMac(final SecretKeySpec spec)
    {
        try
        {
            final Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(spec);
            return mac;
        }
        catch (final GeneralSecurityException e)
        {
            throw new IllegalStateException(ALGORITHM + " is not available on this JVM", e);
        }
    }

    @Override
    public String toString()
    {
        // Never the key.
        return "Fingerprint[keyed]";
    }
}
