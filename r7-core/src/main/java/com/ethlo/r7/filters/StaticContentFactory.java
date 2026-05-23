package com.ethlo.r7.filters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.api.UpstreamRequestGatewayExchange;
import com.ethlo.r7.api.UpstreamRequestGatewayFilter;
import com.ethlo.r7.spi.FilterCreationContext;
import com.ethlo.r7.spi.GatewayFilterFactory;
import com.ethlo.r7.util.FastTerminationGatewayResponse;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.util.constants.HttpStatuses;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayFilterFactory.class)
public final class StaticContentFactory implements GatewayFilterFactory<StaticContentFactory.Config>
{
    public static final String STATIC_CONTENT_PATH_KEY = "gateway.internal.serve_static.path";
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

    public record Config(String baseDirectory) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result);
            final Path dirPath = Paths.get(this.baseDirectory());

            if (!Files.isDirectory(dirPath))
            {
                validator.invalid("base_directory", this.baseDirectory(), "Directory " + dirPath.toAbsolutePath() + " not found");
            }
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
            exchange.attributes().set(STATIC_CONTENT_PATH_KEY, this.config.baseDirectory());
            exchange.shortCircuit(new FastTerminationGatewayResponse(HttpStatuses.OK, null, null));
        }
    }
}