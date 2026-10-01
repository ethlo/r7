package com.ethlo.r7.r7f;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.r7f.fbs.HeaderDelta;

/**
 * Remembers where each exchange the reassembler holds began in the journal, so that a restart
 * can rebuild it instead of losing it.
 * <p>
 * A checkpoint is a read position, and reading an entry is not delivering the exchange it
 * belongs to: an exchange is assembled in memory until its end event, so every exchange open
 * when the process dies has its earlier entries checkpointed as read and its state gone. That
 * cost a hard-killed tailer every exchange that spanned a tick boundary, and turned their end
 * events into orphans on the next run.
 * <p>
 * Moving the checkpoint back to the oldest open exchange would fix the loss but re-deliver
 * everything that completed after it: at a few thousand requests a second and an exchange held
 * open for minutes, millions of duplicates. So the checkpoint stays where it is, and this
 * records beside it, per shard, the earliest start among the open exchanges and their request
 * ids. On restart the tailer replays that window up to the checkpoint for those ids only,
 * which rebuilds exactly the state that was lost and delivers nothing twice; reading then
 * carries on from the checkpoint as usual. Exchanges are sharded by request id, so all of an
 * exchange's entries are in one shard, read in order.
 * <p>
 * This sits between the decoder and the reassembler: as an {@link JournalDecoder.EntryObserver}
 * it learns where each entry starts, and as the listener it records the first entry seen for
 * each request id before passing the event on. During a replay it also filters, so events of
 * exchanges that were not open are dropped rather than delivered again or reported as orphans.
 */
final class OpenExchanges implements JournalEventListener, JournalDecoder.EntryObserver
{
    private static final String KEY_PREFIX = "open.";

    /**
     * Where an exchange's first entry is: the segment (shard and sequence), the offset to
     * resume reading at, and the entry sequence to expect there.
     */
    record Start(int shard, long segment, long offset, int sequence)
    {
        boolean isBefore(final Start other)
        {
            return segment != other.segment ? segment < other.segment : offset < other.offset;
        }

        String serialize()
        {
            return segment + ":" + offset + ":" + sequence;
        }

        static Start parse(final int shard, final String value)
        {
            final String[] parts = value.split(":");
            if (parts.length != 3)
            {
                throw new NumberFormatException(value);
            }
            return new Start(shard, Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        }
    }

    /**
     * What one shard needs replayed: from {@code from}, the events of {@code requestIds}.
     */
    record Resume(Start from, Set<String> requestIds)
    {
    }

    private final ExchangeReassembler target;
    private final Map<String, Start> starts = new HashMap<>();

    private Set<String> only;
    private int shard;
    private long segment;
    private long offset;
    private int sequence;

    OpenExchanges(final ExchangeReassembler target)
    {
        this.target = target;
    }

    /**
     * Must be called before decoding each segment, so that recorded starts name it.
     */
    void enterSegment(final int shard, final long segment)
    {
        this.shard = shard;
        this.segment = segment;
    }

    /**
     * Passes on only the events of these request ids until {@link #acceptAll()}.
     */
    void acceptOnly(final Set<String> requestIds)
    {
        this.only = requestIds;
    }

    void acceptAll()
    {
        this.only = null;
    }

    @Override
    public void onEntry(final long offset, final int sequence)
    {
        this.offset = offset;
        this.sequence = sequence;
    }

    private boolean accept(final String reqId)
    {
        if (only != null && !only.contains(reqId))
        {
            return false;
        }
        if (!starts.containsKey(reqId))
        {
            starts.put(reqId, new Start(shard, segment, offset, sequence));
        }
        return true;
    }

    /**
     * Forgets every exchange the reassembler no longer holds, and returns, per shard, where a
     * restart has to replay from and for which exchanges.
     */
    Map<Integer, Resume> resumePoints()
    {
        starts.keySet().removeIf(reqId -> !target.isOpen(reqId));

        final Map<Integer, Resume> byShard = new TreeMap<>();
        starts.forEach((reqId, start) ->
        {
            final Resume existing = byShard.get(start.shard());
            if (existing == null)
            {
                final Set<String> ids = new HashSet<>();
                ids.add(reqId);
                byShard.put(start.shard(), new Resume(start, ids));
            }
            else
            {
                existing.requestIds().add(reqId);
                if (start.isBefore(existing.from()))
                {
                    byShard.put(start.shard(), new Resume(start, existing.requestIds()));
                }
            }
        });
        return byShard;
    }

