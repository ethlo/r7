package com.ethlo.r7.util.constants;

public final class HttpStatuses
{
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
    public static final int ENTITY_TOO_LARGE = 413;
    public static final int TOO_MANY_REQUESTS = 429;

    // 5xx Server Error
    public static final int INTERNAL_SERVER_ERROR = 500;
    public static final int NOT_IMPLEMENTED = 501;
    public static final int BAD_GATEWAY = 502;
    public static final int SERVICE_UNAVAILABLE = 503;
    public static final int GATEWAY_TIMEOUT = 504;

    private HttpStatuses()
    {
    }

    /**
     * The reason phrase registered for a status code (RFC 9110 and the IANA HTTP status code
     * registry), or an empty string for a code without one. A journal rebuilds status lines from
     * the code, so an unregistered code gets no phrase rather than an invented one.
     */
    public static String getReason(final int code)
    {
        return switch (code)
        {
            case 100 -> "Continue";
            case 101 -> "Switching Protocols";
            case 102 -> "Processing";
            case 103 -> "Early Hints";
            case OK -> "OK";
            case CREATED -> "Created";
            case ACCEPTED -> "Accepted";
            case 203 -> "Non-Authoritative Information";
            case NO_CONTENT -> "No Content";
            case 205 -> "Reset Content";
            case 206 -> "Partial Content";
            case 207 -> "Multi-Status";
            case 208 -> "Already Reported";
            case 226 -> "IM Used";
            case 300 -> "Multiple Choices";
            case MOVED_PERMANENTLY -> "Moved Permanently";
            case FOUND -> "Found";
            case 303 -> "See Other";
            case NOT_MODIFIED -> "Not Modified";
            case 305 -> "Use Proxy";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case BAD_REQUEST -> "Bad Request";
            case UNAUTHORIZED -> "Unauthorized";
            case 402 -> "Payment Required";
            case FORBIDDEN -> "Forbidden";
            case NOT_FOUND -> "Not Found";
            case METHOD_NOT_ALLOWED -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 407 -> "Proxy Authentication Required";
            case 408 -> "Request Timeout";
            case CONFLICT -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case ENTITY_TOO_LARGE -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 421 -> "Misdirected Request";
            case 422 -> "Unprocessable Content";
            case 423 -> "Locked";
            case 424 -> "Failed Dependency";
            case 425 -> "Too Early";
            case 426 -> "Upgrade Required";
            case 428 -> "Precondition Required";
            case TOO_MANY_REQUESTS -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 451 -> "Unavailable For Legal Reasons";
            case INTERNAL_SERVER_ERROR -> "Internal Server Error";
            case NOT_IMPLEMENTED -> "Not Implemented";
            case BAD_GATEWAY -> "Bad Gateway";
            case SERVICE_UNAVAILABLE -> "Service Unavailable";
            case GATEWAY_TIMEOUT -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            case 506 -> "Variant Also Negotiates";
            case 507 -> "Insufficient Storage";
            case 508 -> "Loop Detected";
            case 511 -> "Network Authentication Required";
            default -> "";
        };
    }

    public static boolean is2xx(int statusCode)
    {
        return statusCode >= 200 && statusCode < 300;
    }

    public static boolean is3xx(int statusCode)
    {
        return statusCode >= 300 && statusCode < 400;
    }

    public static boolean is4xx(int statusCode)
    {
        return statusCode >= 400 && statusCode < 500;
    }

    public static boolean is5xx(int statusCode)
    {
        return statusCode >= 500 && statusCode < 600;
    }
}