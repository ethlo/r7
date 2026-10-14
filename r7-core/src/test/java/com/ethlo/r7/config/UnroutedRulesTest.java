package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.condition.ConditionInput;
import com.ethlo.r7.condition.Draw;
import com.ethlo.r7.condition.Leg;
import com.ethlo.r7.condition.UnroutedReason;
import com.ethlo.r7.journal.JournalPolicy;
import com.ethlo.r7.journal.JournalSecurity;
import com.ethlo.r7.journal.QueryParameterNameSet;
import com.ethlo.r7.journal.StatefulJournal;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.TestFingerprints;

/**
 * {@code unrouted} rules: a rule that reads the request is checked only on a route miss, the
 * one refusal whose request r7 interpreted, under {@code not} too; a rule of only
 * {@code UnroutedReason} and {@code Sample} is checked on every refusal.
 */
class UnroutedRulesTest
{
    @TempDir
    Path dir;

    private RouteRegistry.Snapshot build(final String yaml) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, yaml);
        return new ConfigurationManager(new EngineContext(Map.of())).buildFrom(file);
    }

    private JournalPolicy unrouted(final String journal) throws IOException
    {
        return ((DefaultGatewayRoute) this.build(config(journal)).unrouted()).journal();
    }

    private static String config(final String unroutedJournal)
    {
        return """
                version: unrouted
                routes:
                  - id: r
                    match:
                      - PathPrefix:
                          prefix: /r
                    upstream:
                      targets:
                        - url: http://localhost:1
                unrouted:
                  journal:
                %s
                """.formatted(unroutedJournal.indent(4));
    }

    private static ConditionInput refused(final UnroutedReason reason, final String path)
    {
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.path()).thenReturn(path);
        when(request.uri()).thenReturn(path);
        when(request.method()).thenReturn("GET");
        return new ConditionInput()
        {
            @Override
            public GatewayRequest request(final Leg leg)
            {
                return leg == Leg.CLIENT ? request : null;
            }

            @Override
            public GatewayResponse response(final Leg leg)
            {
                return null;
            }

            @Override
            public double sampleDraw(final Draw draw)
            {
                return 0.5;
            }

            @Override
            public UnroutedReason unroutedReason()
            {
                return reason;
            }
        };
    }

    private static JournalLevel requestLevel(final JournalPolicy policy, final ConditionInput input)
    {
        final StatefulJournal journal = new StatefulJournal(mock(Journal.class), policy, JournalSecurity.SAFE_REQUEST_HEADERS,
                JournalSecurity.SAFE_RESPONSE_HEADERS, QueryParameterNameSet.NONE, TestFingerprints.FINGERPRINT);
        journal.onRequest(input, new FastGatewayAttributes());
        return journal.requestLevel();
    }

    @ParameterizedTest
    @EnumSource(UnroutedReason.class)
    void aRuleReadingTheRequestIsCheckedOnlyOnNoRoute(final UnroutedReason reason) throws IOException
    {
        final JournalPolicy policy = this.unrouted("""
                request: { level: METADATA }
                on_request:
                  - match:
                      - PathPrefix:
                          prefix: /api
                    request: { level: HEADERS }
                """);
        assertThat(requestLevel(policy, refused(reason, "/api/x")))
                .isEqualTo(reason == UnroutedReason.NO_ROUTE ? JournalLevel.HEADERS : JournalLevel.METADATA);
    }

    /**
     * Gating each leaf would leave {@code not} free to turn a skipped leaf true: a rule written
     * to silence misses would also silence a smuggling attempt.
     */
    @ParameterizedTest
    @EnumSource(UnroutedReason.class)
    void aRuleReadingTheRequestUnderNotIsCheckedOnlyOnNoRoute(final UnroutedReason reason) throws IOException
    {
        final JournalPolicy policy = this.unrouted("""
                request: { level: HEADERS }
                on_request:
                  - match:
                      - not:
                          PathPrefix:
                            prefix: /api
                    request: { level: NONE }
                """);
        assertThat(requestLevel(policy, refused(reason, "/other")))
                .isEqualTo(reason == UnroutedReason.NO_ROUTE ? JournalLevel.NONE : JournalLevel.HEADERS);
    }

    @ParameterizedTest
    @EnumSource(UnroutedReason.class)
    void aRuleOfOnlyUnroutedReasonIsCheckedOnEveryRefusal(final UnroutedReason reason) throws IOException
    {
        final JournalPolicy policy = this.unrouted("""
                request: { level: METADATA }
                on_request:
                  - match:
                      - not:
                          UnroutedReason: { include: [no_route] }
                    request: { level: HEADERS }
                """);
        assertThat(requestLevel(policy, refused(reason, "/x")))
                .isEqualTo(reason == UnroutedReason.NO_ROUTE ? JournalLevel.METADATA : JournalLevel.HEADERS);
    }

    @ParameterizedTest
    @EnumSource(UnroutedReason.class)
    void aRuleOfOnlySampleIsCheckedOnEveryRefusal(final UnroutedReason reason) throws IOException
    {
        final JournalPolicy policy = this.unrouted("""
                request: { level: METADATA }
                on_request:
                  - match:
                      - Sample: { rate: 1 }
                    request: { level: HEADERS }
                """);
        assertThat(requestLevel(policy, refused(reason, "/x"))).isEqualTo(JournalLevel.HEADERS);
    }

    @Test
    void theFirstMatchingRuleWinsAsInTheDesignExample() throws IOException
    {
        final JournalPolicy policy = this.unrouted("""
                request: { level: HEADERS }
                on_request:
                  - id: api-misses
                    match:
                      - PathPrefix:
                          prefix: /api
                    request: { level: HEADERS }
                  - id: skip-other-misses
                    match:
                      - UnroutedReason: { include: [no_route] }
                    request: { level: NONE }
                """);
        assertThat(requestLevel(policy, refused(UnroutedReason.NO_ROUTE, "/api/gone"))).isEqualTo(JournalLevel.HEADERS);
        assertThat(requestLevel(policy, refused(UnroutedReason.NO_ROUTE, "/wp-login.php"))).isEqualTo(JournalLevel.NONE);
        assertThat(requestLevel(policy, refused(UnroutedReason.TRACE, "/api/x"))).isEqualTo(JournalLevel.HEADERS);
        assertThat(policy.isSilent()).isFalse();
    }

    @Test
    void unroutedReasonIsRefusedOutsideUnrouted()
    {
        assertThatThrownBy(() -> this.build("""
                version: unrouted
                routes:
                  - id: api
                    match:
                      - UnroutedReason: { include: [no_route] }
                    upstream:
                      targets:
                        - url: http://localhost:1
                """))
                .hasMessageContaining("routes.api.match")
                .hasMessageContaining("UnroutedReason applies only under unrouted.");
        assertThatThrownBy(() -> this.build("""
                version: unrouted
                journal:
                  on_request:
                    - match:
                        - UnroutedReason: { include: [no_route] }
                      request: { level: HEADERS }
                routes:
                  - id: r
                    match: []
                    upstream:
                      targets:
                        - url: http://localhost:1
                """))
                .hasMessageContaining("journal.on_request[0]")
                .hasMessageContaining("UnroutedReason applies only under unrouted.");
    }

    @Test
    void anUnknownReasonIsRefused()
    {
        assertThatThrownBy(() -> this.unrouted("""
                on_request:
                  - match:
                      - UnroutedReason: { include: [not_found] }
                    request: { level: HEADERS }
                """))
                .hasMessageContaining("unrouted.journal.on_request[0]")
                .hasMessageContaining("Unknown reason 'not_found'");
    }

    @Test
    void unroutedReasonHasNoLeg()
    {
        assertThatThrownBy(() -> this.unrouted("""
                on_request:
                  - match:
                      - UnroutedReason: { include: [trace], leg: client }
                    request: { level: HEADERS }
                """))
                .hasMessageContaining("UnroutedReason.leg");
    }

    @Test
    void fullIsRefusedInARule()
    {
        assertThatThrownBy(() -> this.unrouted("""
                on_request:
                  - match:
                      - UnroutedReason: { include: [trace] }
                    response: { level: FULL }
                """))
                .hasMessageContaining("unrouted.journal.on_request[0].response.level")
                .hasMessageContaining("FULL is not allowed");
    }

    @Test
    void onResponseIsRefused()
    {
        assertThatThrownBy(() -> this.unrouted("""
                on_response:
                  - match:
                      - Status: { include: [404] }
                    response: { level: HEADERS }
                """))
                .hasMessageContaining("unrouted.journal.on_response")
                .hasMessageContaining("r7's answer to a refused request is fixed by its reason; use on_request.");
    }

    @Test
    void ruleIdsAreUniqueAcrossUnroutedAndRoutes()
    {
        assertThatThrownBy(() -> this.build("""
                version: unrouted
                journal:
                  on_request:
                    - id: twice
                      match:
                        - Method: { include: [POST] }
                      request: { level: HEADERS }
                routes:
                  - id: r
                    match: []
                    upstream:
                      targets:
                        - url: http://localhost:1
                unrouted:
                  journal:
                    on_request:
                      - id: twice
                        match:
                          - UnroutedReason: { include: [trace] }
                        request: { level: HEADERS }
                """))
                .hasMessageContaining("'twice' is already the id of journal.on_request[0]");
    }
}
