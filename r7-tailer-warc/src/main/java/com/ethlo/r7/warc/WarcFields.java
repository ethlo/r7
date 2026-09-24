package com.ethlo.r7.warc;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the named-field lists for each WARC record type this writer emits, so the exchange
 * writer states intent ("a request record for this URI, concurrent with those other records")
 * rather than assembling raw WARC header names itself.
 * <p>
 * Fields are an ordered {@code List<Map.Entry>} rather than a {@code Map} because
 * {@code WARC-Concurrent-To} is, per spec, the one WARC field explicitly permitted to repeat
 * within a single record — see {@code design/warc.md}'s four-record shape, where every record
 * names all three others.
 */
final class WarcFields
{
    private WarcFields()
    {
    }

    static String now()
    {
        return Instant.now().toString();
    }

    static String newRecordId()
    {
        return "urn:uuid:" + java.util.UUID.randomUUID();
    }

    private static Map.Entry<String, String> entry(final String key, final String value)
    {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    /**
     * A full {@code request} or {@code response} record: complete headers and the payload
     * that was actually stored for this exchange.
     *
     * @param msgType         {@code "request"} or {@code "response"}, per {@code application/http;msgtype=}
     * @param concurrentToIds every other record id belonging to this exchange's four-record group
     */
    static List<Map.Entry<String, String>> stored(final String msgType, final String targetUri,
                                                   final List<String> concurrentToIds, final String requestId,
                                                   final String payloadDigest)
    {
        final List<Map.Entry<String, String>> fields = header(msgType, targetUri, concurrentToIds, requestId);
        if (payloadDigest != null)
        {
            fields.add(entry("WARC-Payload-Digest", payloadDigest));
        }
        return fields;
    }

    /**
     * A {@code request} or {@code response} record for a leg whose payload is not stored here
     * because it is byte-identical to, and already stored in, another record of the same
     * four-record group — see {@code design/warc.md}: the gateway journal itself only ever
     * stores one copy of a request body and one copy of a response body, so the second leg of
     * each direction never has bytes of its own to write.
     * <p>
     * {@code WARC-Truncated: unspecified} plus {@code WARC-Payload-Digest} of the payload held
     * elsewhere is what the spec allows for "the payload referred to but not contained by the
     * record" - together with {@code WARC-Concurrent-To} that is enough for a reader to resolve
     * it without r7-specific knowledge, without resorting to {@code WARC-Refers-To} (forbidden
     * on {@code request}/{@code response} records) or a {@code revisit} record (wrong semantics
     * for a second hop of the same capture, not a revisitation of previously archived content).
     */
    static List<Map.Entry<String, String>> notStoredElsewhere(final String msgType, final String targetUri,
                                                               final List<String> concurrentToIds, final String requestId,
                                                               final String payloadDigest)
    {
        final List<Map.Entry<String, String>> fields = header(msgType, targetUri, concurrentToIds, requestId);
        fields.add(entry("WARC-Truncated", "unspecified"));
        if (payloadDigest != null)
        {
            fields.add(entry("WARC-Payload-Digest", payloadDigest));
        }
        return fields;
    }

    /**
     * A {@code request} or {@code response} record for a body the journal itself reports as
     * corrupt: {@link com.ethlo.r7.journal.api.ExchangeCompletionListener#onChecksumMismatch}
     * fired for it. The payload is never written and never digested — a digest here would only
     * validate whatever bytes survived, not the payload that actually crossed the wire — and
     * {@code WARC-Truncated: unspecified} plus a custom flag make the corruption visible to a
     * reader instead of silently archiving damaged bytes as an authoritative record.
     */
    static List<Map.Entry<String, String>> checksumMismatch(final String msgType, final String targetUri,
                                                             final List<String> concurrentToIds, final String requestId)
    {
        final List<Map.Entry<String, String>> fields = header(msgType, targetUri, concurrentToIds, requestId);
        fields.add(entry("WARC-Truncated", "unspecified"));
        fields.add(entry("WARC-R7-Checksum-Mismatch", "true"));
        return fields;
    }

    /**
     * A {@code revisit} record: same envelope as {@link #stored}, but pointing back at an
     * earlier record - from a different exchange entirely - that already carries this exact
     * payload, per the WARC 1.1 "identical payload digest" profile (Clause 6.7.2). Reserved for
     * genuine cross-exchange duplicates (a repeated static asset, a cached response); the
     * within-exchange case uses {@link #notStoredElsewhere} instead - see its javadoc for why.
     */
    static List<Map.Entry<String, String>> revisit(final String msgType, final String targetUri,
                                                     final List<String> concurrentToIds, final String requestId,
                                                     final String payloadDigest, final PayloadDedupIndex.RevisitTarget refersTo)
    {
        final List<Map.Entry<String, String>> fields = stored(msgType, targetUri, concurrentToIds, requestId, payloadDigest);
        fields.add(entry("WARC-Profile", "http://netpreserve.org/warc/1.1/revisit/identical-payload-digest"));
        fields.add(entry("WARC-Truncated", "length"));
        fields.add(entry("WARC-Refers-To", "<" + refersTo.recordId() + ">"));
        fields.add(entry("WARC-Refers-To-Target-URI", refersTo.targetUri()));
        fields.add(entry("WARC-Refers-To-Date", refersTo.warcDate()));
        return fields;
    }

    private static List<Map.Entry<String, String>> header(final String msgType, final String targetUri,
                                                            final List<String> concurrentToIds, final String requestId)
    {
        final List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(entry("WARC-Date", now()));
        fields.add(entry("WARC-Target-URI", targetUri));
        fields.add(entry("Content-Type", "application/http; msgtype=" + msgType));
        for (final String id : concurrentToIds)
        {
            fields.add(entry("WARC-Concurrent-To", "<" + id + ">"));
        }
        fields.add(entry("WARC-R7-Request-Id", requestId));
        return fields;
    }
}
