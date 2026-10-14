package com.ethlo.r7.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.filters.StaticContentFactory;

/**
 * Serves the {@code StaticContent} filter's files, so that a route behaves the same on every
 * server (the static content kit checks it):
 * <ul>
 *   <li>a path segment starting with '.' is answered 404, as if absent, unless the route opts in;
 *   {@code .well-known/} is always served;</li>
 *   <li>the directories are checked in order, and the first that has the file, or a welcome file
 *   for the directory asked for, answers: a higher one hides a lower one file by file;</li>
 *   <li>a directory may itself be a symbolic link (an atomically swapped release), but a link
 *   under it is followed only with {@code follow_symlinks};</li>
 *   <li>a directory that is momentarily missing is skipped; when all are, the answer is a clean
 *   404, not an error;</li>
 *   <li>a directory that was deleted while a mount still holds it (a bind mount of the directory
 *   itself, which then was replaced) is skipped with a warning in the log, and the answer is a 503
 *   when no other directory exists;</li>
 *   <li>a directory is served by its welcome file, else 403; one addressed without its trailing
 *   slash is redirected to it;</li>
 *   <li>a path no directory has is 404, or the route's fallback file (a single-page app's
 *   index.html) with 200;</li>
 *   <li>every answer carries {@code X-Content-Type-Options: nosniff}: the type is guessed from
 *   the extension, and a browser must not second-guess it.</li>
 * </ul>
 * GET and HEAD only; conditional requests by ETag and Last-Modified, and a single byte range.
 */
public final class StaticFiles
{
    private static final Logger logger = LoggerFactory.getLogger(StaticFiles.class);
    private static final String NOSNIFF = "nosniff";
    private static final String UNAVAILABLE = "Static content directory unavailable";
    private static final Set<Path> warnedUnlinked = ConcurrentHashMap.newKeySet();
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

        // The first directory that can answer wins: a file, a welcome file, or the redirect a
        // directory without its slash gets. Each is checked as one directory used to be.
        boolean present = false;
        boolean unlinked = false;
        boolean directory = false;
        for (final Path base : request.directories())
        {
            if (!Files.isDirectory(base))
            {
                // Momentarily missing, e.g. mid atomic swap: skipped, never masking the rest.
                continue;
            }
            if (isUnlinked(base))
            {
                // The path still opens, but on a directory that no longer exists: a mount holds the
                // one that was replaced. Nothing here will ever be served again, so say so.
                if (warnedUnlinked.add(base))
                {
                    logger.warn("Static content directory {} was deleted, but a mount still holds it (a bind mount of the directory itself, "
                            + "replaced on the host). Mount its parent directory instead, or restart to see the new one.", base);
                }
                unlinked = true;
                continue;
            }
            present = true;

            final Path resolved = resolve(base, relative, request.followSymlinks());
            if (resolved == null)
            {
                continue;
            }
            if (Files.isRegularFile(resolved))
            {
                logger.debug("Static '{}' served from {}", relative, base);
                serveFile(target, resolved, method.equals("HEAD"));
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
                final Path welcome = welcomeFile(resolved, request.followSymlinks());
                if (welcome != null)
                {
                    logger.debug("Static '{}' served from {}", relative, base);
                    serveFile(target, welcome, method.equals("HEAD"));
                    return;
                }
                directory = true;
            }
            // Anything else (a device node, a dangling link) is absent here: the next directory.
        }

        if (directory)
        {
            target.answer(403, new byte[0]);
        }
        else if (!present && unlinked)
        {
            target.answer(503, UNAVAILABLE.getBytes(StandardCharsets.UTF_8));
        }
        else if (!present)
        {
            // Every directory momentarily missing: a clean 404, never a hang or a 500.
            target.answer(404, UNAVAILABLE.getBytes(StandardCharsets.UTF_8));
        }
        else if (request.fallback() != null)
        {
            serveFallback(target, request, method.equals("HEAD"));
        }
        else
        {
            target.answer(404, new byte[0]);
        }
    }

    /**
     * A path no directory has, answered with the fallback file from the first directory that
     * has it: a single-page app's index.html, whose routes the browser then handles.
     */
    private static void serveFallback(final Target target, final StaticContentFactory.StaticServeRequest request, final boolean headOnly) throws IOException
    {
        for (final Path base : request.directories())
        {
            final Path file = Files.isDirectory(base) && !isUnlinked(base) ? resolve(base, request.fallback(), request.followSymlinks()) : null;
            if (file != null && Files.isRegularFile(file))
            {
                serveFile(target, file, headOnly);
                return;
            }
        }
        target.answer(404, new byte[0]);
    }

    private static Path welcomeFile(final Path directory, final boolean followSymlinks)
    {
        for (final String welcome : WELCOME_FILES)
        {
            final Path file = directory.resolve(welcome);
            if (Files.isRegularFile(file) && (followSymlinks || !Files.isSymbolicLink(file)))
            {
                return file;
            }
        }
        return null;
    }

    /**
     * Whether {@code directory} has no links left: deleted, and reached only through a mount (or
     * an open descriptor) that still holds it. False where the file system does not say.
     */
    static boolean isUnlinked(final Path directory)
    {
        try
        {
            return Files.getAttribute(directory, "unix:nlink") instanceof Integer links && links == 0;
        }
        catch (final UnsupportedOperationException | IllegalArgumentException | IOException e)
        {
            return false;
        }
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
}
