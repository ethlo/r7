package com.ethlo.r7.filters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
@Description("Serves static files from a local directory.")
public final class StaticContentFactory implements GatewayFilterFactory<StaticContentFactory.Config>
{
    /**
     * Functional handoff to {@code R7UndertowHandler}, telling it which base directory (and
     * options) to serve the response from natively. Deliberately a {@link StateKey} attachment,
     * not an {@code attributes()} entry - this is routing state, not telemetry, and must not
     * leak the server's filesystem layout into the journal/log output.
     */
    public static final StateKey<StaticServeRequest> STATIC_SERVE_REQUEST_KEY = new StateKey<>("serve_static.request");
    private static final String FILTER_NAME = "StaticContent";

    /**
     * @param baseDirectory  the directory to serve files from
     * @param followSymlinks whether to follow symbolic links when resolving files
     * @param listDirectory  whether to render an HTML directory listing when no welcome file is found
     */
    public record StaticServeRequest(Path baseDirectory, boolean followSymlinks, boolean listDirectory)
    {
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
            @Description("The absolute path to the directory containing static files.")
            Path baseDirectory,

            @Nullable
            @Description("Whether to follow symbolic links when resolving files under the base directory. " +
                    "Enable this if the base directory itself, or files/directories within it, are symlinks " +
                    "(e.g. an atomically swapped 'current' release symlink).")
            @DefaultValue("false")
            Boolean followSymlinks,

            @Nullable
            @Description("Whether to render an HTML directory listing when a request resolves to a directory " +
                    "and no welcome file (e.g. index.html) is found there. Disabled by default, in which case " +
                    "such a request is rejected with 403 Forbidden.")
            @DefaultValue("false")
            Boolean listDirectory) implements ValidatableConfig
    {
        @Override
        public Boolean followSymlinks()
        {
            return Optional.ofNullable(this.followSymlinks).orElse(false);
        }

        @Override
        public Boolean listDirectory()
        {
            return Optional.ofNullable(this.listDirectory).orElse(false);
        }

        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);
            validator.required("base_directory", baseDirectory)
                    .ifValid(() ->
                    {
                        if (!Files.isDirectory(baseDirectory()))
                        {
                            validator.invalid("base_directory", this.baseDirectory().toString(), "Directory " + baseDirectory().toAbsolutePath() + " not found");
                        }
                    });
        }
    }

    private static final class GF implements UpstreamRequestGatewayFilter, ShortInfo
    {
        private final Config config;

        public GF(final Config config)
        {
            this.config = config;
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
            exchange.setAttachment(STATIC_SERVE_REQUEST_KEY,
                    new StaticServeRequest(this.config.baseDirectory(), this.config.followSymlinks(), this.config.listDirectory()));
            exchange.shortCircuit(new ShortCircuitGatewayResponse(HttpStatuses.OK, null, null));
        }
    }
}