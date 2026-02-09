package com.ethlo.venturi.undertow;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import com.ethlo.venturi.core.DataBufferRepository;
import com.ethlo.venturi.core.ServerDirection;

public final class RepositoryOutputStream extends OutputStream
{
    private final DataBufferRepository repository;
    private final String requestId;
    private final ServerDirection direction;

    public RepositoryOutputStream(DataBufferRepository repository, CharSequence requestId, ServerDirection direction)
    {
        this.repository = repository;
        this.requestId = requestId.toString();
        this.direction = direction;
    }

    @Override
    public void write(int b)
    {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len)
    {
        repository.writeBody(direction, requestId, ByteBuffer.wrap(b, off, len));
    }

    @Override
    public void close() throws IOException
    {
        repository.closePayloadChannels(requestId);
    }
}