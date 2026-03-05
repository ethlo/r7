package com.ethlo.venturi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class RedactUtil
{
    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() ->
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    });

    public static String fingerprint(final String value)
    {
        final MessageDigest digest = SHA_256.get();
        digest.reset();
        final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        final char[] out = new char[16];
        "id:sha256:".getChars(0, 10, out, 0);
        for (int j = 0; j < 3; j++)
        {
            final int v = hash[j] & 0xFF;
            out[10 + j * 2] = (char) HEX_ARRAY[v >>> 4];
            out[11 + j * 2] = (char) HEX_ARRAY[v & 0x0F];
        }
        return new String(out);
    }
}
