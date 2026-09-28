package com.ethlo.r7.util;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ethlo.r7.doc.Sensitive;
import com.ethlo.r7.journal.HeaderNameSet;

/**
 * Masks the {@link Sensitive} values in a filter's raw configuration before it is rendered.
 */
public final class SensitiveConfig
{
    public static final String MASK = "******";

    private SensitiveConfig()
    {
    }

    /**
     * @param configClass the filter's configuration record
     * @param args        the filter's arguments as parsed from YAML (snake_case keys)
     * @param safeHeaders request headers whose values may be shown
     * @return {@code args} itself when nothing needs masking, otherwise a masked copy
     */
    public static Object mask(final Class<?> configClass, final Object args, final HeaderNameSet safeHeaders)
    {
        if (!(args instanceof Map<?, ?> map) || configClass == null || !configClass.isRecord())
        {
            return args;
        }

        Map<Object, Object> masked = null;
        for (final RecordComponent component : configClass.getRecordComponents())
        {
            final Sensitive sensitive = component.getAnnotation(Sensitive.class);
            if (sensitive == null)
            {
                continue;
            }
            final String key = snakeCase(component.getName());
            if (!map.containsKey(key) || isShownHeaderValue(sensitive, map, safeHeaders))
            {
                continue;
            }
            if (masked == null)
            {
                masked = new LinkedHashMap<>(map);
            }
            masked.put(key, MASK);
        }
        return masked != null ? masked : args;
    }

    private static boolean isShownHeaderValue(final Sensitive sensitive, final Map<?, ?> args, final HeaderNameSet safeHeaders)
    {
        if (sensitive.unlessSafeHeaderIn().isEmpty())
        {
            return false;
        }
        final Object headerName = args.get(snakeCase(sensitive.unlessSafeHeaderIn()));
        return headerName instanceof String name && safeHeaders.contains(name);
    }

    static String snakeCase(final String camelCase)
    {
        final StringBuilder sb = new StringBuilder(camelCase.length() + 4);
        for (int i = 0; i < camelCase.length(); i++)
        {
            final char c = camelCase.charAt(i);
            if (Character.isUpperCase(c))
            {
                sb.append('_').append(Character.toLowerCase(c));
            }
            else
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
