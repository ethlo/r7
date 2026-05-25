package com.ethlo.r7.predicates;

import java.util.Objects;

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
@Description("Matches if the request path matches exactly the provided path.")
public final class PathFactory implements GatewayPredicateFactory<PathFactory.Config>
{
    private static final String PREDICATE_NAME = "Path";

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
            @Description("The exact path to match.")
            @FormatPattern("^/.*$")
            String path) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result).required("path", this.path());
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String path;

        public GP(final Config config)
        {
            this.path = config.path();
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            return Objects.equals(request.path(), this.path);
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.path;
        }
    }
}