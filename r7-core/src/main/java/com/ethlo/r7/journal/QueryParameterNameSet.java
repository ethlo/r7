package com.ethlo.r7.journal;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * The query parameter names whose values the journal records in plain text. Every other
 * parameter's value is fingerprinted, exactly as a header outside the safe header list is.
 * <p>
 * Names are compared after percent-decoding, so {@code user%5Fid} and {@code user_id} are the
 * same parameter, as they are to the upstream. Query parameter names are case sensitive on the
 * wire (RFC 3986 gives them no case rules), but an operator who lists {@code page} means that
 * parameter however a client spells it, so the server configuration matches regardless of case
 * unless told otherwise. Exact matching is there for upstreams that distinguish {@code id}
 * from {@code ID}, where a safe {@code id} says nothing about {@code ID}.
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
