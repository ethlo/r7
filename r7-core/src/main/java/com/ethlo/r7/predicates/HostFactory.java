package com.ethlo.r7.predicates;

import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.ExactMatch;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayPredicateFactory.class)
@Description("Matches if the request 'Host' header matches one of the provided hostnames.")
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

    public record Config(
            @Description("List of hostnames to match against.")
            @ExactMatch(alternative = "To match a pattern, use MatchRequestHeader on the Host header with a regexp.", wildcards = true)
            List<String> hosts) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            new ValidatorUtils(result)
                    .notEmpty("hosts", hosts);
            if (hosts != null && !hosts.isEmpty() && names().isEmpty())
            {
                result.addError("hosts", "Holds no host name. An entry may hold several, separated by commas.");
            }
        }

        /**
         * Every host name, with comma-separated entries split. Interpolation yields one string per
         * scalar, so {@code ["${HOST_LIST}"]} is how a comma-separated variable becomes several hosts.
         * Only this predicate splits: a host name never holds a comma.
         */
        List<String> names()
        {
            if (hosts == null)
            {
                return List.of();
            }
            final List<String> names = new ArrayList<>();
            for (final String entry : hosts)
            {
                if (entry == null)
                {
                    continue;
                }
                for (final String part : entry.split(",", -1))
                {
                    final String name = part.strip();
                    if (!name.isEmpty())
                    {
                        names.add(name);
                    }
                }
            }
            return names;
        }
    }

    private static class GP implements GatewayPredicate, ShortInfo
    {
        private final String[] hosts;

        public GP(Config config)
        {
            this.hosts = config.names().toArray(new String[0]);
        }

        @Override
        public boolean test(GatewayRequest request)
        {
            final String requestHost = request.headers().getFirst("host");
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

            for (final String host : this.hosts)
            {
                if (host.length() == hostLength && requestHost.regionMatches(true, 0, host, 0, hostLength))
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