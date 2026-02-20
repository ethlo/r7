package com.ethlo.venturi.vlf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class VlfDictionary
{
    private static final int CAPACITY = 512; // Power of 2 for fast masking
    private static final int MASK = CAPACITY - 1;

    private final String[] keys;
    private final byte[] values;
    private final int[] lengths; // Extra guard to fail fast
    private final String[] decodeArray;

    public VlfDictionary(Properties props)
    {
        this.keys = new String[CAPACITY];
        this.values = new byte[CAPACITY];
        this.lengths = new int[CAPACITY];
        this.decodeArray = new String[256];

        for (String key : props.stringPropertyNames())
        {
            int id = Integer.parseInt(key);
            String val = props.getProperty(key);

            int hash = hash(val);
            while (keys[hash] != null)
            {
                hash = (hash + 1) & MASK;
            }
            keys[hash] = val;
            values[hash] = (byte) id;
            lengths[hash] = val.length(); // Cache the length
            decodeArray[id] = val;
        }
    }

    /**
     * Static loader for the Writer to initialize from classpath
     */
    public static VlfDictionary load(String classPath)
    {
        Properties props = new Properties();
        try (InputStream in = VlfDictionary.class.getResourceAsStream(classPath.startsWith("/") ? classPath : "/" + classPath))
        {
            if (in == null)
            {
                throw new IOException("No resource found for classpath " + classPath);
            }
            props.load(in);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        return new VlfDictionary(props);
    }

    public byte encode(String value)
    {
        if (value == null) return -1;

        int len = value.length();
        int hash = hash(value);

        while (keys[hash] != null)
        {
            if (lengths[hash] == len && keys[hash].equals(value))
            {
                return values[hash];
            }
            hash = (hash + 1) & MASK;
        }
        return -1;
    }

    private int hash(String s)
    {
        int h = s.hashCode();
        return (h ^ (h >>> 16)) & MASK;
    }

    public String decode(byte id)
    {
        return decodeArray[id & 0xFF];
    }

    /**
     * Used by the Writer to iterate through entries when writing the 4KB preamble
     */
    public Map<String, Byte> getEntries()
    {
        Map<String, Byte> snapshot = new HashMap<>(256);
        for (int i = 0; i < CAPACITY; i++)
        {
            if (keys[i] != null)
            {
                snapshot.put(keys[i], values[i]);
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }
}