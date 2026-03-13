package com.ethlo.r7.predicates;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.ethlo.r7.api.GatewayPredicate;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.ShortInfo;
import com.ethlo.r7.spi.GatewayPredicateFactory;
import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public final class RemoteAddrPredicateFactory implements GatewayPredicateFactory<RemoteAddrPredicateFactory.Config>
{
    private static final String PREDICATE_NAME = "RemoteAddr";

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
    public GatewayPredicate create(final Config config)
    {
        return new GP(config);
    }

    public record Config(String source) implements ValidatableConfig
    {
        @Override
        public void validate(final ValidationResult result)
        {
            final ValidatorUtils validator = new ValidatorUtils(result).required(PREDICATE_NAME, "source", this.source());

            if (this.source() != null)
            {
                try
                {
                    final String[] parts = this.source().split("/");

                    // Validates the IP part
                    InetAddress.getByName(parts[0]);

                    if (parts.length > 1)
                    {
                        final int mask = Integer.parseInt(parts[1]);
                        if (mask < 0 || mask > 128)
                        {
                            validator.invalid(PREDICATE_NAME, "source", this.source(), "Subnet mask must be between 0 and 128");
                        }
                    }
                }
                catch (final UnknownHostException | NumberFormatException e)
                {
                    validator.invalid(PREDICATE_NAME, "source", this.source(), "Invalid IP or CIDR notation");
                }
            }
        }
    }

    private static final class GP implements GatewayPredicate, ShortInfo
    {
        private final byte[] networkBytes;
        private final byte[] maskBytes;
        private final String cidr;

        public GP(final Config config)
        {
            this.cidr = config.source();
            final String[] parts = this.cidr.split("/");

            try
            {
                final InetAddress address = InetAddress.getByName(parts[0]);
                this.networkBytes = address.getAddress();

                // If no mask is provided, use an exact match mask (32 for IPv4, 128 for IPv6)
                final int prefixLength = parts.length > 1 ? Integer.parseInt(parts[1]) : (this.networkBytes.length * 8);

                this.maskBytes = new byte[this.networkBytes.length];

                // Generate the bitwise mask array
                for (int i = 0; i < prefixLength; i++)
                {
                    this.maskBytes[i / 8] |= (byte) (1 << (7 - (i % 8)));
                }

                // Apply the mask to the network bytes to ensure strict subnet alignment
                for (int i = 0; i < this.networkBytes.length; i++)
                {
                    this.networkBytes[i] = (byte) (this.networkBytes[i] & this.maskBytes[i]);
                }
            }
            catch (final UnknownHostException e)
            {
                throw new IllegalArgumentException("Invalid CIDR format during instantiation: " + this.cidr, e);
            }
        }

        @Override
        public boolean test(final GatewayRequest request)
        {
            final InetAddress remoteAddress = request.remoteAddress();

            if (remoteAddress == null || remoteAddress.getAddress() == null)
            {
                return false;
            }

            final byte[] clientBytes = remoteAddress.getAddress();

            // Fail fast if evaluating IPv4 against an IPv6 mask or vice versa
            if (clientBytes.length != this.networkBytes.length)
            {
                return false;
            }

            // Zero-allocation bitwise comparison
            for (int i = 0; i < this.networkBytes.length; i++)
            {
                if ((clientBytes[i] & this.maskBytes[i]) != this.networkBytes[i])
                {
                    return false;
                }
            }

            return true;
        }

        @Override
        public String name()
        {
            return PREDICATE_NAME;
        }

        @Override
        public String summary()
        {
            return PREDICATE_NAME + ": " + this.cidr;
        }
    }
}