package com.ethlo.r7.util;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.CollectionType;

public class JsonUtil
{
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();


    public static String writeValueAsString(Object data)
    {
        return MAPPER.writeValueAsString(data);
    }

    public static void writeValue(Path file, Object data)
    {
        MAPPER.writeValue(file, data);
    }

    public static CollectionType collectionType(Class<? extends Collection> coll, Class<?> type)
    {
        return MAPPER.getTypeFactory().constructCollectionType(coll, type);
    }

    public static <T> List<T> readValue(Path file, CollectionType collectionType)
    {
        return MAPPER.readValue(file, collectionType);
    }
}
