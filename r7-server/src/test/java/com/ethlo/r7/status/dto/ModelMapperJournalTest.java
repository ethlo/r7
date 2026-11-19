package com.ethlo.r7.status.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.journal.JournalPolicy;
import com.ethlo.r7.journal.JournalRule;
import com.ethlo.r7.journal.api.JournalLevel;

/**
 * The management view marks a rule as possibly overridden, per key, by the rules that decide that
 * key before it when they match, and never as overridden outright.
 */
class ModelMapperJournalTest
{
    private static JournalRule rule(final String label, final JournalLevel request, final JournalLevel response)
    {
        return new JournalRule(label, "journal." + label, null, request, response);
    }

    @Test
    void anOuterRuleMayBeOverriddenOnlyForTheKeysAnEarlierRuleAlsoNames()
    {
        // route rule first, then the root's, as they are checked
        final JournalPolicy policy = new JournalPolicy(JournalLevel.METADATA, JournalLevel.METADATA, List.of(), List.of(
                rule("route-posts", null, JournalLevel.NONE),
                rule("root-errors", JournalLevel.HEADERS, JournalLevel.FULL)));

        final List<JournalDto.Rule> rules = ModelMapper.mapJournal(policy).rules();

        assertThat(rules.get(0).mayBeOverriddenBy()).isEmpty();
        assertThat(rules.get(1).mayBeOverriddenBy()).isEqualTo(Map.of("response", List.of("route-posts")));
    }

    @Test
    void anOnRequestRuleMayBeOverriddenByOnResponseRulesNamingTheSameKey()
    {
        final JournalPolicy policy = new JournalPolicy(JournalLevel.METADATA, JournalLevel.METADATA,
                List.of(rule("debug", JournalLevel.HEADERS, JournalLevel.HEADERS)),
                List.of(rule("errors", null, JournalLevel.FULL), rule("quiet", JournalLevel.NONE, null)));

        final List<JournalDto.Rule> rules = ModelMapper.mapJournal(policy).rules();

        assertThat(rules.get(0).mayBeOverriddenBy()).isEqualTo(Map.of("request", List.of("quiet"), "response", List.of("errors")));
        assertThat(rules.get(1).mayBeOverriddenBy()).isEmpty();
        assertThat(rules.get(2).mayBeOverriddenBy()).isEmpty();
    }

    @Test
    void aBodyCapIsItsOwnKey()
    {
        final JournalPolicy policy = new JournalPolicy(JournalLevel.METADATA, JournalLevel.METADATA, List.of(
                new JournalRule("cap", "journal.on_request[0]", null, null, null, 1024, JournalPolicy.NO_CAP),
                new JournalRule("full", "journal.on_request[1]", null, JournalLevel.FULL, null, 4096, JournalPolicy.NO_CAP)), List.of());

        final List<JournalDto.Rule> rules = ModelMapper.mapJournal(policy).rules();

        assertThat(rules.get(1).mayBeOverriddenBy()).isEqualTo(Map.of("request.truncate_body_at", List.of("cap")));
    }
}
