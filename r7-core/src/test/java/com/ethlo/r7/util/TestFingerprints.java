package com.ethlo.r7.util;

import java.util.Map;

import com.ethlo.r7.spi.EngineContext;

/**
 * The fingerprint key tests run under: a gateway does not start without one, so neither does
 * anything a test builds that fingerprints.
 */
public final class TestFingerprints
{
    public static final String KEY = "test-fingerprint-key-of-at-least-32-characters";
    public static final Fingerprint FINGERPRINT = Fingerprint.of(KEY);

    private TestFingerprints()
    {
    }

    public static EngineContext engine()
    {
        return new EngineContext(Map.of(Fingerprint.class, FINGERPRINT));
    }
}
