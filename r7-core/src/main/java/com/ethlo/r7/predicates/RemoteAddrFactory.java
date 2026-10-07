package com.ethlo.r7.predicates;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.spi.PredicateCreationContext;
import com.ethlo.r7.util.CidrRange;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayPredicateFactory.class)
@Description("Matches if the remote IP address falls within the specified CIDR range.")
public final class RemoteAddrFactory implements GatewayPredicateFactory<RemoteAddrFactory.Config>
{
    private static final String PREDICATE_NAME = "RemoteAddr";

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
    public GatewayPredicate create(final Config config, final PredicateCreationContext context)
    {
        return new GP(config);
    }

    public record Config(@Description("The IP or CIDR range to match (e.g., 192.168.1.0/24).") String source) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result).required("source", this.source());

            if (this.source() != null)
            {
                try
                {
                    CidrRange.parse(this.source());
                }
                catch (final IllegalArgumentException e)
                {
                    validator.invalid("source", this.source(), "Invalid IP or CIDR notation");
                }
            }
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final CidrRange range;
        private final String cidr;

        public GP(final Config config)
        {
            this.cidr = config.source();
            this.range = CidrRange.parse(this.cidr);
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            return this.range.contains(request.remoteAddress());
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.cidr;
        }
    }
}