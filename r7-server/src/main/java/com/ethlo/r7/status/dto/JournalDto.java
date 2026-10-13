package com.ethlo.r7.status.dto;

import java.util.List;

import com.ethlo.r7.journal.api.JournalLevel;

/**
 * A route's journal policy: its base levels as inherited, and every rule that applies to it, in
 * the order checked.
 */
public record JournalDto(JournalLevel requestLevel, JournalLevel responseLevel, List<Rule> rules)
{
    /**
     * @param stage    {@code on_request} or {@code on_response}
     * @param label    the rule's id, else its path
     * @param request  the request level it sets, or {@code null}
     * @param response the response level it sets, or {@code null}
     */
    public record Rule(String stage, String label, String path, JournalLevel request, JournalLevel response)
    {
    }
}
