package com.ethlo.r7.journal;

import java.util.ArrayList;
import java.util.List;

import com.ethlo.r7.api.EntryConsumer;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.StatefulEntryConsumer;
import com.ethlo.r7.util.IndexedGatewayHeaders;

/**
 * A read-only view over another header container that replaces every value whose name is not
 * in the safe set with a fingerprint of it.
 * <p>
 * A view rather than a copy. The journal encoder only ever traverses these headers, so
 * materialising a second container to hold the redacted values meant, per journaled message:
 * the container and its backing arrays, a lower-cased string per header name, and a full
 * ISO-8859-1 validation scan of every name and value on the way in. That validation exists to
 * catch a value a <em>filter</em> set — it is applied where the filter sets it, by
 * {@code MutableGatewayHeaders}, and {@code TextValues} documents keeping it off the journal
 * write path. Reaching it again here only re-checked bytes that had come off the wire
 * untouched.
 * <p>
 * Redaction is therefore applied during traversal instead, which costs one capture per
 * message in place of those per-header allocations.
 * <p>
 * The journal takes a {@link #snapshot()} of this view rather than the view itself. The encoder
 * reads the client request and upstream response sets twice - once to write them, once as the
 * base the next set is diffed against - and every traversal of the view redacts again: a
 * safe-list lookup per header, a memo probe per unsafe value. The snapshot pays that once, into
 * two arrays, with none of the costs above: no lower-casing, no validation, the same String
 * instances.
 * <p>
 * Not thread-safe in any way its delegate is not, and not intended to outlive the exchange it
 * was built for: it holds a live reference to the underlying container rather than a snapshot,
 * exactly as the code it replaced did when it read that container at flush time.
 */
public final class RedactingHeaders implements GatewayHeaders
{
    private final GatewayHeaders delegate;
    private final HeaderNameSet safeNames;
    private final FingerprintMemo memo;

    RedactingHeaders(final GatewayHeaders delegate, final HeaderNameSet safeNames, final FingerprintMemo memo)
    {
        this.delegate = delegate;
        this.safeNames = safeNames;
        this.memo = memo;
    }

    private String redact(final String name, final String value)
    {
        return safeNames.contains(name) ? value : memo.fingerprintOf(value);
    }

    /**
     * The redacted set as it is now, in order, readable by position. One traversal of the
     * delegate; later changes to the delegate are not seen.
     */
    public IndexedGatewayHeaders snapshot()
    {
        final IndexedGatewayHeaders snapshot = new IndexedGatewayHeaders(16);
        delegate.forEach(snapshot, (target, name, value) -> target.append(name, redact(name, value)));
        return snapshot;
    }

    @Override
    public int forEach(final EntryConsumer consumer)
    {
        return delegate.forEach((name, value) -> consumer.accept(name, redact(name, value)));
    }

    @Override
    public <S> int forEach(final S state, final StatefulEntryConsumer<S> consumer)
    {
        return delegate.forEach(state, (s, name, value) -> consumer.accept(s, name, redact(name, value)));
    }

    /**
     * Redacted like everything else, so that a caller cannot reach an unredacted value
     * through a lookup that the traversal would have protected. Not on the journal's path.
     */
    @Override
    public String getFirst(final String name)
    {
        final String value = delegate.getFirst(name);
        return value == null ? null : redact(name, value);
    }

    @Override
    public Iterable<String> getAll(final String name)
    {
        final List<String> redacted = new ArrayList<>();
        for (final String value : delegate.getAll(name))
        {
            redacted.add(redact(name, value));
        }
        return redacted;
    }

    @Override
    public boolean contains(final String name)
    {
        return delegate.contains(name);
    }
}
