package com.ethlo.venturi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

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

    public static List<String> redactAll(List<String> headerValues)
    {
        return headerValues.stream().map(RedactUtil::redact).toList();
    }

    public static String redact(final String input, int include)
    {
        if (input == null)
        {
            return null;
        }
        else if (include < 0 || input.length() <= include + 6)
        {
            return "*****";
        }
        return input.substring(0, include) + "*****" + input.substring(input.length() - include);
    }

    public static String redact(String input)
    {
        return redact(input, 1);
    }

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
