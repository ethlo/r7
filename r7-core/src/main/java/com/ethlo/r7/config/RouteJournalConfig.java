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
        this.validateDirection(this.request, result.nested("request"), true);
        this.validateDirection(this.response, result.nested("response"), false);
    }

    private void validateDirection(final JournalDirectionConfig config, final ValidationResult result, final boolean isRequest)
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

                    // The same impossibility in the other direction, and only on the request
                    // side. Request bodies stream into the journal while the request is being
                    // read, which is before any status exists to resolve against — so by the
                    // time a 404 is known, the body has already been written and cannot be
                    // un-written. Silently honouring the override would be a lie; silently
                    // ignoring it would disclose what the operator asked not to keep. Refusing
                    // the configuration is the only honest option.
                    //
                    // The response side has no such problem: the status is known before any
                    // response body is journaled, so a downgrade there is applied correctly.
                    if (isRequest
                            && config.level() == JournalLevel.FULL
                            && override.ordinal() < JournalLevel.FULL.ordinal())
                    {
                        result.addError("status_overrides",
                                "Cannot lower a request status override below FULL when the base request level is FULL"
                                        + " (status " + i + " maps to " + override + "). Request bodies are streamed to the"
                                        + " journal before the response status is known, so the override could not be"
                                        + " honoured. Lower the base request level, or drop the override."
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