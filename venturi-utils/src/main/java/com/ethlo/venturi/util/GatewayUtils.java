package com.ethlo.venturi.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ethlo.venturi.api.GatewayHeaders;

public class GatewayUtils
{
    public static Map<String, List<String>> toMap(GatewayHeaders headers)
    {
        final Map<String, List<String>> map = new HashMap<>();
        headers.forEach((name, value) ->
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
