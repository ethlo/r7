package com.ethlo.r7.config;

import java.util.Optional;

import com.ethlo.r7.journal.api.JournalLevel;

/**
 * The runtime form of a route's journal block. It is built only from a {@link JournalDefinition} that has
 * already passed validation, which is where the status-override rules live.
 */
public record RouteJournalConfig(JournalDirectionConfig request,
                                 JournalDirectionConfig response)
{
    private static final JournalDirectionConfig NONE = new JournalDirectionConfig(JournalLevel.NONE, null);

    public RouteJournalConfig(final JournalDirectionConfig request, final JournalDirectionConfig response)
    {
        this.request = Optional.ofNullable(request).orElse(NONE);
        this.response = Optional.ofNullable(response).orElse(NONE);
    }

    public boolean isAtLeastMetadata(final int statusCode)
    {
        final int metadataOrdinal = JournalLevel.METADATA.ordinal();
        return this.request.level().ordinal() >= metadataOrdinal
                || this.response.level().ordinal() >= metadataOrdinal
                || this.request.resolve(statusCode).ordinal() >= metadataOrdinal
                || this.response.resolve(statusCode).ordinal() >= metadataOrdinal;
    }
}