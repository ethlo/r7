package com.ethlo.r7.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The YAML snippets in the user docs ({@code docs/} and {@code README.md}), so that the modules
 * owning each config file can load every snippet with the same loader and validation r7 runs at
 * startup. A snippet that no longer loads is a doc that no longer tells the truth.
 * <p>
 * What a snippet is checked as is written in the fence itself:
 * <ul>
 *     <li>{@code ```yaml title="server.yaml"}: the whole of that file. The title is also the
 *     file name the docs site shows above the snippet. {@link #FILES} lists the names a title
 *     can take.</li>
 *     <li>{@code ```yaml} with no title: part of a {@code routes.yaml}, either top-level keys
 *     ({@code routes}, {@code global_filters}, ...) or the keys of one route ({@code match},
 *     {@code filters}, {@code journal}, ...). The gateway's docs test completes it with a
 *     placeholder route before loading it.</li>
 *     <li>{@code <!-- docs-check: skip -->} on the line right above the fence: not checked. For
 *     a snippet that is wrong on purpose; keep these rare, since nothing else stops them
 *     rotting.</li>
 * </ul>
 */
public final class DocSnippets
{
    public static final String ROUTES = "routes.yaml";
    public static final String SERVER = "server.yaml";
    public static final String TAILER = "tailer.yaml";
    public static final String REAPER = "reaper.yaml";

    /**
     * Only parsed: a Compose file is Docker's format, and the r7 config it mounts is checked in
     * its own snippets.
     */
    public static final String DOCKER_COMPOSE = "docker-compose.yaml";

    public static final Set<String> FILES = Set.of(ROUTES, SERVER, TAILER, REAPER, DOCKER_COMPOSE);

    /**
     * Values for the {@code ${VAR}} references the snippets make without a default, which would
     * otherwise fail as a missing environment variable. Set as system properties, which
     * {@code EnvInterpolator} falls back to, by {@link #setVariables()}.
     */
    public static final Map<String, String> VARIABLES = Map.of(
            "ALICE_HTPASSWD_ENTRY", "$2y$12$agcM9nDVmZGTJPT.ldejs.zoYitvQGSKw4FIG2Bt9bpsYf89eaeLG",
            "BOB_HTPASSWD_ENTRY", "$2y$12$agcM9nDVmZGTJPT.ldejs.zoYitvQGSKw4FIG2Bt9bpsYf89eaeLG",
            "R7_FINGERPRINT_KEY", "docs-check-fingerprint-key-of-at-least-32-characters"
    );

    private static final Pattern FENCE = Pattern.compile("^(\\s*)(`{3,}|~{3,})\\s*([^`\\s]*)(.*)$");
    private static final Pattern TITLE = Pattern.compile("title=\"([^\"]*)\"");
    private static final Pattern SKIP = Pattern.compile("^\\s*<!--\\s*docs-check:\\s*skip\\b.*-->\\s*$");

    private DocSnippets()
    {
    }

    /**
     * Every YAML snippet that is not marked to be skipped.
     */
    public static List<DocSnippet> all()
    {
        final Path root = repositoryRoot();
        final List<Path> sources = new ArrayList<>();
        sources.add(root.resolve("README.md"));
        try (Stream<Path> docs = Files.walk(root.resolve("docs")))
        {
            docs.filter(path -> path.toString().endsWith(".md")).sorted().forEach(sources::add);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }

        final List<DocSnippet> snippets = new ArrayList<>();
        for (final Path source : sources)
        {
            snippets.addAll(read(root, source));
        }
        return snippets;
    }

    /**
     * The snippets titled with {@code file}, or, for {@code null}, the untitled ones.
     */
    public static List<DocSnippet> of(final String file)
    {
        return all().stream()
                .filter(snippet -> file == null ? snippet.title() == null : file.equals(snippet.title()))
                .toList();
    }

    public static void setVariables()
    {
        VARIABLES.forEach(System::setProperty);
    }

    public static void clearVariables()
    {
        VARIABLES.keySet().forEach(System::clearProperty);
    }

    /**
     * Found by walking up from the working directory, which is a module directory (or below one)
     * when the tests run.
     */
    public static Path repositoryRoot()
    {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent())
        {
            if (Files.isRegularFile(dir.resolve("mkdocs.yml")) && Files.isDirectory(dir.resolve("docs")))
            {
                return dir;
            }
        }
        throw new IllegalStateException("No repository root (a directory with mkdocs.yml and docs/) above " + Path.of("").toAbsolutePath());
    }

    private static List<DocSnippet> read(final Path root, final Path source)
    {
        final List<String> lines;
        try
        {
            lines = Files.readAllLines(source);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }

        final String name = root.relativize(source).toString();
        final List<DocSnippet> snippets = new ArrayList<>();
        int i = 0;
        while (i < lines.size())
        {
            final Matcher open = FENCE.matcher(lines.get(i));
            if (!open.matches())
            {
                i++;
                continue;
            }

            final int indent = open.group(1).length();
            final String fence = open.group(2);
            final String language = open.group(3);
            final Matcher title = TITLE.matcher(open.group(4));
            final boolean skipped = i > 0 && SKIP.matcher(lines.get(i - 1)).matches();
            final int start = i + 1;

            final StringBuilder body = new StringBuilder();
            i++;
            while (i < lines.size() && !isClosing(lines.get(i), fence))
            {
                final String line = lines.get(i);
                body.append(line, Math.min(indent, leadingSpaces(line)), line.length()).append('\n');
                i++;
            }
            i++;

            if (!skipped && (language.equals("yaml") || language.equals("yml")))
            {
                snippets.add(new DocSnippet(name, start, title.find() ? title.group(1) : null, body.toString()));
            }
        }
        return snippets;
    }

    private static boolean isClosing(final String line, final String fence)
    {
        final String trimmed = line.strip();
        return trimmed.startsWith(fence) && trimmed.chars().allMatch(c -> c == fence.charAt(0));
    }

    private static int leadingSpaces(final String line)
    {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ')
        {
            n++;
        }
        return n;
    }
}
