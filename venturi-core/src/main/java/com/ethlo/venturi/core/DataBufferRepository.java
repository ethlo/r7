package com.ethlo.venturi.core;

import com.ethlo.venturi.api.GatewayHeaders;
import com.ethlo.venturi.core.model.BodyProvider;
import com.ethlo.venturi.core.model.WebExchangeDataProvider;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;

public interface DataBufferRepository {
    void putHeaders(ServerDirection direction, String requestId, GatewayHeaders headers);

    Optional<GatewayHeaders> getHeaders(final ServerDirection direction, final String requestId);

    void writeBody(ServerDirection direction, String requestId, ByteBuffer data);

    void persistForError(String requestId);

    void cleanup(String requestId);

    Optional<BodyProvider> getBody(ServerDirection serverDirection, String requestId);

    void archive(WebExchangeDataProvider data, Path archiveDir);
}
