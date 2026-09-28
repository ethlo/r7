package com.ethlo.r7.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ErrorMessages
{

    public static final ByteBuffer NO_ROUTE = ByteBuffer.wrap("No route found for request".getBytes(StandardCharsets.UTF_8));

    public static final ByteBuffer AMBIGUOUS_PATH = ByteBuffer.wrap("Bad Request: ambiguous request path".getBytes(StandardCharsets.UTF_8));
}
