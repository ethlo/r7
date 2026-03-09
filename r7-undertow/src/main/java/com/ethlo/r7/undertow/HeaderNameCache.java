package com.ethlo.r7.undertow;

import com.ethlo.r7.undertow.util.HttpStringUtil;
import com.ethlo.r7.util.HttpStringCharSequence;
import io.undertow.util.HttpString;

class HeaderNameCache
{
    // Must be a power of 2
    private static final int CACHE_SIZE = 1024;
    private static final int MASK = CACHE_SIZE - 1;

    // An array of references. Reading/writing references in Java is atomic.
    private static final Entry[] CACHE = new Entry[CACHE_SIZE];

    public static HttpStringCharSequence wrap(HttpString hm)
    {
        // Bitwise AND handles negative hash codes safely and maps directly to an array index
        int idx = hm.hashCode() & MASK;
        Entry entry = CACHE[idx];

        // Fast path: Cache hit.
        // We check identity (==) first because Undertow internally
        // caches standard HttpString instances, making it an ultra-fast check.
        if (entry != null && (entry.key == hm || entry.key.equals(hm)))
        {
            return entry.value;
        }

        // Slow path: Cache miss. Allocate wrapper and update cache.
        final HttpStringCharSequence wrapped = new HttpStringCharSequence(hm, hm.hashCode(), HttpStringUtil.getBytes(hm));

        // Racy write: It is perfectly fine if multiple threads overwrite this exact
        // index at the same time. The JVM handles atomic reference writes.
        CACHE[idx] = new Entry(hm, wrapped);

        return wrapped;
    }

    private record Entry(HttpString key, HttpStringCharSequence value)
    {
    }
}
