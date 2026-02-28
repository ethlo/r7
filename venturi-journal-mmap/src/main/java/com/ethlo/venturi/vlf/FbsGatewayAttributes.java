package com.ethlo.venturi.vlf;

import static com.ethlo.venturi.vlf.JournalDecoder.asAscii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

import com.ethlo.venturi.api.EntryConsumer;
import com.ethlo.venturi.api.GatewayAttributes;
import com.ethlo.venturi.api.StatefulEntryConsumer;
import com.ethlo.venturi.util.CharSequenceUtil;
import com.ethlo.venturi.vlf.fbs.EndEvent;
import com.ethlo.venturi.vlf.fbs.Header;

/**
 * Zero-allocation projection of StartEvent headers
 */
public class FbsGatewayAttributes implements GatewayAttributes
{
    private final EndEvent event;
    private final int count;
    private final Header reusableHeader = new Header();

    FbsGatewayAttributes(EndEvent event)
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