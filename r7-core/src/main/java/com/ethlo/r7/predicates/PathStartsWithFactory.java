package com.ethlo.r7.predicates;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.CharSequenceUtil;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public class PathStartsWithFactory implements GatewayPredicateFactory<PathStartsWithFactory.Config>
{
    private static final String PREDICATE_NAME = "PathStartsWith";

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
    public GatewayPredicate create(Config config)
    {
        return new GP(config);
    }

    public record Config(String prefix) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            new ValidatorUtils(result).required(PREDICATE_NAME, "prefix", prefix);
        }
    }

    private static class GP implements GatewayPredicate, ShortInfo
    {
        private final String prefix;

        public GP(Config config)
        {
            this.prefix = config.prefix();
        }

        @Override
        public boolean test(GatewayRequest request)
        {
            return CharSequenceUtil.startsWith(request.path(), prefix);
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + prefix;
        }
    }
}