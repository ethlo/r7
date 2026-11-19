package com.ethlo.r7.journal;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

import com.ethlo.r7.api.GatewayAttributes;
import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.IpSource;
import com.ethlo.r7.api.MutableGatewayAttributes;
import com.ethlo.r7.condition.ConditionInput;
import com.ethlo.r7.journal.api.BodyChecksum;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.util.FastGatewayHeaders;
import com.ethlo.r7.util.Fingerprint;

public final class StatefulJournal implements Journal
{
    /**
     * Each rule that fired: decided at least one level, as the first matching rule naming it.
     * Its id, else its path.
     */
    public static final String RULE_KEY = "gateway.journal.rule";

    /**
     * Each rule whose condition threw, and so did not fire. Its id, else its path.
     */
    public static final String RULE_ERROR_KEY = "gateway.journal.rule_error";

    /**
     * The direction keys a rule may decide, as bits: each is resolved on its own.
     */
    private static final int REQUEST_LEVEL = 1;
    private static final int RESPONSE_LEVEL = 2;
    private static final int REQUEST_CAP = 4;
    private static final int RESPONSE_CAP = 8;
    private static final int ALL_KEYS = REQUEST_LEVEL | RESPONSE_LEVEL | REQUEST_CAP | RESPONSE_CAP;

    private final Journal delegate;
    private final JournalPolicy policy;
    private final HeaderNameSet safeRequestHeaders;
    private final HeaderNameSet safeResponseHeaders;
    private final QueryParameterNameSet safeQueryParameters;
    private final Fingerprint fingerprint;
    /**
     * Checksums of the body bytes this journal actually passed to the delegate, created on
     * the first fragment of that direction.
     * <p>
     * Lazy is the whole point. Eagerly created accumulators cannot distinguish "no body was
     * journaled" from "a body was journaled and it hashes to the CRC32C of nothing", and
     * they answer 0 for both. The reader has to know which, because verifying a checksum
     * against a body that was never stored reports a mismatch on a perfectly intact record.
     */
    private CRC32C requestChecksum;
    private CRC32C responseChecksum;

    /**
     * Shared by all four header views of this exchange, so a header that survived the filter
     * chain unchanged is fingerprinted once rather than once per journaled message.
     */
    private final FingerprintMemo fingerprints;

    /**
     * The header sets actually handed to the delegate for this exchange's client request and
     * upstream response — not the sets that arrived, the ones that were written, redaction and
     * level downgrades included.
     * <p>
     * They are the bases the forwarded request and the returned response may be recorded as
     * differences from, and a difference is only meaningful against what the journal really
     * holds. Null means nothing was written to refer to, and the full set is written instead.
     */
    private GatewayHeaders clientRequestBase;
    private GatewayHeaders upstreamResponseBase;

    /**
     * Resolved so far: the base levels, then {@code on_request}, then {@code on_response}.
     * The request level never changes to or from FULL after {@code on_request}: validation
     * refuses an {@code on_response} rule that could.
     */
    private JournalLevel requestLevel;
    private JournalLevel responseLevel;
    /**
     * The body caps resolved the same way, in bytes, or {@link JournalPolicy#NO_CAP}. Only the
     * body the journal keeps is capped: the traffic streams whole whatever these say.
     */
    private long requestTruncateAt;
    private long responseTruncateAt;
    private long requestBodyJournaled;
    private long responseBodyJournaled;
    /**
     * Set once a byte past the cap was offered, not when the cap is reached: a body exactly
     * at the cap was journaled whole.
     */
    private boolean requestBodyTruncated;
    private boolean responseBodyTruncated;
    private String requestId;
    private boolean clientReqFlushed = false;
    private boolean upstreamReqFlushed = false;
    private boolean upstreamResFlushed = false;
    private boolean clientResFlushed = false;

    private ByteBuffer clientReqLine;
    private GatewayHeaders clientReqHeaders;
    private ByteBuffer upstreamReqLine;
    private GatewayHeaders upstreamReqHeaders;
    private ByteBuffer upstreamResLine;
    private GatewayHeaders upstreamResHeaders;
    private boolean upstreamResRelayed;
    private ByteBuffer clientResLine;
    private int clientStatusCode;
    private GatewayHeaders clientResHeaders;
    private int upstreamStatusCode;

