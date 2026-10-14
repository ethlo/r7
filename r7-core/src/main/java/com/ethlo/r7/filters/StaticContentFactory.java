package com.ethlo.r7.filters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.StateKey;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.ShortCircuitGatewayResponse;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
@Description("Serves static files from local directories, the first that has a file answering.")
public final class StaticContentFactory implements GatewayFilterFactory<StaticContentFactory.Config>
{
    /**
     * Functional handoff to the server, telling it which base directory (and options) to serve
     * the response from. Deliberately a {@link StateKey} attachment,
     * not an {@code attributes()} entry - this is routing state, not telemetry, and must not
     * leak the server's filesystem layout into the journal/log output.
     */
    public static final StateKey<StaticServeRequest> STATIC_SERVE_REQUEST_KEY = new StateKey<>("serve_static.request");
    private static final String FILTER_NAME = "StaticContent";

    /**
     * @param directories      the directories to serve files from, the first that has a file winning
     * @param followSymlinks   whether to follow symbolic links when resolving files
     * @param serveHiddenFiles whether a path segment starting with '.' may be served
     * @param fallback         the file, relative to the directories, served for a path none of them has; null for 404
     */
    public record StaticServeRequest(List<Path> directories, boolean followSymlinks, boolean serveHiddenFiles, String fallback)
    {
        /**
         * Whether {@code relativePath} names a hidden file or directory: any segment starting with
         * '.', such as {@code .env} or {@code .git/config}. {@code .well-known/} is not hidden -
         * RFC 8615 puts ACME challenges and security.txt there.
         */
        private static final String WELL_KNOWN = ".well-known";

        public static boolean isHidden(final String relativePath)
        {
            int segmentStart = 0;
            final int len = relativePath.length();
            for (int i = 0; i <= len; i++)
            {
                if (i == len || relativePath.charAt(i) == '/')
                {
                    final int length = i - segmentStart;
                    final boolean wellKnown = length == WELL_KNOWN.length() && relativePath.startsWith(WELL_KNOWN, segmentStart);
                    if (length > 0 && relativePath.charAt(segmentStart) == '.' && !wellKnown)
                    {
                        return true;
                    }
                    segmentStart = i + 1;
                }
            }
            return false;
        }
    }

    @Override
    public String name()
    {
        return FILTER_NAME;
    }

    @Override
    public Class<Config> configClass()
    {
        return Config.class;
    }

    @Override
    public UpstreamRequestGatewayFilter create(final Config config, final FilterCreationContext filterCreationContext)
    {
        return new GF(config);
    }

    public record Config(
            @Description("The directories to serve files from, in order: a request is answered by the first one that " +
                    "has the file, or a welcome file for the directory asked for, so a higher directory overrides a lower " +
                    "one file by file. One directory is a list of one.")
            List<Path> directories,

            @Nullable
            @Description("Whether to follow symbolic links when resolving files under the base directory. " +
                    "Enable this if the base directory itself, or files/directories within it, are symlinks " +
                    "(e.g. an atomically swapped 'current' release symlink).")
            @DefaultValue("false")
            Boolean followSymlinks,

            @Nullable
            @Description("Whether to serve files and directories whose name starts with '.' (e.g. .env, .git/). " +
                    "Disabled by default, in which case such a request is answered 404; .well-known/ is always served.")
            @DefaultValue("false")
            Boolean serveHiddenFiles,

            @Nullable
            @Description("A file, relative to the directories and looked up in their order, served with 200 for a path " +
                    "none of them has, instead of 404: index.html for a single-page app whose routes the browser handles.")
            String fallback) implements ValidatableConfig
    {
        @Override
        public Boolean serveHiddenFiles()
        {
            return Optional.ofNullable(this.serveHiddenFiles).orElse(false);
        }

        @Override
        public Boolean followSymlinks()
        {
            return Optional.ofNullable(this.followSymlinks).orElse(false);
        }

        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);
            validator.required("directories", this.directories).ifValid(() ->
            {
                if (this.directories.isEmpty())
                {
                    validator.invalid("directories", "[]", "List at least one directory");
                    return;
                }
                final Set<Path> seen = new HashSet<>();
                for (final Path directory : this.directories)
                {
                    if (directory == null || !Files.isDirectory(directory))
                    {
                        validator.invalid("directories", String.valueOf(directory), "Directory " + (directory == null ? null : directory.toAbsolutePath()) + " not found");
                    }
                    else if (!seen.add(directory.toAbsolutePath().normalize()))
                    {
                        validator.invalid("directories", directory.toString(), "Directory " + directory.toAbsolutePath() + " is listed more than once");
                    }
                }
                if (this.fallback != null && !result.hasErrors())
                {
                    validateFallback(this, validator);
                }
            });
        }
    }

    private static void validateFallback(final Config config, final ValidatorUtils validator)
    {
        final String file = config.fallback().startsWith("/") ? config.fallback().substring(1) : config.fallback();
        if (file.isEmpty() || file.endsWith("/") || Arrays.stream(file.split("/")).anyMatch(segment -> segment.isEmpty() || segment.equals("..")))
        {
            validator.invalid("fallback", config.fallback(), "Must name a file under the directories, such as index.html");
        }
        else if (!config.serveHiddenFiles() && StaticServeRequest.isHidden(file))
        {
            validator.invalid("fallback", config.fallback(), "Names a hidden file, which this route does not serve");
        }
        else if (config.directories().stream().noneMatch(directory -> Files.isRegularFile(directory.resolve(file))))
        {
            validator.invalid("fallback", config.fallback(), "No directory has " + file);
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final StaticServeRequest request;

        public GF(final Config config)
        {
            this.request = new StaticServeRequest(List.copyOf(config.directories()), config.followSymlinks(), config.serveHiddenFiles(),
                    config.fallback() == null || !config.fallback().startsWith("/") ? config.fallback() : config.fallback().substring(1));
        }

        @Override
        public String name()
        {
            return FILTER_NAME;
        }

        @Override
        public String summary()
        {
            return FILTER_NAME;
        }

        @Override
        public void onUpstreamRequest(final UpstreamRequestGatewayExchange exchange)
        {
            exchange.setAttachment(STATIC_SERVE_REQUEST_KEY, this.request);
            exchange.shortCircuit(new ShortCircuitGatewayResponse(HttpStatuses.OK, null, null));
        }
    }
}