package com.ethlo.r7.r7f;

import static com.ethlo.r7.r7f.JournalDecoder.asAscii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.StatefulEntryConsumer;
import com.ethlo.r7.util.CharSequenceUtil;
import com.ethlo.r7.r7f.fbs.EndExchange;
import com.ethlo.r7.r7f.fbs.Header;

/**
 * Zero-allocation projection of StartEvent headers
 */
public class FbsGatewayAttributes implements GatewayAttributes
{
    private final EndExchange event;
    private final int count;
    private final Header reusableHeader = new Header();

    FbsGatewayAttributes(EndExchange event)
    {
        this.event = event;
        this.count = event.attributesLength();
    }

    @Override
    public CharSequence getFirst(CharSequence name)
    {
        for (int i = 0; i < count; i++)
        {
            event.attributes(reusableHeader, i);
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
                    event.attributes(reusableHeader, idx++);
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
            event.attributes(reusableHeader, i);
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
            event.attributes(reusableHeader, i);
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
}