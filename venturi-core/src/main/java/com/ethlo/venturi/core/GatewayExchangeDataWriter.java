package com.ethlo.venturi.core;

import java.nio.ByteBuffer;

import com.ethlo.venturi.api.GatewayHeaders;

public interface GatewayExchangeDataWriter
{
    void begin(ServerDirection direction, CharSequence requestId, ByteBuffer startLine, GatewayHeaders headers);

    void writeBody(ServerDirection direction, CharSequence requestId, ByteBuffer data);

    void complete(CharSequence requestId);
}
