package com.ethlo.venturi.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.core.storage.StorageLayoutStrategy;

public class DefaultDataBufferRepository implements GatewayExchangeDataWriter
{
    private static final Logger logger = LoggerFactory.getLogger(DefaultDataBufferRepository.class);
    private static final byte[] CRLF = {13, 10};
    private static final byte COLON_SPACE = 58;
    private static final byte SPACE = 32;

    private static final ThreadLocal<ByteBuffer> HEADER_WORK_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(2048));

    // Reduced set of options to speed up kernel parsing of the open request
    private static final StandardOpenOption[] CREATE_OPTIONS = {StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
    private static final StandardOpenOption[] FINAL_OPTIONS = {StandardOpenOption.CREATE, StandardOpenOption.WRITE};

    private final StorageLayoutStrategy layoutStrategy;
    private final Path tempDir;
    private final Path archiveDir;
    private final int thresholdBytes;
    private final Map<CharSequence, RequestState> activeRequests = new ConcurrentHashMap<>(16384);

    public DefaultDataBufferRepository(Path baseDir, int thresholdBytes, StorageLayoutStrategy strategy) throws IOException
    {
        this.tempDir = baseDir.resolve("processing");
        this.archiveDir = baseDir.resolve("completed");
        this.thresholdBytes = thresholdBytes;
        this.layoutStrategy = strategy;
        Files.createDirectories(tempDir);
        Files.createDirectories(archiveDir);
    }

    @Override
    public void begin(ServerDirection direction, CharSequence requestId, ByteBuffer startLine, GatewayHeaders headers)
    {
        final RequestState state = activeRequests.computeIfAbsent(requestId, k -> new RequestState(thresholdBytes));
        final PayloadState payload = state.getPayload(direction);

        synchronized (payload)
        {
            internalWrite(payload, requestId, direction, startLine);
            internalWrite(payload, requestId, direction, ByteBuffer.wrap(CRLF));

            final ByteBuffer workBuf = HEADER_WORK_BUFFER.get();
            headers.forEach((name, value) -> {
                workBuf.clear();
                putAscii(workBuf, name);
                workBuf.put(COLON_SPACE).put(SPACE);
                putAscii(workBuf, value);
                workBuf.put(CRLF);
                workBuf.flip();
                internalWrite(payload, requestId, direction, workBuf);
            });

            internalWrite(payload, requestId, direction, ByteBuffer.wrap(CRLF));
        }
    }

    @Override
    public void writeBody(ServerDirection direction, CharSequence requestId, ByteBuffer data)
    {
        final RequestState state = activeRequests.get(requestId);
        if (state != null)
        {
            final PayloadState payload = state.getPayload(direction);
            synchronized (payload)
            {
                internalWrite(payload, requestId, direction, data);
            }
        }
    }

    private void internalWrite(PayloadState payload, CharSequence id, ServerDirection dir, ByteBuffer data)
    {
        if (payload.fileChannel != null)
        {
            safeWrite(payload.fileChannel, data, id);
        }
        else if (payload.memoryBuffer.remaining() < data.remaining())
        {
            spillToDisk(payload, id, dir, data);
        }
        else
        {
            payload.memoryBuffer.put(data);
        }
    }

    private void safeWrite(FileChannel fc, ByteBuffer data, CharSequence id)
    {
        try
        {
            while (data.hasRemaining())
            {
                if (fc.write(data) == 0) Thread.onSpinWait();
            }
        }
        catch (IOException e)
        {
            logger.error("I/O Error {}: {}", id, e.getMessage());
        }
    }

    private void spillToDisk(PayloadState payload, CharSequence id, ServerDirection dir, ByteBuffer incoming)
    {
        final Path tempFile = tempDir.resolve(id + "_" + dir.name().toLowerCase() + ".raw");
        try
        {
            // Hot Path: Open is the bottleneck
            payload.fileChannel = FileChannel.open(tempFile, CREATE_OPTIONS);

            payload.memoryBuffer.flip();
            safeWrite(payload.fileChannel, payload.memoryBuffer, id);
            safeWrite(payload.fileChannel, incoming, id);
            payload.memoryBuffer = null;
        }
        catch (IOException e)
        {
            logger.error("Spill failed {}: {}", id, e.getMessage());
        }
    }

    @Override
    public void complete(CharSequence id)
    {
        final RequestState state = activeRequests.remove(id);
        if (state != null)
        {
            finalizePayload(id, ServerDirection.REQUEST, state.request);
            finalizePayload(id, ServerDirection.RESPONSE, state.response);
        }
    }

    private void finalizePayload(CharSequence id, ServerDirection dir, PayloadState payload)
    {
        synchronized (payload)
        {
            final String requestId = id.toString();
            final Path shardDir = layoutStrategy.resolveAndPrepare(this.archiveDir, requestId);
            final Path finalPath = shardDir.resolve(requestId + "_" + dir.name().toLowerCase() + ".raw");

            try
            {
                if (payload.fileChannel != null)
                {
                    payload.fileChannel.close();
                    Files.move(tempDir.resolve(id + "_" + dir.name().toLowerCase() + ".raw"), finalPath, StandardCopyOption.ATOMIC_MOVE);
                }
                else if (payload.memoryBuffer != null)
                {
                    payload.memoryBuffer.flip();
                    if (payload.memoryBuffer.hasRemaining())
                    {
                        // This 'open' is also part of that 61.5%!
                        try (FileChannel out = FileChannel.open(finalPath, FINAL_OPTIONS))
                        {
                            safeWrite(out, payload.memoryBuffer, id);
                        }
                    }
                }
            }
            catch (IOException e)
            {
                logger.error("Finalize failed {}: {}", requestId, e.getMessage());
            }
        }
    }

    private void putAscii(ByteBuffer buffer, CharSequence s)
    {
        for (int i = 0, len = s.length(); i < len; i++) buffer.put((byte) s.charAt(i));
    }

    private static class RequestState
    {
        final PayloadState request;
        final PayloadState response;

        RequestState(int bufSize)
        {
            this.request = new PayloadState(bufSize);
            this.response = new PayloadState(bufSize);
        }

        PayloadState getPayload(ServerDirection dir)
        {
            return dir == ServerDirection.REQUEST ? request : response;
        }
    }

    private static class PayloadState
    {
        ByteBuffer memoryBuffer;
        FileChannel fileChannel;

        PayloadState(int bufSize)
        {
            this.memoryBuffer = ByteBuffer.allocateDirect(bufSize);
        }
    }
}