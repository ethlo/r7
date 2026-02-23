package com.ethlo.venturi.vlf.dictionary;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class VlfDictionaryByteUltra implements VlfDictionary
{
    private static final int CAPACITY = 512; // power of 2
    private static final int MASK = CAPACITY - 1;

    private final byte[][] keys = new byte[CAPACITY][];
    private final byte[] lengths = new byte[CAPACITY];
    private final byte[] values = new byte[CAPACITY];
    private final String[] decodeArray = new String[256];

    public VlfDictionaryByteUltra(Properties props)
    {
        for (String k : props.stringPropertyNames())
        {
            int id = Integer.parseInt(k);
            byte[] rawVal = props.getProperty(k).getBytes(java.nio.charset.StandardCharsets.US_ASCII);

            // Store the original case-preserved string for fast decoding
            decodeArray[id & 0xFF] = new String(rawVal, java.nio.charset.StandardCharsets.US_ASCII);

            // Pre-lowercase the dictionary keys so we only have to normalize the input later
            byte[] lowerVal = new byte[rawVal.length];
            for (int i = 0; i < rawVal.length; i++)
            {
                lowerVal[i] = toLowerAscii(rawVal[i]);
            }

            // Hash the lowercased version
            int h = hash(lowerVal);
            while (keys[h] != null) h = (h + 1) & MASK;

            keys[h] = lowerVal;
            lengths[h] = (byte) lowerVal.length;
            values[h] = (byte) id;
        }
    }

    private static boolean compareUnrolledIgnoreCase(byte[] a, byte[] b, int len)
    {
        int diff = 0;
        int i = 0;

        // Process 8 bytes at a time, normalizing the input 'b' on the fly
        for (; i + 8 <= len; i += 8)
        {
            diff |= (a[i] ^ toLowerAscii(b[i]))
                    | (a[i + 1] ^ toLowerAscii(b[i + 1]))
                    | (a[i + 2] ^ toLowerAscii(b[i + 2]))
                    | (a[i + 3] ^ toLowerAscii(b[i + 3]))
                    | (a[i + 4] ^ toLowerAscii(b[i + 4]))
                    | (a[i + 5] ^ toLowerAscii(b[i + 5]))
                    | (a[i + 6] ^ toLowerAscii(b[i + 6]))
                    | (a[i + 7] ^ toLowerAscii(b[i + 7]));
        }

        // Handle remaining bytes
        for (; i < len; i++)
        {
            diff |= a[i] ^ toLowerAscii(b[i]);
        }

        return diff == 0;
    }

    private static int hash(byte[] b)
    {
        int h = 0;
        for (byte x : b)
        {
            // Normalize to lowercase while hashing so 'A' and 'a' route to the same bucket
            h = 31 * h + (toLowerAscii(x) & 0xFF);
        }
        return h & MASK;
    }

    /**
     * Converts uppercase ASCII letters to lowercase using a bitwise OR.
     * The ternary operator is easily compiled to a branchless CMOV instruction.
     */
    private static byte toLowerAscii(byte b)
    {
        return (b >= 'A' && b <= 'Z') ? (byte) (b | 0x20) : b;
    }

    @Override
    public byte encode(byte[] value)
    {
        if (value == null || value.length > 64)
        {
            return -1;
        }

        int h = hash(value);
        while (keys[h] != null)
        {
            byte len = lengths[h];
            // Dictionary keys (keys[h]) are already lowercased from the constructor
            if (len == value.length && compareUnrolledIgnoreCase(keys[h], value, len))
            {
                return values[h];
            }
            h = (h + 1) & MASK;
        }
        return -1;
    }

    @Override
    public CharSequence decode(byte id)
    {
        return decodeArray[id & 0xFF];
    }

    @Override
    public Map<CharSequence, Byte> getEntries()
    {
        Map<CharSequence, Byte> map = new HashMap<>();
        for (int i = 0; i < CAPACITY; i++)
        {
            if (keys[i] != null)
            {
                // Use the decodeArray to return the exact original case, not the lowercased keys
                byte id = values[i];
                map.put(decodeArray[id & 0xFF], id);
            }
        }
        return Collections.unmodifiableMap(map);
    }
}