    private long bytesWritten;
    private InetAddress remoteAddress;
    private IpSource remoteAddressSource;

    /**
     * The policy of the route this exchange first matched, fixed when the journal was opened: a
     * fallback does not change it.
     */
    public JournalPolicy policy()
    {
        return this.policy;
    }

    public JournalLevel requestLevel()
    {
        return this.requestLevel;
    }

    public JournalLevel responseLevel()
    {
        return this.responseLevel;
    }

    /**
     * @return the request body cap resolved so far, in bytes, or {@link JournalPolicy#NO_CAP}
     */
    public long requestTruncateAt()
    {
        return this.requestTruncateAt;
    }

    /**
     * @return the response body cap resolved so far, in bytes, or {@link JournalPolicy#NO_CAP}
     */
    public long responseTruncateAt()
    {
        return this.responseTruncateAt;
    }

    /**
     * Applies the {@code on_request} rules over the base levels. Once the route is chosen, before
     * its filters run and before the request body streams.
     */
    public void onRequest(final ConditionInput input, final MutableGatewayAttributes attributes)
    {
        this.apply(this.policy.onRequestRules(), input, attributes);
    }

    /**
     * Applies the {@code on_response} rules over what {@code on_request} resolved. Once the
     * response head is final, before it is written.
     */
    public void onResponse(final ConditionInput input, final MutableGatewayAttributes attributes)
    {
        this.apply(this.policy.onResponseRules(), input, attributes);
    }

    /**
     * Per key, the first matching rule naming it wins. The four keys (each direction's level
     * and body cap) are resolved independently, so a rule that names only one never hides an
     * outer rule's other. A rule is only evaluated while it could still decide a key, so
     * evaluation stops once all are decided.
     */
    private void apply(final JournalRule[] rules, final ConditionInput input, final MutableGatewayAttributes attributes)
    {
        int decided = 0;
        for (final JournalRule rule : rules)
        {
            final int decides = keysOf(rule) & ~decided;
            if (decides == 0)
            {
                continue;
            }
            final int outcome = rule.evaluate(input);
            if (outcome == JournalRule.FAILED)
            {
                attributes.add(RULE_ERROR_KEY, rule.label());
            }
            if (outcome != JournalRule.MATCHED)
            {
                continue;
            }
            if ((decides & REQUEST_LEVEL) != 0)
            {
                this.requestLevel = rule.request();
            }
            if ((decides & RESPONSE_LEVEL) != 0)
            {
                this.responseLevel = rule.response();
            }
            if ((decides & REQUEST_CAP) != 0)
            {
                this.requestTruncateAt = rule.requestTruncateAt();
            }
            if ((decides & RESPONSE_CAP) != 0)
            {
                this.responseTruncateAt = rule.responseTruncateAt();
            }
            decided |= decides;
            attributes.add(RULE_KEY, rule.label());
            if (decided == ALL_KEYS)
            {
                return;
            }
        }
    }

    private static int keysOf(final JournalRule rule)
    {
        int keys = 0;
        if (rule.request() != null)
        {
            keys |= REQUEST_LEVEL;
        }
        if (rule.response() != null)
        {
            keys |= RESPONSE_LEVEL;
        }
        if (rule.requestTruncateAt() != JournalPolicy.NO_CAP)
        {
            keys |= REQUEST_CAP;
        }
        if (rule.responseTruncateAt() != JournalPolicy.NO_CAP)
        {
            keys |= RESPONSE_CAP;
        }
        return keys;
    }

    /**
     * Whether the exchange gets an end record: decided on the resolved levels, the same ones
     * the request and response records are written at, so an end record never stands alone.
     */
    public boolean isAtLeastMetadata()
    {
        return this.requestLevel != JournalLevel.NONE || this.responseLevel != JournalLevel.NONE;
    }

