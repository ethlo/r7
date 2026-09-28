package com.ethlo.r7.util;

import java.net.InetAddress;

/**
 * An IPv4 or IPv6 CIDR range, immutable once parsed. Matching is a masked byte comparison,
 * so it costs nothing to evaluate per request.
 */
public final class CidrRange
{
    private final byte[] networkBytes;
    private final byte[] maskBytes;
    private final String source;

    private CidrRange(final byte[] networkBytes, final byte[] maskBytes, final String source)
    {
        this.networkBytes = networkBytes;
        this.maskBytes = maskBytes;
        this.source = source;
    }

    /**
     * @param cidr an address or {@code address/prefixLength}, e.g. {@code 10.0.0.0/8} or
     *             {@code ::1/128}. The address must be a literal (no hostnames, so no DNS
     *             lookup is ever performed), and the optional prefix must be exactly one
     *             non-empty, all-digit segment — trailing slashes, extra segments and
     *             blank prefixes (e.g. {@code 10.0.0.1/}, {@code 10.0.0.1/24/extra}) are all
     *             refused rather than silently defaulting or being ignored, since a typo an
     *             operator did not notice would otherwise trust a different network than the
     *             one they configured.
     * @throws IllegalArgumentException if {@code cidr} does not match that grammar, or the
     *                                   prefix length is out of range for the address type
     */
    public static CidrRange parse(final String cidr)
    {
        if (cidr == null || cidr.isBlank())
        {
            throw new IllegalArgumentException("CIDR must not be null or blank");
        }

        // -1 keeps trailing empty segments (e.g. a trailing '/') instead of silently
        // dropping them, so they are rejected below rather than treated as "no prefix".
        final String[] parts = cidr.split("/", -1);
        if (parts.length > 2)
        {
            throw new IllegalArgumentException("Invalid IP or CIDR notation: '" + cidr + "'");
        }

        final InetAddress address;
        try
        {
            // ofLiteral parses an IPv4/IPv6 text literal only; unlike getByName it never
            // falls back to resolving a hostname, so it cannot be made to perform a DNS
            // lookup by an attacker-controlled value.
            address = InetAddress.ofLiteral(parts[0]);
        }
        catch (final IllegalArgumentException e)
        {
            throw new IllegalArgumentException("Invalid IP or CIDR notation: '" + cidr + "'", e);
        }

        final byte[] addressBytes = address.getAddress();
        final int maxMask = addressBytes.length * 8;

        final int prefixLength;
        if (parts.length == 2)
        {
            final String prefixPart = parts[1];
            if (prefixPart.isEmpty() || !isAllDigits(prefixPart))
            {
                throw new IllegalArgumentException(
                        "Invalid IP or CIDR notation: '" + cidr + "' (prefix length must be a non-negative integer)");
            }
            try
            {
                prefixLength = Integer.parseInt(prefixPart);
            }
            catch (final NumberFormatException e)
            {
                throw new IllegalArgumentException("Invalid IP or CIDR notation: '" + cidr + "'", e);
            }
        }
        else
        {
            prefixLength = maxMask;
        }

        if (prefixLength > maxMask)
        {
            throw new IllegalArgumentException(
                    "Subnet mask must be between 0 and " + maxMask + " for '" + cidr + "'");
        }

        final byte[] maskBytes = new byte[addressBytes.length];
        for (int i = 0; i < prefixLength; i++)
        {
            maskBytes[i / 8] |= (byte) (1 << (7 - (i % 8)));
        }

        final byte[] networkBytes = new byte[addressBytes.length];
        for (int i = 0; i < addressBytes.length; i++)
        {
            networkBytes[i] = (byte) (addressBytes[i] & maskBytes[i]);
        }

        return new CidrRange(networkBytes, maskBytes, cidr);
    }

    private static boolean isAllDigits(final String value)
    {
        for (int i = 0, len = value.length(); i < len; i++)
        {
            if (!Character.isDigit(value.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * @return whether {@code address} falls within this range. {@code null}, and addresses
     * of a different family (IPv4 vs IPv6), never match.
     */
    public boolean contains(final InetAddress address)
    {
        if (address == null)
        {
            return false;
        }

        final byte[] clientBytes = address.getAddress();
        if (clientBytes.length != this.networkBytes.length)
        {
            return false;
        }

        for (int i = 0; i < this.networkBytes.length; i++)
        {
            if ((clientBytes[i] & this.maskBytes[i]) != this.networkBytes[i])
            {
                return false;
            }
        }

        return true;
    }

    @Override
    public String toString()
    {
        return source;
    }
}
