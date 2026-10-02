package com.ethlo.r7.journal;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * The query parameter names whose values the journal records in plain text. Every other
 * parameter's value is fingerprinted, exactly as a header outside the safe header list is.
 * <p>
 * Names are compared after percent-decoding, so {@code user%5Fid} and {@code user_id} are the
 * same parameter, as they are to the upstream. Unlike header names, query parameter names are
 * case sensitive (RFC 3986 gives them no case rules, and most frameworks distinguish
 * {@code id} from {@code ID}), so matching is exact by default. An upstream that folds case
 * would read {@code Page} as {@code page}; {@code ignoreCase} lets the safe list follow it.
 * Exact matching is the default because the safe list only ever decides what is shown: a name
 * that fails to match is fingerprinted, never exposed.
 */
public final class QueryParameterNameSet
{
    /**
     * No safe names: every value is fingerprinted. The default when nothing is configured.
     */
    public static final QueryParameterNameSet NONE = new QueryParameterNameSet(Set.of(), false);

    private final Set<String> names;
    private final boolean ignoreCase;

    private QueryParameterNameSet(final Set<String> names, final boolean ignoreCase)
    {
        this.names = names;
        this.ignoreCase = ignoreCase;
    }

    /**
     * @param names      decoded parameter names
     * @param ignoreCase whether {@code Page} matches a safe {@code page}
     */
    public static QueryParameterNameSet of(final Collection<String> names, final boolean ignoreCase)
    {
        if (names.isEmpty())
        {
            return ignoreCase ? new QueryParameterNameSet(Set.of(), true) : NONE;
        }
        if (!ignoreCase)
        {
            return new QueryParameterNameSet(Set.copyOf(names), false);
        }
        // A sorted set under String.CASE_INSENSITIVE_ORDER looks a name up without allocating a
        // folded copy of it, and folds per character, so the result does not depend on the
        // default locale.
        final TreeSet<String> folded = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        folded.addAll(names);
        return new QueryParameterNameSet(folded, true);
    }

    public boolean contains(final String decodedName)
    {
        return decodedName != null && names.contains(decodedName);
    }

    public Set<String> names()
    {
        return names;
    }

    public boolean ignoreCase()
    {
        return ignoreCase;
    }
}
