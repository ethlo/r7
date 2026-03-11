package com.ethlo.r7.predicates;

import java.util.List;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.CharSequenceUtil;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

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

            for (String host : hosts)
            {
                if (CharSequenceUtil.regionEquals(requestHost, 0, hostLength, host, 0, hostLength, true))
                {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + String.join(", ", hosts);
        }
    }
}