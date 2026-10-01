package com.ethlo.r7.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.filters.StaticContentFactory;

/**
 * Serves the {@code StaticContent} filter's files, so that a route behaves the same on every
 * server (the static content kit checks it):
 * <ul>
 *   <li>a path segment starting with '.' is answered 404, as if absent, unless the route opts in;
 *   {@code .well-known/} is always served;</li>
 *   <li>the base directory may itself be a symbolic link (an atomically swapped release), but a
 *   link under it is followed only with {@code follow_symlinks};</li>
 *   <li>a base directory that is momentarily missing is a clean 404, not an error;</li>
 *   <li>a directory is served by its welcome file, else listed when the route allows it, else
 *   403; one addressed without its trailing slash is redirected to it;</li>
 *   <li>every answer carries {@code X-Content-Type-Options: nosniff}: the type is guessed from
 *   the extension, and a browser must not second-guess it.</li>
 * </ul>
 * GET and HEAD only; conditional requests by ETag and Last-Modified, and a single byte range.
 */
public final class StaticFiles
{
    private static final String NOSNIFF = "nosniff";
    private static final List<String> WELCOME_FILES = List.of("index.html", "index.htm", "default.html", "default.htm");
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html"), Map.entry("htm", "text/html"), Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"), Map.entry("mjs", "text/javascript"), Map.entry("json", "application/json"),
            Map.entry("map", "application/json"), Map.entry("txt", "text/plain"), Map.entry("xml", "application/xml"),
            Map.entry("csv", "text/csv"), Map.entry("md", "text/markdown"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"), Map.entry("otf", "font/otf"), Map.entry("pdf", "application/pdf"),
            Map.entry("wasm", "application/wasm"), Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("ogg", "audio/ogg"), Map.entry("wav", "audio/wav"),
            Map.entry("zip", "application/zip"), Map.entry("gz", "application/gzip"), Map.entry("tar", "application/x-tar"),
            Map.entry("webmanifest", "application/manifest+json"));

    private StaticFiles()
    {
    }

    /**
     * What a server's exchange provides to answer a static request.
     */
    public interface Target
    {
        String method();

        /**
         * The request path after filters, decoded: what is resolved under the base directory.
         */
        String path();

        /**
         * The path the client sent, raw, with the query: where a redirect sends it.
         */
        String clientTarget();

        GatewayHeaders requestHeaders();

        /**
         * Answers with a status, the headers set so far and an in-memory body (possibly empty).
         */
        void answer(int status, byte[] body);

        /**
         * Answers with a status, the headers set so far and {@code length} bytes of the file from
         * {@code offset}; HEAD sends the headers only.
         */
        void answerFile(int status, Path file, long offset, long length, boolean headOnly) throws IOException;

        void header(String name, String value);
    }

    public static void serve(final Target target, final StaticContentFactory.StaticServeRequest request) throws IOException
    {
        target.header("X-Content-Type-Options", NOSNIFF);

        final String method = target.method();
        if (!method.equals("GET") && !method.equals("HEAD"))
        {
            target.header("Allow", "GET, HEAD");
            target.answer(405, new byte[0]);
            return;
        }

        final String path = target.path();
        final String relative = path.startsWith("/") ? path.substring(1) : path;
        if (!request.serveHiddenFiles() && StaticContentFactory.StaticServeRequest.isHidden(relative))
        {
            target.answer(404, new byte[0]);
            return;
        }

        final Path base = request.baseDirectory();
        if (!Files.isDirectory(base))
        {
            // Momentarily missing, e.g. mid atomic swap: a clean 404, never a hang or a 500.
            target.answer(404, "Static content directory unavailable".getBytes(StandardCharsets.UTF_8));
            return;
        }

        final Path resolved = resolve(base, relative, request.followSymlinks());
        if (resolved == null || !Files.exists(resolved))
        {
            target.answer(404, new byte[0]);
            return;
        }

        if (Files.isDirectory(resolved))
        {
            if (!relative.isEmpty() && !relative.endsWith("/"))
            {
                target.header("Location", withTrailingSlash(target.clientTarget()));
                target.answer(302, new byte[0]);
                return;
            }
            for (final String welcome : WELCOME_FILES)
            {
                final Path file = resolved.resolve(welcome);
                if (Files.isRegularFile(file) && (request.followSymlinks() || !Files.isSymbolicLink(file)))
                {
                    serveFile(target, file, method.equals("HEAD"));
                    return;
                }
            }
            if (request.listDirectory())
            {
                target.header("Content-Type", "text/html; charset=utf-8");
                target.answer(200, listing(resolved, path, request.serveHiddenFiles()));
                return;
            }
            target.answer(403, new byte[0]);
            return;
        }

        if (!Files.isRegularFile(resolved))
        {
            target.answer(404, new byte[0]);
            return;
        }
        serveFile(target, resolved, method.equals("HEAD"));
    }

    /**
     * The file {@code relative} names under {@code base}, or null when it would leave the base
     * directory or pass through a symbolic link that may not be followed. The base itself may be
     * a link: an atomically swapped release directory is, and is still the configured root.
     */
    public static Path resolve(final Path base, final String relative, final boolean followSymlinks)
    {
        final Path root = base.toAbsolutePath().normalize();
        final Path resolved;
        try
        {
            resolved = root.resolve(relative).normalize();
        }
        catch (final InvalidPathException e)
        {
            return null;
        }
        if (!resolved.startsWith(root))
        {
            return null;
        }
        if (!followSymlinks)
        {
            Path current = root;
            for (final Path segment : root.relativize(resolved))
            {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current))
                {
                    return null;
                }
            }
        }
        return resolved;
    }

    private static void serveFile(final Target target, final Path file, final boolean headOnly) throws IOException
    {
        final BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        final long size = Files.size(file);
        final Instant modified = attributes.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.SECONDS);
        final String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(modified.getEpochSecond()) + "\"";

        target.header("Content-Type", contentType(file));
        target.header("ETag", etag);
        target.header("Last-Modified", HTTP_DATE.format(modified));
        target.header("Accept-Ranges", "bytes");

        if (notModified(target.requestHeaders(), etag, modified))
        {
            target.answer(304, new byte[0]);
            return;
        }

        final String range = target.requestHeaders().getFirst("Range");
        final String ifRange = target.requestHeaders().getFirst("If-Range");
        if (range != null && (ifRange == null || ifRange.equals(etag)))
        {
            final long[] span = parseRange(range, size);
            if (span == null)
            {
                // Not a single byte range we understand: serve the whole file, as allowed.
                target.answerFile(200, file, 0, size, headOnly);
                return;
            }
            if (span.length == 0)
            {
                target.header("Content-Range", "bytes */" + size);
                target.answer(416, new byte[0]);
                return;
            }
            target.header("Content-Range", "bytes " + span[0] + "-" + (span[0] + span[1] - 1) + "/" + size);
            target.answerFile(206, file, span[0], span[1], headOnly);
            return;
        }
        target.answerFile(200, file, 0, size, headOnly);
    }

    /**
     * RFC 9110 §13.1.2-3: If-None-Match wins when present; If-Modified-Since only without it.
     */
    private static boolean notModified(final GatewayHeaders headers, final String etag, final Instant modified)
    {
        final String ifNoneMatch = headers.getFirst("If-None-Match");
        if (ifNoneMatch != null)
        {
            for (final String candidate : ifNoneMatch.split(","))
            {
                final String tag = candidate.trim();
                if (tag.equals("*") || tag.equals(etag) || tag.equals("W/" + etag))
                {
                    return true;
                }
            }
            return false;
        }
        final String ifModifiedSince = headers.getFirst("If-Modified-Since");
        if (ifModifiedSince != null)
        {
            try
            {
                final Instant since = ZonedDateTime.parse(ifModifiedSince, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return !modified.isAfter(since);
            }
            catch (final DateTimeParseException ignored)
            {
                // An unparseable date is ignored (RFC 9110 §13.1.3).
            }
        }
        return false;
    }

    /**
     * One {@code bytes=} range as {offset, length}; an empty array when it cannot be satisfied;
     * null when the header is not a single byte range, which is served as a whole file.
     */
    static long[] parseRange(final String header, final long size)
    {
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0)
        {
            return null;
        }
        final String spec = header.substring(6).trim();
        final int dash = spec.indexOf('-');
        if (dash < 0)
        {
            return null;
        }
        try
        {
            final String first = spec.substring(0, dash).trim();
            final String last = spec.substring(dash + 1).trim();
            if (first.isEmpty())
            {
                // Suffix: the last N bytes.
                final long suffix = Long.parseLong(last);
                if (suffix <= 0 || size == 0)
                {
                    return new long[0];
                }
                final long length = Math.min(suffix, size);
                return new long[]{size - length, length};
            }
            final long start = Long.parseLong(first);
            if (start < 0 || start >= size)
            {
                return new long[0];
            }
            final long end = last.isEmpty() ? size - 1 : Math.min(Long.parseLong(last), size - 1);
            if (end < start)
            {
                return null;
            }
            return new long[]{start, end - start + 1};
        }
        catch (final NumberFormatException e)
        {
            return null;
        }
    }

    static String contentType(final Path file)
    {
        final String name = file.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        final String type = dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        // No charset: a file's encoding is not known from its name, and Undertow adds none either.
        return type == null ? "application/octet-stream" : type;
    }

    public static String withTrailingSlash(final String target)
    {
        final int query = target.indexOf('?');
        return query < 0 ? target + "/" : target.substring(0, query) + "/" + target.substring(query);
    }

    public static byte[] listing(final Path directory, final String path, final boolean showHidden) throws IOException
    {
        final List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory))
        {
            for (final Path entry : stream)
            {
                final String name = entry.getFileName().toString();
                if (!showHidden && name.startsWith(".") && !name.equals(".well-known"))
                {
                    continue;
                }
                entries.add(Files.isDirectory(entry) ? name + "/" : name);
            }
        }
        entries.sort(String::compareTo);
        final StringBuilder html = new StringBuilder(256).append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Index of ")
                .append(escape(path)).append("</title></head><body><h1>Index of ").append(escape(path)).append("</h1><ul>");
        if (!path.equals("/"))
        {
            html.append("<li><a href=\"../\">../</a></li>");
        }
        for (final String entry : entries)
        {
            html.append("<li><a href=\"").append(escape(encodeSegment(entry))).append("\">").append(escape(entry)).append("</a></li>");
        }
        return html.append("</ul></body></html>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String encodeSegment(final String name)
    {
        final StringBuilder sb = new StringBuilder();
        for (final byte b : name.getBytes(StandardCharsets.UTF_8))
        {
            final int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~/".indexOf(c) >= 0)
            {
                sb.append((char) c);
            }
            else
            {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }

    private static String escape(final String text)
    {
        final StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            switch (c)
            {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
