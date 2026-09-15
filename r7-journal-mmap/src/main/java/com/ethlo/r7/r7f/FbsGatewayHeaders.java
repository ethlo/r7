package com.ethlo.r7.r7f;

import static com.ethlo.r7.r7f.JournalDecoder.asLatin1;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;
import com.ethlo.r7.r7f.fbs.ClientRequest;
import com.ethlo.r7.r7f.fbs.Header;

public class FbsGatewayHeaders implements GatewayHeaders
{
    private final ClientRequest event;
    private final int count;
    private final Header reusableHeader = new Header();

    FbsGatewayHeaders(ClientRequest event)
    {
        this.event = event;
        this.count = event.headersLength();
    }

    @Override
    public String getFirst(String name)
    {
        for (int i = 0; i < count; i++)
        {
            event.headers(reusableHeader, i);
            if (Objects.equals(name, asLatin1(reusableHeader.nameAsByteBuffer())))
            {
                return decodeValue(reusableHeader.valueAsByteBuffer());
            }
        }
        return null;
    }

    @Override
    public Iterable<String> getAll(String name)
    {
        return () -> new Iterator<>()
        {
            private int idx = 0;
            private String nextVal = null;

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
                    if (Objects.equals(name, asLatin1(reusableHeader.nameAsByteBuffer())))
                    {
                        nextVal = decodeValue(reusableHeader.valueAsByteBuffer());
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String next()
            {
                if (!hasNext()) throw new NoSuchElementException();
                String v = nextVal;
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
                    asLatin1(reusableHeader.nameAsByteBuffer()),
                    decodeValue(reusableHeader.valueAsByteBuffer())
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
                    asLatin1(reusableHeader.nameAsByteBuffer()),
                    decodeValue(reusableHeader.valueAsByteBuffer())
            );
        }
        return count;
    }

    /**
     * Header and attribute values are stored as the latin-1 bytes the writer produced, so
     * they are decoded the same way. Decoding as UTF-8 turns every byte above 127 into
     * U+FFFD, which silently rewrites the record rather than reproducing it.
     */
    private String decodeValue(ByteBuffer buf)
    {
        if (buf == null)
        {
            return null;
        }
        ByteBuffer tmp = buf.duplicate();
        return StandardCharsets.ISO_8859_1.decode(tmp).toString();
    }
}