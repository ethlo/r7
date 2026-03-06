package com.ethlo.venturi.api;

public enum IpSource
{
    UNKNOWN((byte) 0),
    SOCKET((byte) 1),
    X_FORWARDED_FOR((byte) 2),
    X_REAL_IP((byte) 3);

    private final byte id;

    IpSource(final byte id)
    {
        this.id = id;
    }

    public static IpSource valueOf(byte b)
    {
        return switch (b)
        {
            case 0 -> UNKNOWN;
            case 1 -> SOCKET;
            case 2 -> X_FORWARDED_FOR;
            case 3 -> X_REAL_IP;
            default -> throw new IllegalStateException("Unexpected value: " + b);
        };
    }

    public byte byteValue()
    {
        return id;
    }
}