package com.ethlo.r7.api;

import java.util.Objects;

import com.ethlo.r7.doc.Nullable;

/**
 * Who made the request, as an authentication filter verified it. A filter sets it with
 * {@code exchange.setAttachment(Identity.KEY, identity)} once the credentials have passed, and
 * the gateway journals its scheme, issuer, subject and username in plain text: the journal is an
 * audit record, and who made a request is what it exists to answer. If two filters set it, the
 * last one wins.
 *
 * @param scheme   how the caller was authenticated, such as {@code basic} or {@code jwt}
 * @param issuer   who vouches for the caller, such as a token's {@code iss}; null when the gateway checked the credentials itself
 * @param subject  the caller's stable id, such as a token's {@code sub} or a Basic username
 * @param username a readable name for the caller, when the credentials carry one; may equal the subject
 * @param claims   the verified claims, for later filters; {@link Claims#NONE} when there are none
 */
public record Identity(String scheme, @Nullable String issuer, String subject, @Nullable String username, Claims claims)
{
    public static final StateKey<Identity> KEY = new StateKey<>("gateway.identity");

    public Identity
    {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(claims, "claims");
    }
}