    /**
     * @param safeQueryParameters query parameters whose values the request lines keep; every
     *                            other value is fingerprinted like an unsafe header's
     * @param fingerprint         how values of headers and query parameters not on the safe
     *                            lists are written
     */
    public StatefulJournal(final Journal delegate, final JournalPolicy policy,
                            final HeaderNameSet safeRequestHeaders, final HeaderNameSet safeResponseHeaders,
                            final QueryParameterNameSet safeQueryParameters, final Fingerprint fingerprint)
    {
        this.fingerprint = fingerprint;
        this.safeQueryParameters = safeQueryParameters;
        this.fingerprints = new FingerprintMemo(fingerprint);
        this.delegate = delegate;
        this.policy = policy;
        this.requestLevel = policy.request();
        this.responseLevel = policy.response();
        this.requestTruncateAt = policy.requestTruncateAt();
        this.responseTruncateAt = policy.responseTruncateAt();
        this.safeRequestHeaders = safeRequestHeaders;
        this.safeResponseHeaders = safeResponseHeaders;
    }

    @Override
    public int clientRequest(final JournalLevel level, final String reqId, final ByteBuffer startLine, final GatewayHeaders headers, final InetAddress remoteAddress, IpSource ipSource)
    {
        this.requestId = reqId;
        this.clientReqLine = redactRequestLine(startLine);
        this.clientReqHeaders = headers;
        this.remoteAddress = remoteAddress;
        this.remoteAddressSource = ipSource;

        if (this.requestLevel == JournalLevel.FULL)
        {
            return checkAndFlushRequest();
        }
        return 0;
    }

    @Override
    public int upstreamRequest(final JournalLevel level, final String reqId, final ByteBuffer startLine, final GatewayHeaders headers, final GatewayHeaders ignoredBase)
    {
        this.upstreamReqLine = redactRequestLine(startLine);
        this.upstreamReqHeaders = headers;

        if (this.requestLevel == JournalLevel.FULL)
        {
            return checkAndFlushRequest();
        }
        return 0;
    }

    @Override
    public int upstreamResponse(final JournalLevel level, final String reqId, final int upstreamStatusCode, final ByteBuffer startLine, final GatewayHeaders headers, final boolean relayed)
    {
        this.upstreamResLine = cloneBuffer(startLine);
        this.upstreamResHeaders = headers;
        this.upstreamResRelayed = relayed;
        this.upstreamStatusCode = upstreamStatusCode;

        int written = checkAndFlushRequest();
        written += checkAndFlushResponse();
        return written;
    }

    @Override
    public int clientResponse(final JournalLevel level, final String reqId, final int clientStatusCode, final ByteBuffer startLine, final GatewayHeaders headers, final GatewayHeaders ignoredBase)
    {
        this.clientStatusCode = clientStatusCode;
        this.clientResLine = cloneBuffer(startLine);
        this.clientResHeaders = headers;

        return checkAndFlushResponse();
    }

    @Override
    public int requestBody(final String reqId, final ByteBuffer data)
    {
        if (this.requestLevel == JournalLevel.FULL)
        {
            // Anchor the body before writing it: a body entry the reader cannot attach to a
            // request is discarded as an orphan, and its recorded checksum is then compared
            // against a body that was thrown away — a mismatch on a record that is exactly
            // what this writer intended.
            //
            // A FULL request level is final once on_request has run: validation refuses an
            // on_response rule that names the request whenever it can be FULL, since a
            // journaled body cannot be un-written. So is the request cap: on_response may not
            // name it.
            final int anchored = checkAndFlushRequest();

            final ByteBuffer kept = capped(data, this.requestTruncateAt, this.requestBodyJournaled);
            if (kept == null)
            {
                this.requestBodyTruncated = true;
                return anchored;
            }
            if (kept != data)
            {
                this.requestBodyTruncated = true;
            }
            if (requestChecksum == null)
            {
                requestChecksum = new CRC32C();
            }
            requestChecksum.update(kept.duplicate());
            this.requestBodyJournaled += kept.remaining();
            final int bodyBytes = delegate.requestBody(reqId, kept);
            this.bytesWritten += bodyBytes;
            return anchored + bodyBytes;
        }
        return 0;
    }

