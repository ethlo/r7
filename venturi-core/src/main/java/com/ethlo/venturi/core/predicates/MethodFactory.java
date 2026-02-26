package com.ethlo.venturi.core.predicates;

import java.util.Arrays;
import java.util.List;

import com.ethlo.venturi.api.GatewayPredicate;
import com.ethlo.venturi.api.GatewayRequest;
import com.ethlo.venturi.core.ShortInfo;
import com.ethlo.venturi.plugin.ValidatorUtils;
import com.ethlo.venturi.spi.GatewayPredicateFactory;
import com.ethlo.venturi.util.CharSequenceUtil;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public class MethodFactory implements GatewayPredicateFactory<MethodFactory.Config>
{
    private static final String PREDICATE_NAME = "Method";

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

    public record Config(List<String> include) implements ValidatableConfig
    {
        @Override
        public void validate(ValidationResult result)
        {
            new ValidatorUtils(result)
                    .required(PREDICATE_NAME, "include", include)
                    .notEmpty(PREDICATE_NAME, "include", include);
        }
    }

    private static class GP implements GatewayPredicate, ShortInfo
    {
        private final String[] include;

        public GP(Config config)
        {
            // Array iteration is mechanically faster than Collection iteration
            this.include = config.include().toArray(new String[0]);
        }

        @Override
        public boolean test(GatewayRequest request)
        {
            final CharSequence requestMethod = request.method();
            for (final String m : include)
            {
                if (CharSequenceUtil.equals(m, requestMethod)) return true;
            }
            return false;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + Arrays.toString(include);
        }
    }
}