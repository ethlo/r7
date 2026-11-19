package com.ethlo.r7.warc;

/**
 * Minimal RFC 4648 base32 encoder, unpadded.
 * <p>
 * The WARC spec's labelled-digest examples ({@code sha1:AB2CD3EF4GH5IJ6KL7MN8OPQ}) use base32,
 * which is the convention most existing WARC tooling (Heritrix, wget, warcio) expects for
 * {@code WARC-Payload-Digest} even though the spec itself does not mandate an encoding. A small
 * local encoder avoids pulling in a dependency for fifteen lines of bit-shifting.
 */
final class Base32
{
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32()
    {
    }

    static String encode(final byte[] data)
    {
        final StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (final byte b : data)
        {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5)
            {
                bitsLeft -= 5;
                sb.append(ALPHABET[(buffer >>> bitsLeft) & 0x1F]);
            }
        }
        if (bitsLeft > 0)
        {
            sb.append(ALPHABET[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return sb.toString();
    }
}
