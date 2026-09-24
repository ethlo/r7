package com.ethlo.r7.warc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the named-field maps for each WARC record type this writer emits, so the exchange
 * writer states intent ("a request record for this URI, concurrent with that response") rather
 * than assembling raw WARC header names itself.
 */
final class WarcFields
{
    static final String REVISIT_IDENTICAL_PAYLOAD_DIGEST_PROFILE =
            "http://netpreserve.org/warc/1.1/revisit/identical-payload-digest";

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

    /**
     * A full {@code request} or {@code response} record: complete headers, and a payload if
     * {@code payload} is non-null (may be zero-length, which is different from absent — a GET
     * request legitimately has no body at all).
     *
     * @param msgType {@code "request"} or {@code "response"}, per {@code application/http;msgtype=}
     */
    static Map<String, String> requestOrResponse(final String msgType, final String targetUri, final String concurrentToRecordId,
                                                  final String requestId, final String payloadDigest)
    {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("WARC-Date", now());
        fields.put("WARC-Target-URI", targetUri);
        fields.put("Content-Type", "application/http; msgtype=" + msgType);
        if (concurrentToRecordId != null)
        {
            fields.put("WARC-Concurrent-To", "<" + concurrentToRecordId + ">");
        }
        if (payloadDigest != null)
        {
            fields.put("WARC-Payload-Digest", payloadDigest);
        }
        fields.put("WARC-R7-Request-Id", requestId);
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
    static Map<String, String> requestOrResponseWithChecksumMismatch(final String msgType, final String targetUri,
                                                                       final String concurrentToRecordId, final String requestId)
    {
        final Map<String, String> fields = requestOrResponse(msgType, targetUri, concurrentToRecordId, requestId, null);
        fields.put("WARC-Truncated", "unspecified");
        fields.put("WARC-R7-Checksum-Mismatch", "true");
        return fields;
    }

    /**
     * A {@code revisit} record: same envelope as {@link #requestOrResponse}, but pointing back
     * at the record that already carries this exact payload, per the WARC 1.1
     * "identical payload digest" profile (Clause 6.7.2). The header block itself is still
     * written in full — only the payload is omitted — because the spec recommends preserving
     * response headers on a revisit so a reader does not have to fetch the original record just
     * to see them.
     */
    static Map<String, String> revisit(final String msgType, final String targetUri, final String concurrentToRecordId,
                                        final String requestId, final String payloadDigest,
                                        final PayloadDedupIndex.RevisitTarget refersTo)
    {
        final Map<String, String> fields = requestOrResponse(msgType, targetUri, concurrentToRecordId, requestId, payloadDigest);
        fields.put("WARC-Profile", REVISIT_IDENTICAL_PAYLOAD_DIGEST_PROFILE);
        fields.put("WARC-Truncated", "length");
        fields.put("WARC-Refers-To", "<" + refersTo.recordId() + ">");
        fields.put("WARC-Refers-To-Target-URI", refersTo.targetUri());
        fields.put("WARC-Refers-To-Date", refersTo.warcDate());
        return fields;
    }
}