    @Override
    public int responseBody(final String reqId, final ByteBuffer data)
    {
        if (this.responseLevel == JournalLevel.FULL)
        {
            // Anchor the body, for the same reason requestBody does. Below FULL the client
            // request is held back until the exchange completes, but the response body is teed
            // into the journal while it is being sent — before completion. Unanchored, every
            // fragment of a "request HEADERS, response FULL" route reached the reader ahead of
            // any event for its request id, was discarded as an orphan, and the end event then
            // reported a checksum mismatch against a body nobody kept. A response body is teed
            // only after on_response has run, so the levels and the cap here are final.
            final int anchored = checkAndFlushRequest();

            final ByteBuffer kept = capped(data, this.responseTruncateAt, this.responseBodyJournaled);
            if (kept == null)
            {
                this.responseBodyTruncated = true;
                return anchored;
            }
            if (kept != data)
            {
                this.responseBodyTruncated = true;
            }
            if (responseChecksum == null)
            {
                responseChecksum = new CRC32C();
            }
            responseChecksum.update(kept.duplicate());
            this.responseBodyJournaled += kept.remaining();
            final int written = delegate.responseBody(reqId, kept);
            this.bytesWritten += written;
            return anchored + written;
        }
        return 0;
    }

    /**
     * The part of a body fragment the journal keeps under a cap: the fragment itself when it fits
     * whole, a view of its head when the cap falls inside it, or {@code null} when the cap was
     * already reached and the fragment has a byte to drop. The caller's buffer is not moved: it
     * is the one being sent on.
     */
    private static ByteBuffer capped(final ByteBuffer data, final long truncateAt, final long journaled)
    {
        if (truncateAt == JournalPolicy.NO_CAP || journaled + data.remaining() <= truncateAt)
        {
            return data;
        }
        final long room = truncateAt - journaled;
        if (room <= 0)
        {
            return data.hasRemaining() ? null : data;
        }
        final ByteBuffer head = data.duplicate();
        head.limit(head.position() + (int) room);
        return head;
    }

    /**
     * The two checksum arguments, and the two truncation flags, are deliberately <em>not</em>
     * forwarded: this class decides which body bytes the journal keeps, so it alone knows
     * whether it stopped short.
     * <p>
     * This class decides what actually reaches the delegate — it gates every body fragment
     * on the effective journal level — so it is the only layer that can state a checksum
     * over the bytes that were really stored. A caller upstream sees the bytes on the wire,
     * which is a different set whenever the level is below FULL. Recording the caller's
     * value would produce a checksum of bytes the journal does not contain, and the reader
     * would report a mismatch on an intact record.
     * <p>
     * The accumulators are created on the first fragment of their direction, so a direction
     * that journaled nothing has a null one, and {@link BodyChecksum#of(java.util.zip.CRC32C)}
     * turns that into {@link BodyChecksum#NOT_RECORDED} — the difference between "hashes to
     * the CRC32C of nothing" and "there was nothing to hash", which is the difference the
     * reader needs to decide whether to verify at all.
     */
    @Override
    public int endExchange(final String reqId, final GatewayAttributes attributes, final long requestStartTs, final long requestEndTs, final int statusCode, final long requestHeaderBytes, final long requestBodyBytes, final long responseHeaderBytes, final long responseBodyBytes, final long proxyStartTs, final long proxyFirstByteReceivedTs, final long proxyEndTs, final BodyChecksum ignoredRequestChecksum, final BodyChecksum ignoredResponseChecksum, final boolean ignoredRequestBodyTruncated, final boolean ignoredResponseBodyTruncated)
    {
        int written = checkAndFlushRequest();
        written += checkAndFlushResponse();

        final int endBytes = delegate.endExchange(reqId, attributes, requestStartTs, requestEndTs, statusCode, requestHeaderBytes, requestBodyBytes, responseHeaderBytes, responseBodyBytes, proxyStartTs, proxyFirstByteReceivedTs, proxyEndTs, BodyChecksum.of(requestChecksum), BodyChecksum.of(responseChecksum), this.requestBodyTruncated, this.responseBodyTruncated);
        this.bytesWritten += endBytes;
        return written + endBytes;
    }

    @Override
    public void close() throws java.io.IOException
    {
        delegate.close();
    }

    private int checkAndFlushRequest()
    {
        if (clientReqFlushed && upstreamReqFlushed)
        {
            return 0;
        }

        JournalLevel effectiveLevel = this.requestLevel;
        if (effectiveLevel == JournalLevel.NONE && this.responseLevel != JournalLevel.NONE)
        {
            effectiveLevel = JournalLevel.METADATA;
        }

        if (effectiveLevel == JournalLevel.NONE)
        {
            return 0;
        }

        int written = handleClientRequest(effectiveLevel);
        written += handleUpstreamRequest(effectiveLevel);
        return written;
    }

