package com.ethlo.r7.condition;

import java.util.concurrent.ThreadLocalRandom;

import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;

/**
 * What a {@link Condition} is evaluated over: one exchange, as far as it has come.
 */
public interface ConditionInput
{
    /**
     * @return the request on that leg, or {@code null} while the leg has none: the upstream
     * request exists once r7 hands it to the upstream
     */
    GatewayRequest request(Leg leg);

    /**
     * @return the response on that leg, or {@code null} while the leg has none: the upstream
     * response exists only once an upstream sent a response head
     */
    GatewayResponse response(Leg leg);

    /**
     * @return the exchange's one uniform draw in {@code [0, 1)}, made the first time it is asked
     * for and kept, so every {@code Sample} of the exchange agrees
     */
    double sampleDraw();

    /**
     * Only a client request, as when a route is chosen. Allocates; for callers off the request
     * path.
     */
    static ConditionInput of(final GatewayRequest clientRequest)
    {
        return new ConditionInput()
        {
            private double draw = Double.NaN;

            @Override
            public GatewayRequest request(final Leg leg)
            {
                return leg == Leg.CLIENT ? clientRequest : null;
            }

            @Override
            public GatewayResponse response(final Leg leg)
            {
                return null;
            }

            @Override
            public double sampleDraw()
            {
                if (Double.isNaN(this.draw))
                {
                    this.draw = ThreadLocalRandom.current().nextDouble();
                }
                return this.draw;
            }
        };
    }
}
