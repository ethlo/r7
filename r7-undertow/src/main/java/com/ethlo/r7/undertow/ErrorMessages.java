package com.ethlo.r7.undertow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ErrorMessages
{

    public static final ByteBuffer TRACE_NOT_SUPPORTED = ByteBuffer.wrap("Not Implemented: TRACE is not supported".getBytes(StandardCharsets.UTF_8));

    public static final ByteBuffer NO_ROUTE = ByteBuffer.wrap("No route found for request".getBytes(StandardCharsets.UTF_8));

    public static final ByteBuffer AMBIGUOUS_PATH = ByteBuffer.wrap("Bad Request: ambiguous request path".getBytes(StandardCharsets.UTF_8));

    public static final ByteBuffer UNSUPPORTED_TRANSFER_ENCODING = ByteBuffer.wrap("Bad Request: Transfer-Encoding must be exactly 'chunked'".getBytes(StandardCharsets.UTF_8));

    public static final ByteBuffer REGEX_BUDGET_EXCEEDED = ByteBuffer.wrap("Internal Server Error: a configured regular expression could not be evaluated for this request".getBytes(StandardCharsets.UTF_8));
}
