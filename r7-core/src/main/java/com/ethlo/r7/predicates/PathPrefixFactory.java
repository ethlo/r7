package com.ethlo.r7.predicates;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.FormatPattern;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayPredicateFactory.class)
@Description("Matches if the request path starts with the provided prefix.")
public final class PathPrefixFactory implements GatewayPredicateFactory<PathPrefixFactory.Config>
{
    private static final String PREDICATE_NAME = "PathPrefix";

    @Override
    public String name()
    {
        return PREDICATE_NAME;
    }

    @Override
    public Class<Config> configClass()
    {
        return Config.class;
    }

    @Override
    public GatewayPredicate create(final Config config)
    {
        return new GP(config);
    }

    public record Config(
            @Description("The path prefix to match.")
            @FormatPattern("^/.*$")
            String prefix) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result).required("prefix", this.prefix());
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String prefix;

        public GP(final Config config)
        {
            this.prefix = config.prefix();
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            return request.path().startsWith(this.prefix);
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.prefix;
        }
    }
}