package com.ethlo.venturi.plugin;

import com.ethlo.venturi.api.GatewayFilter;
import com.ethlo.venturi.spi.GatewayFilterFactory;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

public class RouteFactory 
{
    private final FilterRegistry registry;
    private final YAMLMapper mapper;

    public RouteFactory(FilterRegistry registry) 
    {
        this.registry = registry;

        this.mapper = YAMLMapper.builder()
                .build();
    }

    public GatewayFilter buildFilter(String filterName, JsonNode yamlConfig)
    {
        GatewayFilterFactory<?> factory = registry.get(filterName);

        // Jackson 3 maps the YAML tree directly to your config record
        ValidatableConfig config = mapper.treeToValue(yamlConfig, (Class<ValidatableConfig>) factory.configClass());

        // The record strictly polices its own state natively
        final ValidationResult result = new ValidationResult();
        config.validate(result);

        if (result.hasErrors())
        {
            // TODO: Make this cleaner!
            throw new IllegalArgumentException(result.toString());
        }

        @SuppressWarnings("unchecked")
        GatewayFilterFactory<ValidatableConfig> typedFactory = (GatewayFilterFactory<ValidatableConfig>) factory;
        
        return typedFactory.create(config);
    }
}