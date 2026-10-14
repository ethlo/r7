package com.ethlo.r7.predicates;

import java.util.List;
import java.util.Locale;

import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.api.GatewayResponsePredicate;
import com.ethlo.r7.doc.AcceptsInteger;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.spi.GatewayResponsePredicateFactory;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import com.google.auto.service.AutoService;

@SuppressWarnings("rawtypes")
@AutoService(GatewayResponsePredicateFactory.class)
@Description("Matches if the response status is one of the listed codes or classes.")
public final class StatusFactory implements GatewayResponsePredicateFactory<StatusFactory.Config>
{
    private static final String PREDICATE_NAME = "Status";
    private static final int LOWEST = 100;
    private static final int HIGHEST = 599;

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
            @Description("Status codes (404) and classes (5xx, case-insensitive) that match.")
            @AcceptsInteger(minimum = LOWEST, maximum = HIGHEST)
            List<String> include) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            if (this.include == null || this.include.isEmpty())
            {
                result.addError("include", "name at least one status code (404) or class (5xx)");
                return;
            }
            for (int i = 0; i < this.include.size(); i++)
            {
                if (parse(this.include.get(i)) == null)
                {
                    result.addError("include[" + i + "]", "'" + this.include.get(i) + "' is not a status code from " + LOWEST + " to " + HIGHEST + " or a class from 1xx to 5xx");
                }
            }
        }
    }

    /**
     * @return {@code [from, to]} inclusive, or {@code null} for a value that names neither a
     * code nor a class
     */
    private static int[] parse(final String value)
    {
        if (value == null)
        {
            return null;
        }
        final String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() != 3)
        {
            return null;
        }
        final char first = trimmed.charAt(0);
        if (first < '1' || first > '5')
        {
            return null;
        }
        if (trimmed.endsWith("xx"))
        {
            final int from = (first - '0') * 100;
            return new int[]{from, from + 99};
        }
        if (!Character.isDigit(trimmed.charAt(1)) || !Character.isDigit(trimmed.charAt(2)))
        {
            return null;
        }
        final int code = Integer.parseInt(trimmed);
        return new int[]{code, code};
    }

    private static final class GP implements GatewayResponsePredicate
    {
        // Indexed by status: one array read per test, whatever the list holds.
        private final boolean[] matches = new boolean[HIGHEST + 1];
        private final List<String> include;

        GP(final Config config)
        {
            this.include = List.copyOf(config.include());
            for (final String value : this.include)
            {
                final int[] range = parse(value);
                if (range == null)
                {
                    throw new IllegalArgumentException("'" + value + "' is not a status code or class");
                }
                for (int status = range[0]; status <= range[1]; status++)
                {
                    this.matches[status] = true;
                }
            }
        }

        @Override
        public boolean test(final GatewayResponse response)
        {
            final int status = response.status();
            return status >= 0 && status < this.matches.length && this.matches[status];
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.include;
        }
    }
}
