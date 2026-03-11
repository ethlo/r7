package com.ethlo.r7.predicates;

import java.util.Arrays;
import java.util.List;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.core.ShortInfo;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.CharSequenceUtil;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

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
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + Arrays.toString(include);
        }
    }
}