package com.ethlo.venturi.api;

import java.util.List;

public interface MutableGatewayHeaders extends GatewayHeaders, MutableMultiAttributes
{
    MutableGatewayHeaders EMPTY = new MutableGatewayHeaders()
    {
        @Override
        public void set(final CharSequence name, final CharSequence value)
        {

        }

        @Override
        public void remove(final CharSequence name)
        {

        }

        @Override
        public void set(final CharSequence name, final Iterable<CharSequence> values)
        {

        }

        @Override
        public void add(final CharSequence name, final CharSequence value)
        {

        }

        @Override
        public CharSequence getFirst(final CharSequence name)
        {
            return null;
        }

        @Override
        public Iterable<CharSequence> getAll(final CharSequence name)
        {
            return List.of();
        }

        @Override
        public int forEach(final EntryConsumer consumer)
        {
            return 0;
        }

        @Override
        public <S> int forEach(final S state, final StatefulEntryConsumer<S> consumer)
        {
            return 0;
        }
    };
}