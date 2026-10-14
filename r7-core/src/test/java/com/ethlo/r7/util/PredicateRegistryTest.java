package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayResponsePredicate;
import com.ethlo.r7.condition.Condition;
import com.ethlo.r7.condition.ConditionScope;
import com.ethlo.r7.condition.Leg;
import com.ethlo.r7.config.YamlConfigSupport;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.spi.GatewayResponsePredicateFactory;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import tools.jackson.databind.ObjectMapper;

class PredicateRegistryTest
{
    private static final ObjectMapper MAPPER = YamlConfigSupport.baseMapperBuilder().build();

    public record NoParameters() implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
        }
    }

    public record WithLeg(String leg) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
        }
    }

    private static <C extends ValidatableConfig> GatewayPredicateFactory<C> request(final String name, final Class<C> configClass)
    {
        return new GatewayPredicateFactory<>()
        {
            @Override
            public String name()
            {
                return name;
            }

            @Override
            public Class<C> configClass()
            {
                return configClass;
            }

            @Override
            public GatewayPredicate create(final C config)
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static <C extends ValidatableConfig> GatewayResponsePredicateFactory<C> response(final String name, final Class<C> configClass)
    {
        return new GatewayResponsePredicateFactory<>()
        {
            @Override
            public String name()
            {
                return name;
            }

            @Override
            public Class<C> configClass()
            {
                return configClass;
            }

            @Override
            public GatewayResponsePredicate create(final C config)
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void aPredicateMayNotTakeANameTheConditionTreeUses()
    {
        for (final String reserved : List.of("and", "or", "not", "Sample", "UnroutedReason"))
        {
            assertThatThrownBy(() -> new PredicateRegistry(MAPPER, List.of(request(reserved, NoParameters.class)), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Predicate name '" + reserved + "' is reserved");
        }
    }

    @Test
    void aPredicateMustTestTheClientLeg()
    {
        final GatewayPredicateFactory<NoParameters> base = request("UpstreamOnly", NoParameters.class);
        final GatewayPredicateFactory<NoParameters> upstreamOnly = new GatewayPredicateFactory<>()
        {
            @Override
            public String name()
            {
                return base.name();
            }

            @Override
            public Class<NoParameters> configClass()
            {
                return base.configClass();
            }

            @Override
            public GatewayPredicate create(final NoParameters config)
            {
                return base.create(config);
            }

            @Override
            public Set<Leg> legs()
            {
                return Set.of(Leg.UPSTREAM);
            }
        };
        assertThatThrownBy(() -> new PredicateRegistry(MAPPER, List.of(upstreamOnly), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Predicate 'UpstreamOnly' must test the client leg: legs() has to include CLIENT");
    }

    @Test
    void aPredicateMayNotHaveAParameterNamedLeg()
    {
        assertThatThrownBy(() -> new PredicateRegistry(MAPPER, List.of(), List.of(response("Legged", WithLeg.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Predicate 'Legged' has a parameter named 'leg', which is reserved: every predicate takes it");
    }

    @Test
    void aNameIsEitherARequestOrAResponsePredicate()
    {
        assertThatThrownBy(() -> new PredicateRegistry(MAPPER, List.of(request("Both", NoParameters.class)), List.of(response("Both", NoParameters.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Predicate name 'Both' is registered both as a request and as a response predicate");
    }

    @Test
    void aResponsePredicateIsRefusedWhenARouteIsChosen()
    {
        final PredicateRegistry registry = new PredicateRegistry(MAPPER, List.of(), List.of(response("Answered", NoParameters.class)));
        final ValidationResult result = new ValidationResult();

        assertThat(registry.exists("Answered")).isTrue();
        assertThat(registry.condition("Answered", Map.of(), ConditionScope.ROUTE_MATCH, result)).isSameAs(Condition.FALSE);
        assertThat(result.getErrors()).containsExactly("[Answered] Answered tests a response, which does not exist yet when a route is chosen; use it in on_response.");
    }
}
