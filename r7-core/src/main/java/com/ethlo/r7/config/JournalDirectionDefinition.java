package com.ethlo.r7.config;

import java.util.Map;
import java.util.TreeMap;

import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record JournalDirectionDefinition(JournalLevel level,
                                         Map<String, JournalLevel> statusOverrides) implements ValidatableConfig
{
    public JournalDirectionDefinition(final JournalLevel level, final Map<String, JournalLevel> statusOverrides)
    {
        this.level = level != null ? level : JournalLevel.NONE;
        this.statusOverrides = statusOverrides != null ? statusOverrides : new TreeMap<>();
    }

    /**
     * Rules that hold for both directions. Request-only rules are in {@link #validateRequestSide}, which
     * {@link JournalDefinition} calls because only it knows which direction this is.
     */
    @Override
    public void validate(final ValidationResult result)
    {
        for (final Map.Entry<String, JournalLevel> entry : this.statusOverrides.entrySet())
        {
            final String key = entry.getKey();

            // Keys are checked here, not left to JournalOverrideParser at instantiation: there a bad
            // key surfaced as a bare NumberFormatException/ArrayIndexOutOfBounds stack trace at
            // startup, and as an opaque failure on hot reload, with nothing naming the route.
            try
            {
                JournalOverrideParser.parseKey(key);
            }
            catch (final IllegalArgumentException e)
            {
                result.addError("status_overrides", e.getMessage());
                continue;
            }

            // Body capture is decided from the base level alone, before any status exists. On the
            // request side that is inherent: the body streams into the journal while it is being
            // read. On the response side the status *is* known before the first body byte, but
            // R7UndertowHandler.setupJournaling only installs the response tee when the base level
            // is FULL, so an elevated override would never see a body. Accepting it would record
            // metadata and headers under a FULL label with the body silently missing. Lifting this
            // for responses means installing the tee whenever any response override is FULL —
            // StatefulJournal.responseBody already gates on the resolved level.
            if (entry.getValue() == JournalLevel.FULL && this.level != JournalLevel.FULL)
            {
                result.addError("status_overrides",
                        "Cannot elevate status override '" + key + "' to FULL when the base level is " + this.level
                                + ". Bodies are only captured when the base level is FULL; set the base level to FULL"
                                + " and lower the statuses you do not want to keep, or use HEADERS for this override."
                );
            }
        }
    }

    /**
     * Rules that only apply to the request direction. Must run after {@link #validate}; keys that fail to
     * parse have already been reported there and are skipped here.
     */
    void validateRequestSide(final ValidationResult result)
    {
        if (this.level != JournalLevel.FULL)
        {
            return;
        }

        for (final Map.Entry<String, JournalLevel> entry : this.statusOverrides.entrySet())
        {
            // Request bodies stream into the journal while the request is being read, which is
            // before any status exists to resolve against — so by the time a 404 is known, the body
            // has already been written and cannot be un-written. Silently honouring the override
            // would be a lie; silently ignoring it would disclose what the operator asked not to
            // keep. StatefulJournal.requestBody relies on this rule to never anchor a body to a
            // request it then journals at a lower level.
            //
            // The response side has no such problem: the status is known before any response
            // body is journaled, so a downgrade there is applied correctly.
            if (entry.getValue() != JournalLevel.FULL && isValidKey(entry.getKey()))
            {
                result.addError("status_overrides",
                        "Cannot lower request status override '" + entry.getKey() + "' to " + entry.getValue()
                                + " when the base request level is FULL. Request bodies are streamed to the journal"
                                + " before the response status is known, so the override could not be honoured."
                                + " Lower the base request level, or drop the override."
                );
            }
        }
    }

    private static boolean isValidKey(final String key)
    {
        try
        {
            JournalOverrideParser.parseKey(key);
            return true;
        }
        catch (final IllegalArgumentException e)
        {
            return false;
        }
    }
}
