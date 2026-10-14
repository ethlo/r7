package com.ethlo.r7.status.dto;

import java.util.List;
import java.util.Map;

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
     * @param mayBeOverriddenBy      per key it names ({@code request}, {@code response},
     *                               {@code request.truncate_body_at},
     *                               {@code response.truncate_body_at}), the labels of the rules
     *                               that take precedence for that key when they match: earlier
     *                               rules of its stage, and for {@code on_request} every
     *                               {@code on_response} rule. Never a claim that the rule is
     *                               overridden, only that it may be
     */
    public record Rule(String stage, String label, String path, JournalLevel request, JournalLevel response,
                       Long requestTruncateBodyAt, Long responseTruncateBodyAt, long ruleErrors,
                       Map<String, List<String>> mayBeOverriddenBy)
    {
    }
}
