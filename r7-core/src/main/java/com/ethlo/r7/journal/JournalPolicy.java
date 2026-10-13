package com.ethlo.r7.journal;

import java.util.List;

import com.ethlo.r7.journal.api.JournalLevel;

/**
 * A route's journal levels as configuration resolves them: the base level of each direction,
 * from the innermost level naming it, and the rules that may replace it, each list in the order
 * checked: the route's, then its group's, then the root's.
 */
public final class JournalPolicy
{
    public static final JournalPolicy NONE = new JournalPolicy(JournalLevel.NONE, JournalLevel.NONE, List.of(), List.of());

    private final JournalLevel request;
    private final JournalLevel response;
    private final JournalRule[] onRequest;
    private final JournalRule[] onResponse;
    private final boolean mayJournal;

    public JournalPolicy(final JournalLevel request, final JournalLevel response, final List<JournalRule> onRequest, final List<JournalRule> onResponse)
    {
        this.request = request;
        this.response = response;
        this.onRequest = onRequest.toArray(new JournalRule[0]);
        this.onResponse = onResponse.toArray(new JournalRule[0]);
        boolean any = request != JournalLevel.NONE || response != JournalLevel.NONE;
        for (final JournalRule rule : this.rules())
        {
            any |= isRecorded(rule.request()) || isRecorded(rule.response());
        }
        this.mayJournal = any;
    }

    public static JournalPolicy of(final JournalLevel request, final JournalLevel response)
    {
        return new JournalPolicy(request, response, List.of(), List.of());
    }

    private static boolean isRecorded(final JournalLevel level)
    {
        return level != null && level != JournalLevel.NONE;
    }

    public JournalLevel request()
    {
        return this.request;
    }

    public JournalLevel response()
    {
        return this.response;
    }

    JournalRule[] onRequestRules()
    {
        return this.onRequest;
    }

    JournalRule[] onResponseRules()
    {
        return this.onResponse;
    }

    public List<JournalRule> onRequest()
    {
        return List.of(this.onRequest);
    }

    public List<JournalRule> onResponse()
    {
        return List.of(this.onResponse);
    }

    /**
     * @return every rule, {@code on_request} first, each list in the order checked
     */
    public List<JournalRule> rules()
    {
        final JournalRule[] all = new JournalRule[this.onRequest.length + this.onResponse.length];
        System.arraycopy(this.onRequest, 0, all, 0, this.onRequest.length);
        System.arraycopy(this.onResponse, 0, all, this.onRequest.length, this.onResponse.length);
        return List.of(all);
    }

    /**
     * @return false when neither the base levels nor any rule can record anything: such an
     * exchange needs no request start line kept for completion
     */
    public boolean mayJournal()
    {
        return this.mayJournal;
    }
}
