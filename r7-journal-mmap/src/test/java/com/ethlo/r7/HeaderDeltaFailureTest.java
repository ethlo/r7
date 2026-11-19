package com.ethlo.r7;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.journal.api.ReassemblyOptions;
import com.ethlo.r7.r7f.ExchangeReassembler;
import com.ethlo.r7.r7f.fbs.DeltaBase;
import com.ethlo.r7.r7f.fbs.DeltaOp;
import com.ethlo.r7.r7f.fbs.DeltaOpKind;
import com.ethlo.r7.r7f.fbs.HeaderDelta;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.google.flatbuffers.FlatBufferBuilder;

/**
 * What happens when a delta cannot be rebuilt.
 * <p>
 * The headers are then <em>unknown</em>, which is a different thing from absent, and the whole
 * value of recording a difference rests on that distinction being kept: a consumer that wrote
 * "no headers" into an audit trail because the base entry was lost would state something the
 * journal never said.
 * <p>
 * So this asserts the two halves that make the distinction real — the exchange does not come
 * back carrying an empty header set, and the failure reaches the integrity listener rather than
 * passing silently. The second half matters as much as the first: a counter that is never
 * incremented and an oracle that never asks about it are indistinguishable from no problem.
 */
class HeaderDeltaFailureTest
{
    @Test
    void aDeltaWithNoBaseIsReportedAndLeavesTheHeadersUnknown()
    {
        final CollectingSink sink = new CollectingSink();
        final ExchangeReassembler reassembler = new ExchangeReassembler(sink, ReassemblyOptions.DEFAULTS, sink);

        // No client request first, so there is nothing for the delta to be expressed against.
        reassembler.onUpstreamRequest("no-base", JournalLevel.HEADERS, "GET /x HTTP/1.1", null, copyOfFirstBaseEntry());

        assertThat(sink.unreconstructableDeltas)
                .as("the failure has to reach the integrity listener")
                .hasSize(1);
        assertThat(sink.unreconstructableDeltas.getFirst()).contains("no-base").contains("upstream request");
        assertThat(sink.isClean())
                .as("an exchange whose headers cannot be described is not a clean read")
                .isFalse();
    }

    /**
     * A delta whose base exists but is shorter than the op claims. Reconstructing part of it
     * and stopping would produce a header set that looks complete and is not.
     */
    @Test
    void aDeltaReachingPastItsBaseIsReportedRatherThanPartiallyApplied()
    {
        final CollectingSink sink = new CollectingSink();
        final ExchangeReassembler reassembler = new ExchangeReassembler(sink, ReassemblyOptions.DEFAULTS, sink);

        final MutableFastGatewayHeaders base = new MutableFastGatewayHeaders();
        base.add("only", "one");
        reassembler.onClientRequest("short-base", JournalLevel.HEADERS, "GET /x HTTP/1.1", base,
                InetAddress.getLoopbackAddress(), IpSource.SOCKET);

        // The base holds one entry; this asks for five.
        reassembler.onUpstreamRequest("short-base", JournalLevel.HEADERS, "GET /x HTTP/1.1", null, copyOf(0, 5));

        assertThat(sink.unreconstructableDeltas).hasSize(1);
        assertThat(sink.unreconstructableDeltas.getFirst()).contains("but the base holds 1");
        assertThat(sink.isClean()).isFalse();
    }

    private static HeaderDelta copyOfFirstBaseEntry()
    {
        return copyOf(0, 1);
    }

    /**
     * A delta consisting of a single COPY run, built by hand because the writer only produces
     * one when it has a base to diff against, and the point here is what happens when it does
     * not.
     */
    private static HeaderDelta copyOf(final int baseIndex, final int count)
    {
        final FlatBufferBuilder fbb = new FlatBufferBuilder(256);
        DeltaOp.startDeltaOp(fbb);
        DeltaOp.addKind(fbb, DeltaOpKind.COPY);
        DeltaOp.addBaseIndex(fbb, baseIndex);
        DeltaOp.addCount(fbb, count);
        final int op = DeltaOp.endDeltaOp(fbb);

        final int ops = HeaderDelta.createOpsVector(fbb, new int[]{op});
        HeaderDelta.startHeaderDelta(fbb);
        HeaderDelta.addBase(fbb, DeltaBase.CLIENT_REQUEST);
        HeaderDelta.addOps(fbb, ops);
        fbb.finish(HeaderDelta.endHeaderDelta(fbb));

        return HeaderDelta.getRootAsHeaderDelta(fbb.dataBuffer());
    }
}
