package com.ethlo.venturi.vlf.dictionary;

import java.util.Map;

public interface VlfDictionary
{
    byte encode(byte[] value);

    CharSequence decode(byte id);

    Map<CharSequence, Byte> getEntries();
}
