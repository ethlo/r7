package com.ethlo.r7.server;

import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;

/**
 * A request header view as a server presents one: field-lines in arrival order, names kept as
 * sent and matched ignoring case. The guards in this package are written against that contract,
 * so they are tested against it rather than against r7-utils' containers, which match names
 * exactly.
 */
final class TestHeaders implements MutableGatewayHeaders
{
    private final List<String[]> lines = new ArrayList<>();

    static TestHeaders of(final String... namesAndValues)
    {
        final TestHeaders headers = new TestHeaders();
        for (int i = 0; i < namesAndValues.length; i += 2)
        {
            headers.add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return headers;
    }

    /**
     * The field-lines left, as {@code name=value}, in order.
     */
    List<String> lines()
    {
        final List<String> out = new ArrayList<>();
        for (final String[] line : lines)
        {
            out.add(line[0] + "=" + line[1]);
        }
        return out;
    }

    List<String> names()
    {
        final List<String> out = new ArrayList<>();
        for (final String[] line : lines)
        {
            out.add(line[0]);
        }
        return out;
    }

    @Override
    public String getFirst(final String name)
    {
        for (final String[] line : lines)
        {
            if (line[0].equalsIgnoreCase(name))
            {
                return line[1];
            }
        }
        return null;
    }

    @Override
    public Iterable<String> getAll(final String name)
    {
        final List<String> out = new ArrayList<>();
        for (final String[] line : lines)
        {
            if (line[0].equalsIgnoreCase(name))
            {
                out.add(line[1]);
            }
        }
        return out;
    }

    @Override
    public void add(final String name, final String value)
    {
        lines.add(new String[]{name, value});
    }

    @Override
    public MutableGatewayHeaders set(final String name, final String value)
    {
        remove(name);
        add(name, value);
        return this;
    }

    @Override
    public void set(final String name, final Iterable<String> values)
    {
        remove(name);
        for (final String value : values)
        {
            add(name, value);
        }
    }

    @Override
    public void remove(final String name)
    {
        lines.removeIf(line -> line[0].equalsIgnoreCase(name));
    }

    @Override
    public int forEach(final EntryConsumer consumer)
    {
        for (final String[] line : List.copyOf(lines))
        {
            consumer.accept(line[0], line[1]);
        }
        return lines.size();
    }

    @Override
    public <S> int forEach(final S state, final StatefulEntryConsumer<S> consumer)
    {
        for (final String[] line : List.copyOf(lines))
        {
            consumer.accept(state, line[0], line[1]);
        }
        return lines.size();
    }
}
