package com.ethlo.r7.config;

import java.util.Optional;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record RouteJournalConfig(JournalDirectionConfig request,
                                 JournalDirectionConfig response) implements ValidatableConfig
{
    private static final JournalDirectionConfig NONE = new JournalDirectionConfig(JournalLevel.NONE, null);

    public RouteJournalConfig(final JournalDirectionConfig request, final JournalDirectionConfig response)
    {
        this.request = Optional.ofNullable(request).orElse(NONE);
        this.response = Optional.ofNullable(response).orElse(NONE);
    }

    @Override
    public void validate(final ValidationResult result)
    {
        this.validateDirection(this.request, result.nested("request"));
        this.validateDirection(this.response, result.nested("response"));
    }

    private void validateDirection(final JournalDirectionConfig config, final ValidationResult result)
    {
        if (config.statusOverrides() != null)
        {
            for (int i = 0; i < config.statusOverrides().length; i++)
            {
                final JournalLevel override = config.statusOverrides()[i];
                if (override != null)
                {
                    // Catch things like "43" mapped to index 43
                    if (i < 100)
                    {
                        result.addError("status_overrides",
                                "Invalid HTTP status code '" + i + "' found in overrides. Status codes must be between 100 and 599."
                        );
                    }

                    // Catch the impossible zero-buffering time-travel
                    if (override == JournalLevel.FULL && config.level() != JournalLevel.FULL)
                    {
                        result.addError("status_overrides",
                                "Cannot elevate a status override to FULL unless the base level is already FULL. A zero-buffering proxy cannot retroactively capture streamed bodies."
                        );
                    }
                }
            }
        }
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