package com.ethlo.venturi.core;

import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.util.ArrayBackedPairStorage;
import com.ethlo.venturi.util.CharSequenceUtil;

/**
 * High-performance, low-allocation implementation of GatewayAttributes.
 * Perfectly suited for request context and filter state.
 */
public final class FastGatewayAttributes extends ArrayBackedPairStorage<CharSequence, Object> implements GatewayAttributes
{
    public FastGatewayAttributes()
    {
        // Initial capacity of 8 pairs (16 slots)
        super(8);
    }

    @Override
    protected boolean keysEqual(CharSequence requestedKey, CharSequence storedKey)
    {
        return CharSequenceUtil.equals(requestedKey, storedKey);
    }

    @Override
    public Iterable<CharSequence> attributeNames()
    {
        return getKeysInternal();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(CharSequence key)
    {
        return (T) getFirstInternal(key);
    }

    @Override
    public void put(CharSequence key, Object value)
    {
        // Attributes behave like a Map: update if exists, otherwise add
        setInternal(key, value);
    }

    /**
     * Optional: check if an attribute exists without potentially casting
     */
    public boolean contains(CharSequence key)
    {
        return getFirstInternal(key) != null;
    }
}