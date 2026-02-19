package com.ethlo.venturi.core.predicates;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.GatewayRequest;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MethodPredicateTest
{

    @Test
    void test1()
    {
        final GatewayRequest gatewayRequest = new GatewayRequest()
        {
            @Override
            public CharSequence method()
            {
                return "POST";
            }

            @Override
            public CharSequence uri()
            {
                return null;
            }

            @Override
            public CharSequence path()
            {
                return null;
            }

            @Override
            public CharSequence queryParams()
            {
                return null;
            }

            @Override
            public GatewayHeaders headers()
            {
                return null;
            }

            @Override
            public void addBodyListener(final Consumer<ByteBuffer> listener)
            {

            }

            @Override
            public void path(final CharSequence path)
            {

            }

            @Override
            public void uri(final CharSequence uri)
            {

            }
        };

        final MethodPredicate methodPredicate = new MethodPredicate(new String[]{"GET", "POST"});

        for (long i = 0; i < 10_000_000_000L;i++)
        {
            methodPredicate.test(gatewayRequest);
        }
    }
}