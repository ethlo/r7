package com.ethlo.r7.predicates;

import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.GatewayResponsePredicate;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.GatewayResponsePredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayResponsePredicateFactory.class)
@Description("Matches if the response contains the specified header, regardless of its value.")
public final class HasResponseHeaderFactory implements GatewayResponsePredicateFactory<HasResponseHeaderFactory.Config>
{
    private static final String PREDICATE_NAME = "HasResponseHeader";

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
    public GatewayResponsePredicate create(final Config config)
    {
        return new GP(config);
    }

    public record Config(
            @Description("The name of the header.")
            String name) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            new ValidatorUtils(result).notBlank("name", this.name());
        }
    }

    private static final class GP implements GatewayResponsePredicate
    {
        private final String headerName;

        GP(final Config config)
        {
            this.headerName = config.name();
        }

        @Override
        public boolean test(final GatewayResponse response)
        {
            return response.headers().getFirst(this.headerName) != null;
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.headerName;
        }
    }
}
