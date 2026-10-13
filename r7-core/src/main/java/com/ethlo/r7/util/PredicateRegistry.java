package com.ethlo.r7.util;

import static com.ethlo.r7.util.Levenshtein.findClosestMatch;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.ethlo.r7.condition.Condition;
import com.ethlo.r7.condition.ConditionScope;
import com.ethlo.r7.condition.Leg;
import com.ethlo.r7.condition.RequestCondition;
import com.ethlo.r7.condition.ResponseCondition;
import com.ethlo.r7.condition.SampleCondition;
import com.ethlo.r7.condition.SampleConfig;
import com.ethlo.r7.config.ConfigurationException;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.spi.GatewayResponsePredicateFactory;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Every predicate the configuration can name, request and response predicates in one namespace,
 * and {@code Sample}. Turns one named entry of a condition into a leaf of the condition tree.
 */
public class PredicateRegistry
{
    /**
     * The parameter every predicate takes, taken out before the predicate's own parameters bind.
     */
    public static final String LEG = "leg";

    /**
     * Not a predicate but a node of the condition tree, evaluated over the exchange.
     */
    public static final String SAMPLE = "Sample";

    private static final Set<String> RESERVED_NAMES = Set.of("and", "or", "not", SAMPLE);

    private final Map<String, GatewayPredicateFactory<?>> requestFactories;
    private final Map<String, GatewayResponsePredicateFactory<?>> responseFactories;
    private final ObjectMapper mapper;

    public PredicateRegistry(final ObjectMapper mapper)
    {
        this(mapper,
                ServiceLoader.load(GatewayPredicateFactory.class).stream().<GatewayPredicateFactory<?>>map(ServiceLoader.Provider::get).toList(),
                ServiceLoader.load(GatewayResponsePredicateFactory.class).stream().<GatewayResponsePredicateFactory<?>>map(ServiceLoader.Provider::get).toList());
    }

    PredicateRegistry(final ObjectMapper mapper, final List<GatewayPredicateFactory<?>> requestFactories, final List<GatewayResponsePredicateFactory<?>> responseFactories)
    {
        this.mapper = mapper;
        this.requestFactories = requestFactories.stream()
                .collect(Collectors.toUnmodifiableMap(GatewayPredicateFactory::name, factory -> factory));
        this.responseFactories = responseFactories.stream()
                .collect(Collectors.toUnmodifiableMap(GatewayResponsePredicateFactory::name, factory -> factory));

        this.requestFactories.forEach((name, factory) -> checkRegistration(name, factory.configClass()));
        this.responseFactories.forEach((name, factory) ->
        {
            checkRegistration(name, factory.configClass());
            if (this.requestFactories.containsKey(name))
            {
                throw new IllegalStateException("Predicate name '" + name + "' is registered both as a request and as a response predicate");
            }
        });
    }

    // A plugin that takes a reserved name or parameter would be silently shadowed by the
    // condition builder: refuse it when the gateway starts instead.
    private static void checkRegistration(final String name, final Class<?> configClass)
    {
        if (RESERVED_NAMES.contains(name))
        {
            throw new IllegalStateException("Predicate name '" + name + "' is reserved");
        }
        if (configClass != null && configClass.isRecord())
        {
            for (final RecordComponent component : configClass.getRecordComponents())
            {
                if (component.getName().equals(LEG))
                {
                    throw new IllegalStateException("Predicate '" + name + "' has a parameter named '" + LEG + "', which is reserved: every predicate takes it");
                }
            }
        }
    }

    public boolean exists(final String name)
    {
        return SAMPLE.equals(name) || this.requestFactories.containsKey(name) || this.responseFactories.containsKey(name);
    }

    /**
     * @return the config record class of the named predicate, or empty for an unknown name
     */
    public Optional<Class<?>> configClass(final String name)
    {
        if (SAMPLE.equals(name))
        {
            return Optional.of(SampleConfig.class);
        }
        final GatewayPredicateFactory<?> request = this.requestFactories.get(name);
        if (request != null)
        {
            return Optional.of(request.configClass());
        }
        return Optional.ofNullable(this.responseFactories.get(name)).map(GatewayResponsePredicateFactory::configClass);
    }

