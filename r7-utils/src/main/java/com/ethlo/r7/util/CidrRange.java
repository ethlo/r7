package com.ethlo.r7.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

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
     *             {@code ::1/128}
     * @throws IllegalArgumentException if {@code cidr} is not a resolvable address or the
     *                                   prefix length is out of range for the address type
     */
    public static CidrRange parse(final String cidr)
    {
        final String[] parts = cidr.split("/");
        try
        {
            final InetAddress address = InetAddress.getByName(parts[0]);
            final byte[] addressBytes = address.getAddress();
            final int maxMask = addressBytes.length * 8;
            final int prefixLength = parts.length > 1 ? Integer.parseInt(parts[1]) : maxMask;

            if (prefixLength < 0 || prefixLength > maxMask)
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
        catch (final UnknownHostException | NumberFormatException e)
        {
            throw new IllegalArgumentException("Invalid IP or CIDR notation: '" + cidr + "'", e);
        }
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
