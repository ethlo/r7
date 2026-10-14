package com.ethlo.r7.status.dto;

import java.util.List;

import com.ethlo.r7.journal.api.JournalLevel;

/**
 * A route's journal policy: its base levels as inherited, and every rule that applies to it, in
 * the order checked. A body cap is {@code null} when none applies.
 */
public record JournalDto(JournalLevel requestLevel, JournalLevel responseLevel, Long requestTruncateBodyAt, Long responseTruncateBodyAt, List<Rule> rules)
{
    /**
     * @param stage                  {@code on_request} or {@code on_response}
     * @param label                  the rule's id, else its path
     * @param request                the request level it sets, or {@code null}
     * @param response               the response level it sets, or {@code null}
     * @param requestTruncateBodyAt  the request body cap in bytes it sets, or {@code null}
     * @param responseTruncateBodyAt the response body cap in bytes it sets, or {@code null}
     * @param ruleErrors             how often its condition could not be evaluated since the
     *                               configuration was loaded, on every route it applies to,
     *                               whether or not a record was written
     */
    public record Rule(String stage, String label, String path, JournalLevel request, JournalLevel response,
                       Long requestTruncateBodyAt, Long responseTruncateBodyAt, long ruleErrors)
    {
    }
}
