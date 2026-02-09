package com.ethlo.venturi.api;

public final class GatewayHttpStatus {
    // 2xx Success
    public static final int OK = 200;
    public static final int CREATED = 201;
    public static final int ACCEPTED = 202;
    public static final int NO_CONTENT = 204;

    // 3xx Redirection
    public static final int MOVED_PERMANENTLY = 301;
    public static final int FOUND = 302;
    public static final int NOT_MODIFIED = 304;

    // 4xx Client Error
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405;
    public static final int CONFLICT = 409;
    public static final int TOO_MANY_REQUESTS = 429;

    // 5xx Server Error
    public static final int INTERNAL_SERVER_ERROR = 500;
    public static final int NOT_IMPLEMENTED = 501;
    public static final int BAD_GATEWAY = 502;
    public static final int SERVICE_UNAVAILABLE = 503;
    public static final int GATEWAY_TIMEOUT = 504;

    private GatewayHttpStatus() {}
}