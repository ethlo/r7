package com.ethlo.venturi.config;

import java.util.Optional;

import com.ethlo.venturi.journal.api.JournalLevel;
import com.ethlo.venturi.validation.ValidatableConfig;
import com.ethlo.venturi.validation.ValidationResult;

public record RouteJournalConfig(
        JournalDirectionConfig request,
        JournalDirectionConfig response
) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        if (request != null)
        {
            validateDirection("request", request, result);
        }

        if (response != null)
        {
            validateDirection("response", response, result);
        }
    }

    private void validateDirection(final String directionName, final JournalDirectionConfig config, final ValidationResult result)
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
                        result.addError("journal." + directionName + ".status_overrides",
                                "Invalid HTTP status code '" + i + "' found in overrides. Status codes must be between 100 and 599."
                        );
                    }

                    // Catch the impossible zero-buffering time-travel
                    if (override == JournalLevel.FULL && config.level() != JournalLevel.FULL)
                    {
                        result.addError("journal." + directionName + ".status_overrides",
                                "Cannot elevate a status override to FULL unless the base level is already FULL. A zero-buffering proxy cannot retroactively capture streamed bodies."
                        );
                    }
                }
            }
        }
    }

    public JournalDirectionConfig request()
    {
        return Optional.ofNullable(request).orElse(new JournalDirectionConfig(JournalLevel.NONE, null));
    }

    public JournalDirectionConfig response()
    {
        return Optional.ofNullable(response).orElse(new JournalDirectionConfig(JournalLevel.NONE, null));
    }
}