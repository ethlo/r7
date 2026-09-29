package com.ethlo.r7.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounds the work a single regular-expression match may do.
 * <p>
 * Route predicates and filters match operator-supplied patterns against client-supplied text -
 * paths, headers, cookies, query parameters - on the XNIO I/O threads. {@link Pattern} backtracks,
 * so a pattern such as {@code ^(a+)+$} takes exponential time on a crafted input, and a handful of
 * such requests would stall every I/O thread. The engine reads its input only through
 * {@link CharSequence#charAt}, so counting those reads bounds the CPU a match can take whatever the
 * pattern; a match that exceeds the budget throws, and the pipeline fails closed with a 500 rather
 * than guessing whether the text would have matched.
 * <p>
 * The budget is far above what a linear match needs on any input the listener admits (headers are
 * bounded by {@code max_header_size}), so only a pattern that has gone super-linear reaches it -
 * and a match that does is stopped within a few milliseconds.
 */
public final class RegexBudget
{
    /**
     * Character reads allowed per match. Exhausting it takes about 4 ms, so a malicious request
     * costs an I/O thread no more than that; a linear pattern needs about one read per character,
     * 8,000 on the largest header the listener admits, which leaves more than a hundredfold headroom.
     */
    public static final long MAX_CHARACTER_READS = 1_000_000L;

    /**
     * The longest input the budget is sized for. An unanchored {@code find()} retries from every
     * start position, so even a benign pattern can read an input several times over; a sixteenfold
     * margin keeps ordinary patterns clear of the budget. Anything that bounds matched input -
     * the listener's header size, which also bounds the request line - must stay within this.
     */
    public static final int MAX_INPUT_LENGTH = (int) (MAX_CHARACTER_READS / 16);

    private RegexBudget()
    {
    }

    /**
     * A matcher over {@code input} that fails with {@link RegexBudgetExceededException} once it has
     * read {@link #MAX_CHARACTER_READS} characters.
     */
    public static Matcher matcher(final Pattern pattern, final CharSequence input)
    {
        return pattern.matcher(new BudgetedCharSequence(input, pattern, MAX_CHARACTER_READS));
    }

    static final class BudgetedCharSequence implements CharSequence
    {
        private final CharSequence delegate;
        private final Pattern pattern;
        private final long maxReads;
        private long reads;

        BudgetedCharSequence(final CharSequence delegate, final Pattern pattern, final long maxReads)
        {
            this.delegate = delegate;
            this.pattern = pattern;
            this.maxReads = maxReads;
        }

        @Override
        public char charAt(final int index)
        {
            if (++this.reads > this.maxReads)
            {
                throw new RegexBudgetExceededException(this.pattern, this.delegate.length());
            }
            return this.delegate.charAt(index);
        }

        @Override
        public int length()
        {
            return this.delegate.length();
        }

        /**
         * Group extraction and replacement copy out of the input; that is not matching work.
         */
        @Override
        public CharSequence subSequence(final int start, final int end)
        {
            return this.delegate.subSequence(start, end);
        }

        @Override
        public String toString()
        {
            return this.delegate.toString();
        }
    }

    public static final class RegexBudgetExceededException extends RuntimeException
    {
        RegexBudgetExceededException(final Pattern pattern, final int inputLength)
        {
            // The pattern is configuration, and may be a shared secret: name its length, not it.
            super("Regular expression exceeded its budget of " + MAX_CHARACTER_READS + " character reads on an input of "
                    + inputLength + " characters (pattern of " + pattern.pattern().length() + " characters); "
                    + "the pattern backtracks super-linearly and should be rewritten");
        }
    }
}
