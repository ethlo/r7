package com.ethlo.r7.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ErrorMessages
{

    public static final ByteBuffer NO_ROUTE = ByteBuffer.wrap("No route found for request".getBytes(StandardCharsets.UTF_8));
}
