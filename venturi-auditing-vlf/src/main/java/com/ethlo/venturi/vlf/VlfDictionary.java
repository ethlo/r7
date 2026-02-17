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
    private final Map<String, Byte> encodeMap;
    private final String[] decodeArray;

    /**
     * Constructor used by both the loader and the Decoder
     */
    public VlfDictionary(Properties props)
    {
        this.encodeMap = new HashMap<>(props.size());
        this.decodeArray = new String[256];

        for (String key : props.stringPropertyNames())
        {
            int id = Integer.parseInt(key);
            String value = props.getProperty(key);

            if (id < 0 || id > 255)
            {
                throw new IllegalArgumentException("Dictionary ID must be between 0 and 255");
            }

            this.encodeMap.put(value, (byte) id);
            this.decodeArray[id] = value;
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

    public Byte encode(String value)
    {
        return encodeMap.get(value);
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
        return Collections.unmodifiableMap(encodeMap);
    }
}