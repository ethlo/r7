package com.ethlo.venturi.core.predicates;

import java.util.List;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.util.ValidatorUtils;
import com.ethlo.venturi.spi.GatewayPredicateFactory;
import com.ethlo.venturi.util.CharSequenceUtil;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class HostFactory implements GatewayPredicateFactory<HostFactory.Config>
{
    private static final String PREDICATE_NAME = "Host";

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

    public record Config(List<String> hosts) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            new ValidatorUtils(result).required(PREDICATE_NAME, "hosts", hosts);
            if (hosts != null && hosts.isEmpty())
            {
                throw new IllegalArgumentException(PREDICATE_NAME + " requires at least one host");
            }
        }
    }

    private static class GP implements GatewayPredicate, ShortInfo
    {
        private final String[] hosts;

        public GP(Config config)
        {
            this.hosts = config.hosts().toArray(new String[0]);
        }

        @Override
        public boolean test(GatewayRequest request)
        {
            final CharSequence requestHost = request.headers().getFirst("host");
            if (requestHost == null) return false;

            int colonIndex = -1;
            for (int i = 0; i < requestHost.length(); i++)
            {
                if (requestHost.charAt(i) == ':')
                {
                    colonIndex = i;
                    break;
                }
            }

            int hostLength = (colonIndex != -1) ? colonIndex : requestHost.length();

            // Assuming CharSequenceUtil can match with a length boundary, or 
            // you might need a custom equalsIgnoreCase that takes a length!
            for (String host : hosts)
            {
                if (CharSequenceUtil.equalsIgnoreCase(requestHost, host)) return true;
            }
            return false;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + String.join(", ", hosts);
        }
    }
}