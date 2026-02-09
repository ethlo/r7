package com.ethlo.venturi.core;

import com.ethlo.http.model.BodyProvider;
import com.ethlo.http.model.WebExchangeDataProvider;
import com.ethlo.http.netty.ServerDirection;
import org.springframework.http.HttpHeaders;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;

public interface DataBufferRepository
{
    void putHeaders(ServerDirection direction, String requestId, HttpHeaders headers);

    Optional<HttpHeaders> getHeaders(final ServerDirection direction, final String requestId);

    void writeBody(ServerDirection direction, String requestId, ByteBuffer data);

    void persistForError(String requestId);

    void cleanup(String requestId);

    Optional<BodyProvider> getBody(ServerDirection serverDirection, String requestId);

    void archive(WebExchangeDataProvider data, Path archiveDir);
}
