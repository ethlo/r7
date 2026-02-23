package com.ethlo.venturi.undertow.util;

import io.undertow.util.HttpString;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class HttpStringUtil
{
    private static final VarHandle VALUE_BYTES;

    static
    {
        try
        {
            VALUE_BYTES = MethodHandles.privateLookupIn(HttpString.class, MethodHandles.lookup())
                    .findVarHandle(HttpString.class, "bytes", byte[].class);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public static byte[] getBytes(HttpString hs)
    {
        return (byte[]) VALUE_BYTES.get(hs);
        //return copyBytes(hs);
    }

    public static byte[] copyBytes(HttpString hs)
    {
        final byte[] bytes = new byte[hs.length()];
        hs.copyTo(bytes, 0, bytes.length);
        return bytes;
    }
}