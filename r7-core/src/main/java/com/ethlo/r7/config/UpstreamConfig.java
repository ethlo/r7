package com.ethlo.r7.config;

import java.util.List;
import java.util.Optional;

import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.doc.Nullable;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

@Description("Configuration for routing traffic to backend services.")
public record UpstreamConfig(
        @DefaultValue("ROUND_ROBIN")
        @Description("How requests are spread across the healthy targets.") Strategy strategy,
        @DefaultValue("{}") HealthCheckConfig healthCheck,
        @DefaultValue("{}") TimeoutConfig timeouts,
        @Nullable
        @Description("List of backend servers to forward traffic to. Required after inheritance: a route takes it from its group or the root when it names none.")
        List<TargetConfig> targets,
        @DefaultValue("{}")
        @Description("How to handle routing if upstream servers becomes unavailable") FallbackConfig fallback,
        @DefaultValue("false")
        @Description("Send the client's Host header to the target instead of the target's own. TLS server name indication and certificate checks still use the target's host.") Boolean preserveHost
) implements ValidatableConfig
{
    @Override
    public Strategy strategy()
    {
        return Optional.ofNullable(this.strategy).orElse(Strategy.ROUND_ROBIN);
    }

    /**
     * This upstream's keys, each taken from here when named here and from {@code outer}
     * otherwise. A key is replaced whole: naming {@code timeouts} gives both of its values, and
     * nothing an outer level named can be removed (an explicit {@code null} is refused before
     * binding, by {@link RoutesTreeCheck}).
     *
     * @param outer the enclosing level's effective upstream, or {@code null}
     */
    public UpstreamConfig inherit(final UpstreamConfig outer)
    {
        if (outer == null)
        {
            return this;
        }
        return new UpstreamConfig(
                this.strategy != null ? this.strategy : outer.strategy,
                this.healthCheck != null ? this.healthCheck : outer.healthCheck,
                this.timeouts != null ? this.timeouts : outer.timeouts,
                this.targets != null ? this.targets : outer.targets,
                this.fallback != null ? this.fallback : outer.fallback
        );
    }

    /**
     * Validates the keys this level names. Whether there are {@code targets} at all is a property
     * of the effective upstream, checked per route once inheritance is resolved: a group may name
     * only the {@code timeouts} its routes share.
     */
    @Override
    public Boolean preserveHost()
    {
        return Optional.ofNullable(this.preserveHost).orElse(false);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils validator = new ValidatorUtils(result);

        if (this.targets != null && this.targets.isEmpty())
        {
            validator.invalid("targets", null, "Upstream configuration must contain at least one target configuration");
        }
        else if (this.targets != null)
        {
            for (int i = 0; i < this.targets.size(); i++)
            {
                final TargetConfig target = this.targets.get(i);
                target.validate(result.nested("targets[" + i + "]"));
            }
        }

        if (this.healthCheck != null)
        {
            this.healthCheck.validate(result.nested("health_check"));
        }

        if (this.timeouts != null)
        {
            this.timeouts.validate(result.nested("timeouts"));
        }

        if (this.fallback != null)
        {
            this.fallback.validate(result.nested("fallback"));
        }
    }
}