package com.ethlo.r7.journal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ethlo.r7.util.RedactUtil;

/**
 * How a redacted header value is written to the journal.
 * <p>
 * The unkeyed form, {@code id:sha256:} and six hex digits, is a plain SHA-256 of the value.
 * It correlates repeats, but it is not a secret: anyone who can read the journal can hash
 * guesses and compare. For a high-entropy bearer token that is harmless. For
 * {@code Authorization: Basic} with a known user name and a common password, a short API key
 * or a cookie such as {@code role=admin}, a dictionary finds the value — which is what the
 * redaction was supposed to prevent.
 * <p>
 * The keyed form, {@code id:hmac:} and sixteen hex digits, is HMAC-SHA-256 under a key that
 * the gateway holds and journal readers do not. It still correlates repeats within the
 * deployment, and without the key a guess cannot be checked. It is longer because, once the
 * value cannot be guessed, the only thing the length does is keep two different values from
 * looking like the same one.
 * <p>
 * The two forms carry different prefixes so a reader can tell which it has. Keyed output is
 * opt-in ({@code storage.journal_security.fingerprint_key}), so the unkeyed form, and every
 * consumer that matches on it, is unchanged until an operator sets a key.
 */
public final class HeaderFingerprint
{
    public static final HeaderFingerprint UNKEYED = new HeaderFingerprint(null);

    /**
     * Shortest key accepted. The key is the entire secret, and HMAC-SHA-256 wants at least
     * as many bits of key as the security it is expected to give.
     */
    public static final int MIN_KEY_LENGTH = 32;

    static final String KEYED_PREFIX = "id:hmac:";
    private static final int KEYED_DIGEST_BYTES = 8;
    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final ThreadLocal<Mac> mac;

    private HeaderFingerprint(final byte[] key)
    {
        if (key == null)
        {
            this.mac = null;
            return;
        }

        final SecretKeySpec spec = new SecretKeySpec(key, ALGORITHM);
        // Tried once here so that a JVM without HmacSHA256 fails at startup, not on the first
        // redacted header, where it would be a runtime failure on the request path.
        newMac(spec);
        this.mac = ThreadLocal.withInitial(() -> newMac(spec));
    }

    /**
     * @param key the per-deployment secret, at least {@link #MIN_KEY_LENGTH} characters;
     *            {@code null} or empty for the unkeyed form
     */
    public static HeaderFingerprint of(final String key)
    {
        if (key == null || key.isEmpty())
        {
            return UNKEYED;
        }
        if (key.length() < MIN_KEY_LENGTH)
        {
            throw new IllegalArgumentException("A fingerprint key must be at least " + MIN_KEY_LENGTH + " characters");
        }
        return new HeaderFingerprint(key.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isKeyed()
    {
        return mac != null;
    }

    public String fingerprint(final String value)
    {
        if (mac == null)
        {
            return RedactUtil.fingerprint(value);
        }

        final Mac hmac = mac.get();
        final byte[] digest = hmac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        final char[] out = new char[KEYED_PREFIX.length() + KEYED_DIGEST_BYTES * 2];
        KEYED_PREFIX.getChars(0, KEYED_PREFIX.length(), out, 0);
        for (int i = 0; i < KEYED_DIGEST_BYTES; i++)
        {
            final int v = digest[i] & 0xFF;
            out[KEYED_PREFIX.length() + i * 2] = HEX[v >>> 4];
            out[KEYED_PREFIX.length() + i * 2 + 1] = HEX[v & 0x0F];
        }
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
        return mac == null ? "HeaderFingerprint[unkeyed]" : "HeaderFingerprint[keyed]";
    }
}
