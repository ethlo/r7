package com.ethlo.venturi.vlf;

import static com.ethlo.venturi.vlf.JournalDecoder.asAscii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

import com.ethlo.venturi.api.EntryConsumer;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.StatefulEntryConsumer;
import com.ethlo.venturi.util.CharSequenceUtil;
import com.ethlo.venturi.vlf.fbs.Header;
import com.ethlo.venturi.vlf.fbs.StartEvent;

/**
 * Zero-allocation projection of StartEvent headers
 */
public class FbsGatewayHeaders implements GatewayHeaders
{
    private final StartEvent event;
    private final int count;
    private final Header reusableHeader = new Header();

    FbsGatewayHeaders(StartEvent event)
    {
        this.event = event;
        this.count = event.headersLength();
    }

    @Override
    public CharSequence getFirst(CharSequence name)
    {
        for (int i = 0; i < count; i++)
        {
            event.headers(reusableHeader, i);
            if (CharSequenceUtil.equals(name, asAscii(reusableHeader.nameAsByteBuffer())))
            {
                return decodeUtf8(reusableHeader.valueAsByteBuffer());
            }
        }
        return null;
    }

    @Override
    public Iterable<CharSequence> getAll(CharSequence name)
    {
        return () -> new Iterator<>()
        {
            private int idx = 0;
            private CharSequence nextVal = null;

            @Override
            public boolean hasNext()
            {
                if (nextVal != null)
                {
                    return true;
                }

                while (idx < count)
                {
                    event.headers(reusableHeader, idx++);
                    if (CharSequenceUtil.equals(name, asAscii(reusableHeader.nameAsByteBuffer())))
                    {
                        nextVal = decodeUtf8(reusableHeader.valueAsByteBuffer());
                        return true;
                    }
                }
                return false;
            }

            @Override
            public CharSequence next()
            {
                if (!hasNext()) throw new NoSuchElementException();
                CharSequence v = nextVal;
                nextVal = null;
                return v;
            }
        };
    }

    @Override
    public int forEach(EntryConsumer consumer)
    {
        for (int i = 0; i < count; i++)
        {
            event.headers(reusableHeader, i);
            consumer.accept(
                    asAscii(reusableHeader.nameAsByteBuffer()),
                    decodeUtf8(reusableHeader.valueAsByteBuffer())
            );
        }
        return count;
    }

    @Override
    public <S> int forEach(S state, StatefulEntryConsumer<S> consumer)
    {
        for (int i = 0; i < count; i++)
        {
            event.headers(reusableHeader, i);
            consumer.accept(
                    state,
                    asAscii(reusableHeader.nameAsByteBuffer()),
                    decodeUtf8(reusableHeader.valueAsByteBuffer())
            );
        }
        return count;
    }

    private String decodeUtf8(ByteBuffer buf)
    {
        if (buf == null)
        {
            return null;
        }
        ByteBuffer tmp = buf.duplicate();
        return StandardCharsets.UTF_8.decode(tmp).toString();
    }

    @Override
    public void set(CharSequence name, CharSequence value)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove(CharSequence name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void set(CharSequence name, Iterable<CharSequence> values)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(CharSequence name, CharSequence value)
    {
        throw new UnsupportedOperationException();
    }
}