    private int handleUpstreamRequest(final JournalLevel effectiveLevel)
    {
        if (!upstreamReqFlushed && upstreamReqLine != null)
        {
            final GatewayHeaders headers = effectiveLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(upstreamReqHeaders, safeRequestHeaders);
            final int written = delegate.upstreamRequest(effectiveLevel, requestId, upstreamReqLine, headers, clientRequestBase);
            this.bytesWritten += written;
            this.upstreamReqFlushed = true;
            this.upstreamReqLine = null;
            return written;
        }
        return 0;
    }

    private int handleClientRequest(final JournalLevel effectiveLevel)
    {
        if (!clientReqFlushed && clientReqLine != null)
        {
            final GatewayHeaders headers = effectiveLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(clientReqHeaders, safeRequestHeaders);
            this.clientRequestBase = headers;
            final int written = delegate.clientRequest(effectiveLevel, requestId, clientReqLine, headers, remoteAddress, remoteAddressSource);
            this.bytesWritten += written;
            this.clientReqFlushed = true;
            this.clientReqLine = null;
            return written;
        }
        return 0;
    }

    private int checkAndFlushResponse()
    {
        final JournalLevel resLevel = this.responseLevel;
        if (resLevel == JournalLevel.NONE)
        {
            return 0;
        }

        int written = handleUpstreamResponse(resLevel);
        written += handleClientResponse(resLevel);
        return written;
    }

    private int handleClientResponse(final JournalLevel resLevel)
    {
        if (!clientResFlushed && clientResLine != null)
        {
            final GatewayHeaders headers = resLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(clientResHeaders, safeResponseHeaders);
            final int written = delegate.clientResponse(resLevel, requestId, clientStatusCode, clientResLine, headers, upstreamResponseBase);
            this.bytesWritten += written;
            this.clientResFlushed = true;
            this.clientResLine = null;
            return written;
        }
        return 0;
    }

    private int handleUpstreamResponse(final JournalLevel resLevel)
    {
        if (!upstreamResFlushed && upstreamResLine != null)
        {
            final GatewayHeaders headers = resLevel == JournalLevel.METADATA ? FastGatewayHeaders.empty() : redactHeaders(upstreamResHeaders, safeResponseHeaders);
            this.upstreamResponseBase = headers;
            final int written = delegate.upstreamResponse(resLevel, requestId, upstreamStatusCode, upstreamResLine, headers, upstreamResRelayed);
            this.bytesWritten += written;
            this.upstreamResFlushed = true;
            this.upstreamResLine = null;
            return written;
        }
        return 0;
    }

    private GatewayHeaders redactHeaders(final GatewayHeaders original, final HeaderNameSet safeHeaders)
    {
        if (original == null)
        {
            return FastGatewayHeaders.empty();
        }
        // A snapshot, not the view: see RedactingHeaders for why the journal redacts once. It
        // also makes a base exactly what was written. The view was read again when the next set
        // was diffed against it, so a delegate that changed in between would have produced a
        // delta against headers the journal never held; the gateway passes immutable snapshots,
        // so that never happened there, but the base no longer depends on it.
        return new RedactingHeaders(original, safeHeaders, fingerprints).snapshot();
    }

    /**
     * Applied to the request line at every journal level, METADATA included: unlike headers,
     * the query is part of the start line, which every level records.
     */
    private ByteBuffer redactRequestLine(final ByteBuffer startLine)
    {
        if (startLine == null)
        {
            return null;
        }
        final ByteBuffer redacted = RedactingQuery.redactRequestLine(startLine, safeQueryParameters, fingerprint);
        // A new buffer is already this journal's own; the caller's buffer is per-thread and reused.
        return redacted == startLine ? cloneBuffer(startLine) : redacted;
    }

    private ByteBuffer cloneBuffer(final ByteBuffer original)
    {
        if (original == null)
        {
            return null;
        }
        final ByteBuffer copy = ByteBuffer.allocate(original.remaining());
        final int pos = original.position();
        copy.put(original);
        original.position(pos);
        copy.flip();
        return copy;
    }

    public long getBytesWritten()
    {
        return bytesWritten;
    }
}