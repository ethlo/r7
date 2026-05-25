package com.ethlo.r7.predicates;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayPredicateFactory.class)
@Description("Matches if the request contains the specified header with an exact value.")
public final class RequestHeaderFactory implements GatewayPredicateFactory<RequestHeaderFactory.Config>
{
    private static final String PREDICATE_NAME = "RequestHeader";

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
            @Description("The name of the request header.")
            String name,

            @Description("The exact value the header must hold.")
            String value) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result)
                    .notBlank("name", this.name())
                    .notBlank("value", this.value());
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final String headerName;
        private final String targetValue;

        public GP(final Config config)
        {
            this.headerName = config.name();
            this.targetValue = config.value();
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            final String headerValue = request.headers().getFirst(this.headerName);

            if (headerValue == null)
            {
                return false;
            }

            return this.targetValue.equals(headerValue);
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.headerName + " == " + this.targetValue;
        }
    }
}