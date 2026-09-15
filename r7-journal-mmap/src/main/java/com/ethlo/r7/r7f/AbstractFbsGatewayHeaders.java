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
import com.ethlo.r7.r7f.fbs.Header;

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
    public String getFirst(String name)
    {
        for (int i = 0; i < count; i++)
        {
            getHeader(reusableHeader, i);
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
                if (nextVal != null) return true;

                while (idx < count)
                {
                    getHeader(reusableHeader, idx++);
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
            getHeader(reusableHeader, i);
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
            getHeader(reusableHeader, i);
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
        return StandardCharsets.ISO_8859_1.decode(buf.duplicate()).toString();
    }

    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < count; i++)
        {
            getHeader(reusableHeader, i);
            final String name = asLatin1(reusableHeader.nameAsByteBuffer());
            final String value = decodeValue(reusableHeader.valueAsByteBuffer());

            sb.append(name).append("=").append(value);

            if (i < count - 1)
            {
                sb.append(", ");
            }
        }
        return sb.append("}").toString();
    }
}