package com.ethlo.venturi.constants;

/**
 * Common HTTP Media Types (MIME types) for Java 25.
 * This utility provides constants for the most frequently used IANA media types.
 */
public final class MediaTypes
{

    // --- Application Types ---
    public static final String APPLICATION_JSON = "application/json";
    public static final String APPLICATION_XML = "application/xml";
    public static final String APPLICATION_PDF = "application/pdf";
    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    public static final String APPLICATION_X_WWW_FORM_URLENCODED = "application/x-www-form-urlencoded";
    public static final String APPLICATION_ZIP = "application/zip";
    public static final String APPLICATION_GRPC = "application/grpc"; // Common in modern microservices
    // --- Text Types ---
    public static final String TEXT_PLAIN = "text/plain";
    public static final String TEXT_HTML = "text/html";
    public static final String TEXT_CSS = "text/css";
    public static final String TEXT_CSV = "text/csv";
    public static final String TEXT_JAVASCRIPT = "text/javascript";
    public static final String TEXT_MARKDOWN = "text/markdown";
    // --- Image Types ---
    public static final String IMAGE_PNG = "image/png";
    public static final String IMAGE_JPEG = "image/jpeg";
    public static final String IMAGE_GIF = "image/gif";
    public static final String IMAGE_SVG_XML = "image/svg+xml";
    public static final String IMAGE_WEBP = "image/webp";
    public static final String IMAGE_AVIF = "image/avif"; // Modern high-efficiency format
    // --- Multipart Types ---
    public static final String MULTIPART_FORM_DATA = "multipart/form-data";
    public static final String MULTIPART_MIXED = "multipart/mixed";
    // Private constructor to prevent instantiation
    private MediaTypes()
    {
    }
}