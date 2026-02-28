package com.ethlo.venturi.vlf;

import static com.ethlo.venturi.vlf.JournalDecoder.asAscii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;

import com.ethlo.venturi.api.EntryConsumer;
import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.api.StatefulEntryConsumer;
import com.ethlo.venturi.util.CharSequenceUtil;
import com.ethlo.venturi.vlf.fbs.Header;

public abstract class AbstractFbsGatewayHeaders implements GatewayHeaders
{
    private final int count;
    private final Header reusableHeader = new Header();

    protected AbstractFbsGatewayHeaders(int count)
    {
        this.count = count;
    }

    /**
     * Implementation-specific way to fetch a header at a given index into the reusable object.
     */
    protected abstract void getHeader(Header target, int index);

    @Override
    public CharSequence getFirst(CharSequence name)
    {
        for (int i = 0; i < count; i++)
        {
            getHeader(reusableHeader, i);
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
                if (nextVal != null) return true;

                while (idx < count)
                {
                    getHeader(reusableHeader, idx++);
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
            getHeader(reusableHeader, i);
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
            getHeader(reusableHeader, i);
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
        if (buf == null) return null;
        return StandardCharsets.UTF_8.decode(buf.duplicate()).toString();
    }
}