    /**
     * Builds the leaf of a condition the entry {@code name: yamlValue} names. Errors are reported
     * on {@code result} under the entry's name, and the leaf is then {@link Condition#FALSE},
     * so a refused configuration never builds to a condition that holds.
     *
     * @param result the result of the condition node holding the entry
     */
    public Condition condition(final String name, final Object yamlValue, final ConditionScope scope, final ValidationResult result)
    {
        final Object leg = yamlValue instanceof Map<?, ?> map ? map.get(LEG) : null;
        if (SAMPLE.equals(name))
        {
            if (leg != null)
            {
                result.addError(name + "." + LEG, "Sample draws once per exchange and has no leg.");
                return Condition.FALSE;
            }
            final SampleConfig config = this.bind(name, yamlValue, SampleConfig.class, result);
            return config != null ? new SampleCondition(config.rate(), scope.draw()) : Condition.FALSE;
        }

        final GatewayPredicateFactory<?> requestFactory = this.requestFactories.get(name);
        final GatewayResponsePredicateFactory<?> responseFactory = this.responseFactories.get(name);
        if (requestFactory == null && responseFactory == null)
        {
            result.addError(name, this.unknown(name));
            return Condition.FALSE;
        }
        if (responseFactory != null && !scope.allowsResponses())
        {
            result.addError(name, name + " tests a response, which does not exist yet " + scope.when() + "; use it in on_response.");
            return Condition.FALSE;
        }

        final Leg resolvedLeg = this.leg(name, leg, scope, result);
        if (resolvedLeg == null)
        {
            return Condition.FALSE;
        }
        final Object parameters = withoutLeg(yamlValue);
        if (requestFactory != null)
        {
            @SuppressWarnings("unchecked") final GatewayPredicateFactory<ValidatableConfig> factory = (GatewayPredicateFactory<ValidatableConfig>) requestFactory;
            final ValidatableConfig config = this.bind(name, parameters, factory.configClass(), result);
            return config != null ? created(name, () -> new RequestCondition(factory.create(config), resolvedLeg), result) : Condition.FALSE;
        }
        @SuppressWarnings("unchecked") final GatewayResponsePredicateFactory<ValidatableConfig> factory = (GatewayResponsePredicateFactory<ValidatableConfig>) responseFactory;
        final ValidatableConfig config = this.bind(name, parameters, factory.configClass(), result);
        return config != null ? created(name, () -> new ResponseCondition(factory.create(config), resolvedLeg), result) : Condition.FALSE;
    }

    // A factory refuses what validation could not see, a pattern that does not compile, by
    // throwing: reported under the entry like any other refusal.
    private static Condition created(final String name, final Supplier<Condition> create, final ValidationResult result)
    {
        try
        {
            return create.get();
        }
        catch (final ConfigurationException | IllegalArgumentException e)
        {
            result.addError(name, e.getMessage());
            return Condition.FALSE;
        }
    }

    private Leg leg(final String name, final Object value, final ConditionScope scope, final ValidationResult result)
    {
        if (value == null)
        {
            return Leg.CLIENT;
        }
        final Leg leg = value instanceof String s ? Leg.fromConfig(s) : null;
        if (leg == null)
        {
            result.addError(name + "." + LEG, "Must be client or upstream, received '" + value + "'");
            return null;
        }
        if (leg == Leg.UPSTREAM && !scope.allowsUpstreamLeg())
        {
            result.addError(name + "." + LEG, "The upstream leg does not exist yet " + scope.when() + "; leg: upstream is allowed only in on_response.");
            return null;
        }
        return leg;
    }

    private static Object withoutLeg(final Object yamlValue)
    {
        if (yamlValue instanceof Map<?, ?> map && map.containsKey(LEG))
        {
            final Map<Object, Object> copy = new LinkedHashMap<>(map);
            copy.remove(LEG);
            return copy;
        }
        return yamlValue;
    }

    private <C extends ValidatableConfig> C bind(final String name, final Object yamlValue, final Class<C> configClass, final ValidationResult result)
    {
        final C config;
        try
        {
            config = this.mapper.convertValue(yamlValue, configClass);
        }
        catch (final UnrecognizedPropertyException e)
        {
            final String knownProps = e.getKnownPropertyIds() != null ? e.getKnownPropertyIds().toString() : "none";
            result.addError(name, String.format("Unrecognized property '%s'. Known properties are: %s", e.getPropertyName(), knownProps));
            return null;
        }
        catch (final ConfigurationException | IllegalArgumentException e)
        {
            result.addError(name, e.getMessage());
            return null;
        }

        if (config == null)
        {
            result.addError(name, "Missing parameters");
            return null;
        }
        final int before = result.getErrors().size();
        config.validate(result.nested(name));
        return result.getErrors().size() == before ? config : null;
    }

    private String unknown(final String name)
    {
        final Map<String, Object> all = new TreeMap<>();
        this.requestFactories.keySet().forEach(n -> all.put(n, n));
        this.responseFactories.keySet().forEach(n -> all.put(n, n));
        all.put(SAMPLE, SAMPLE);
        final List<String> available = List.copyOf(all.keySet());
        final String closestMatch = findClosestMatch(name, available);
        final String suggestion = closestMatch != null ? " Did you mean '" + closestMatch + "'?" : "";
        return "Unknown predicate: '" + name + "'." + suggestion + " Available predicates are: " + String.join(", ", available);
    }
}
