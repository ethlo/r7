package com.ethlo.r7.filters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.ethlo.r7.api.ShortInfo;
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
    public static final String STATIC_CONTENT_PATH_KEY = "gateway.internal.serve_static.path";
    public static final String STATIC_CONTENT_FOLLOW_SYMLINKS_KEY = "gateway.internal.serve_static.follow_symlinks";
    public static final String STATIC_CONTENT_LIST_DIRECTORY_KEY = "gateway.internal.serve_static.list_directory";
    private static final String FILTER_NAME = "StaticContent";

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
            exchange.attributes().set(STATIC_CONTENT_PATH_KEY, this.config.baseDirectory().toString());
            exchange.attributes().set(STATIC_CONTENT_FOLLOW_SYMLINKS_KEY, this.config.followSymlinks().toString());
            exchange.attributes().set(STATIC_CONTENT_LIST_DIRECTORY_KEY, this.config.listDirectory().toString());
            exchange.shortCircuit(new ShortCircuitGatewayResponse(HttpStatuses.OK, null, null));
        }
    }
}