package com.ethlo.r7.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.r7.api.MultiAttributes;

public class GatewayUtils
{
    public static Map<String, List<String>> toMap(MultiAttributes attributes)
    {
        if (attributes == null)
        {
            return Map.of();
        }

        final Map<String, List<String>> map = new HashMap<>();
        attributes.forEach((name, value) ->
                {
                    map.compute(name.toString(), (k, values) -> {
                                if (values == null)
                                {
                                    values = new ArrayList<>(1);
                                }
                                values.add(value.toString());
                                return values;
                            }
                    );
                }
        );
        return map;
    }
}
