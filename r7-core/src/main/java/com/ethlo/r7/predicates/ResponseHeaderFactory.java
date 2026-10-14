package com.ethlo.r7.predicates;

import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.GatewayResponsePredicate;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.ExactMatch;
import com.ethlo.r7.spi.GatewayResponsePredicateFactory;
import com.ethlo.r7.util.RepeatedValues;
import com.ethlo.r7.util.SensitiveConfig;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayResponsePredicateFactory.class)
@Description("Matches if the response contains the specified header with an exact value.")
public final class ResponseHeaderFactory implements GatewayResponsePredicateFactory<ResponseHeaderFactory.Config>
{
    private static final String PREDICATE_NAME = "ResponseHeader";

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
            @Description("The name of the response header.")
            String name,

            @Description("The exact value the header must hold.")
            @ExactMatch(alternative = "Use MatchResponseHeader with a regexp to match a pattern.")
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

    private static final class GP implements GatewayResponsePredicate
    {
        private final String headerName;
        private final String targetValue;

        GP(final Config config)
        {
            this.headerName = config.name();
            this.targetValue = config.value();
        }

        @Override
        public boolean test(final GatewayResponse response)
        {
            // Every occurrence, not the first: see RepeatedValues.
            return RepeatedValues.allEqual(response.headers(), this.headerName, this.targetValue);
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            // Masked as RequestHeader's is: the value can be a token, and the summary is shown
            // on the management page.
            return PREDICATE_NAME + ": " + this.headerName + " == " + SensitiveConfig.MASK;
        }
    }
}