    /**
     * Writes resume points into the checkpoint properties: {@code open.<shard>} holds the
     * start, {@code open.<shard>.<n>} each request id. One property per id, because a request
     * id may come from a client header and so contain any separator a list would use;
     * {@link Properties} escapes whatever it holds.
     */
    static void write(final Map<Integer, Resume> resumePoints, final Properties props)
    {
        resumePoints.forEach((shard, resume) ->
        {
            props.setProperty(KEY_PREFIX + shard, resume.from().serialize());
            int n = 0;
            for (final String reqId : resume.requestIds())
            {
                props.setProperty(KEY_PREFIX + shard + "." + n++, reqId);
            }
        });
    }

    /**
     * Reads and removes the resume points from loaded checkpoint properties, so that what is
     * left is the per-segment checkpoints.
     *
     * @param problems receives a description of each record that could not be read
     */
    static Map<Integer, Resume> read(final Properties props, final List<String> problems)
    {
        final Map<Integer, Start> from = new TreeMap<>();
        final Map<Integer, Set<String>> ids = new TreeMap<>();
        final List<String> consumed = new ArrayList<>();

        for (final String key : props.stringPropertyNames())
        {
            if (!key.startsWith(KEY_PREFIX))
            {
                continue;
            }
            consumed.add(key);
            final String rest = key.substring(KEY_PREFIX.length());
            final int dot = rest.indexOf('.');
            try
            {
                final int shard = Integer.parseInt(dot < 0 ? rest : rest.substring(0, dot));
                if (dot < 0)
                {
                    from.put(shard, Start.parse(shard, props.getProperty(key)));
                }
                else
                {
                    ids.computeIfAbsent(shard, s -> new HashSet<>()).add(props.getProperty(key));
                }
            }
            catch (final NumberFormatException e)
            {
                problems.add(key + "=" + props.getProperty(key));
            }
        }
        consumed.forEach(props::remove);

        final Map<Integer, Resume> result = new TreeMap<>();
        from.forEach((shard, start) ->
        {
            final Set<String> shardIds = ids.remove(shard);
            if (shardIds != null)
            {
                result.put(shard, new Resume(start, shardIds));
            }
        });
        ids.keySet().forEach(shard -> problems.add(KEY_PREFIX + shard + " (request ids with no start)"));
        return result;
    }

    @Override
    public void onClientRequest(final String reqId, final JournalLevel level, final String startLine, final GatewayHeaders headers, final InetAddress remoteAddress, final IpSource ipSource)
    {
        if (accept(reqId))
        {
            target.onClientRequest(reqId, level, startLine, headers, remoteAddress, ipSource);
        }
    }

    @Override
    public void onUpstreamRequest(final String reqId, final JournalLevel level, final String startLine, final GatewayHeaders headers, final HeaderDelta delta)
    {
        if (accept(reqId))
        {
            target.onUpstreamRequest(reqId, level, startLine, headers, delta);
        }
    }

    @Override
    public void onRequestBody(final String reqId, final ByteBuffer bodyChunk)
    {
        if (accept(reqId))
        {
            target.onRequestBody(reqId, bodyChunk);
        }
    }

    @Override
    public void onResponseBody(final String reqId, final ByteBuffer bodyChunk)
    {
        if (accept(reqId))
        {
            target.onResponseBody(reqId, bodyChunk);
        }
    }

    @Override
    public void onUpstreamResponse(final String reqId, final JournalLevel level, final String startLine, final GatewayHeaders headers)
    {
        if (accept(reqId))
        {
            target.onUpstreamResponse(reqId, level, startLine, headers);
        }
    }

    @Override
    public void onClientResponse(final String reqId, final JournalLevel level, final String startLine, final GatewayHeaders headers, final HeaderDelta delta)
    {
        if (accept(reqId))
        {
            target.onClientResponse(reqId, level, startLine, headers, delta);
        }
    }

    @Override
    public void onEnd(final String reqId, final GatewayAttributes attributes,
                      final long clientStartTs, final long clientEndTs,
                      final int status,
                      final long requestHeaderBytes, final long requestBodyBytes, final long responseHeaderBytes, final long responseBodyBytes,
                      final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs,
                      final BodyChecksum requestChecksum, final BodyChecksum responseChecksum)
    {
        if (accept(reqId))
        {
            target.onEnd(reqId, attributes, clientStartTs, clientEndTs, status,
                    requestHeaderBytes, requestBodyBytes, responseHeaderBytes, responseBodyBytes,
                    proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs, requestChecksum, responseChecksum);
        }
    }
}
