package com.ethlo.venturi.undertow;

import org.xnio.conduits.AbstractStreamSinkConduit;
import org.xnio.conduits.StreamSinkConduit;

import java.io.OutputStream;
import java.nio.ByteBuffer;

public class TeeingStreamSinkConduit extends AbstractStreamSinkConduit<StreamSinkConduit> {
    private final OutputStream auditLog;

    public TeeingStreamSinkConduit(StreamSinkConduit next, OutputStream auditLog) {
        super(next);
        this.auditLog = auditLog;
    }

    @Override
    public int write(ByteBuffer src) throws java.io.IOException {
        int pos = src.position();
        int written = next.write(src);
        if (written > 0) {
            // Duplicate the exact bytes written to the network to the audit log
            int limit = src.limit();
            src.limit(pos + written);
            src.position(pos);
            while (src.hasRemaining()) {
                auditLog.write(src.get());
            }
            src.limit(limit);
        }
        return written;
    }
}