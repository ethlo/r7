package com.ethlo.r7.config;

import java.time.Duration;
import java.util.Optional;

import com.ethlo.r7.doc.DefaultValue;
import com.ethlo.r7.doc.Description;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

/**
 * Networking limits for the upstream proxy connection
 *
 * @param read    Maximum time to wait for a response from the target
 * @param connect Maximum time to wait for a TCP connection, and the TLS handshake for https
 *                targets
 */
public record TimeoutConfig(
        @DefaultValue("30s")
        @Description("Maximum time to wait for a response after sending the request.") Duration read,
        @DefaultValue("5s")
        @Description("Maximum time to wait for a connection to a target, including the TLS handshake for https targets.") Duration connect
) implements ValidatableConfig
{
    public static final Duration DEFAULT_READ = Duration.ofSeconds(30);
    public static final Duration DEFAULT_CONNECT = Duration.ofSeconds(5);

    /**
     * Both timeouts at their defaults, for an upstream with no {@code timeouts} block.
     */
    public static TimeoutConfig defaults()
    {
        return new TimeoutConfig(null, null);
    }

    @Override
    public Duration read()
    {
        return Optional.ofNullable(this.read).orElse(DEFAULT_READ);
    }

    @Override
    public Duration connect()
    {
        return Optional.ofNullable(this.connect).orElse(DEFAULT_CONNECT);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        if (this.read != null && (this.read.isNegative() || this.read.isZero()))
        {
            result.addError("read", "Read timeout must be greater than 0");
        }
        // The socket API takes whole milliseconds and reads 0 as "wait forever", so anything
        // shorter than 1 ms would turn the bound off instead of tightening it
        if (this.connect != null && this.connect.compareTo(Duration.ofMillis(1)) < 0)
        {
            result.addError("connect", "Connect timeout must be at least 1ms, but was " + this.connect);
        }
        // The route's upstream client takes both as int-millisecond socket timeouts
        new ValidatorUtils(result)
                .fitsIntMillis("read", this.read)
                .fitsIntMillis("connect", this.connect);
    }
}
