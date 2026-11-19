package com.ethlo.r7.journal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import com.ethlo.r7.condition.Condition;
import com.ethlo.r7.condition.ConditionInput;
import com.ethlo.r7.journal.api.JournalLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A compiled journal rule. Built once per declaration and per configuration load, so a root or
 * group rule is one instance for every route beneath it, and so are its error count and its
 * once-per-load warning.
 */
public final class JournalRule
{
    private static final Logger logger = LoggerFactory.getLogger(JournalRule.class);

    static final int NOT_MATCHED = 0;
    static final int MATCHED = 1;
    static final int FAILED = 2;

    private final String label;
    private final String path;
    private final Condition condition;
    private final JournalLevel request;
    private final JournalLevel response;
    private final long requestTruncateAt;
    private final long responseTruncateAt;
    private final LongAdder errors = new LongAdder();
    private final AtomicBoolean warned = new AtomicBoolean();

    /**
     * @param label    what {@code gateway.journal.rule} records: the rule's id, else its path
     * @param path     where the rule is declared, as validation names it
     * @param request  the request level the rule sets, or {@code null} for none
     * @param response the response level the rule sets, or {@code null} for none
     */
    public JournalRule(final String label, final String path, final Condition condition, final JournalLevel request, final JournalLevel response)
    {
        this(label, path, condition, request, response, JournalPolicy.NO_CAP, JournalPolicy.NO_CAP);
    }

    /**
     * @param requestTruncateAt  the request body cap in bytes the rule sets, or
     *                           {@link JournalPolicy#NO_CAP} when it names none
     * @param responseTruncateAt as above, for the response body
     */
    public JournalRule(final String label, final String path, final Condition condition, final JournalLevel request, final JournalLevel response,
                       final long requestTruncateAt, final long responseTruncateAt)
    {
        this.label = label;
        this.path = path;
        this.condition = condition;
        this.request = request;
        this.response = response;
        this.requestTruncateAt = requestTruncateAt;
        this.responseTruncateAt = responseTruncateAt;
    }

    public String label()
    {
        return this.label;
    }

    public String path()
    {
        return this.path;
    }

    public Condition condition()
    {
        return this.condition;
    }

    public JournalLevel request()
    {
        return this.request;
    }

    public JournalLevel response()
    {
        return this.response;
    }

    /**
     * @return the request body cap in bytes the rule sets, or {@link JournalPolicy#NO_CAP}
     */
    public long requestTruncateAt()
    {
        return this.requestTruncateAt;
    }

    /**
     * @return the response body cap in bytes the rule sets, or {@link JournalPolicy#NO_CAP}
     */
    public long responseTruncateAt()
    {
        return this.responseTruncateAt;
    }

    /**
     * @return how often the rule's condition threw since the configuration was loaded
     */
    public long errors()
    {
        return this.errors.sum();
    }

    /**
     * @return {@link #MATCHED}, {@link #NOT_MATCHED}, or {@link #FAILED} when the condition
     * threw, which is counted, and logged the first time after each load. A failed rule does not
     * fire: the whole condition counts as false, not the leaf that threw, since under {@code not}
     * a false leaf would turn the rule on.
     */
    int evaluate(final ConditionInput input)
    {
        try
        {
            return this.condition.test(input) ? MATCHED : NOT_MATCHED;
        }
        catch (final RuntimeException e)
        {
            this.errors.increment();
            // Later failures only count: a failing rule on a hot route must not flood the log.
            if (this.warned.compareAndSet(false, true))
            {
                logger.warn("Journal rule {} could not be evaluated and did not fire; further failures of it are only counted", this.path, e);
            }
            return FAILED;
        }
    }
}
