package com.ethlo.r7.predicates;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayPredicateFactory.class)
public final class QueryParameterFactory implements GatewayPredicateFactory<QueryParameterFactory.Config>
{
    private static final String PREDICATE_NAME = "QueryParameter";

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

    public record Config(String name, String value) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required("name", this.name())
                    .required("value", this.value());
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String paramName;
        private final String targetValue;

        public GP(final Config config)
        {
            this.paramName = config.name();
            this.targetValue = config.value();
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            final String paramValue = request.queryParams().getFirst(this.paramName);

            if (paramValue == null)
            {
                return false;
            }

            return this.targetValue.equals(paramValue);
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.paramName + " == " + this.targetValue;
        }
    }
}