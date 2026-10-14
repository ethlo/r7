package com.ethlo.r7.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.io.TempDir;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ethlo.r7.api.GatewayRequest;
import com.ethlo.r7.api.GatewayResponse;
import com.ethlo.r7.condition.ConditionInput;
import com.ethlo.r7.condition.Draw;
import com.ethlo.r7.condition.Leg;
import com.ethlo.r7.journal.JournalPolicy;
import com.ethlo.r7.journal.JournalRule;
import com.ethlo.r7.journal.JournalSecurity;
import com.ethlo.r7.journal.QueryParameterNameSet;
import com.ethlo.r7.journal.StatefulJournal;
import com.ethlo.r7.journal.api.Journal;
import com.ethlo.r7.journal.api.JournalLevel;
import com.ethlo.r7.spi.EngineContext;
import com.ethlo.r7.util.FastGatewayAttributes;
import com.ethlo.r7.util.MutableFastGatewayHeaders;
import com.ethlo.r7.util.TestFingerprints;

/**
 * Journal rules: {@code on_request} and {@code on_response} at the root, a group and a route,
 * how they resolve the two levels, and what loading refuses.
 */
class JournalRulesTest
{
    @TempDir
    Path dir;

    private RouteRegistry.Snapshot build(final String yaml) throws IOException
    {
        final Path file = this.dir.resolve("routes.yaml");
        Files.writeString(file, yaml);
        return new ConfigurationManager(new EngineContext(Map.of())).buildFrom(file);
    }

    private JournalPolicy policy(final String yaml, final String routeId) throws IOException
    {
        return ((DefaultGatewayRoute) this.build(yaml).routes().stream().filter(r -> r.id().equals(routeId)).findFirst().orElseThrow()).journal();
    }

    private static String route(final String journal)
    {
        return """
                version: rules
                routes:
                  - id: r
                    match:
                      - PathPrefix:
                          prefix: /r
                    upstream:
                      targets:
                        - url: http://localhost:1
                    journal:
                %s
                """.formatted(journal.indent(6));
    }

    private static ConditionInput exchange(final String path, final int status, final String header, final String value)
    {
        return exchange(path, status, header, value, "GET");
    }

    private static ConditionInput exchange(final String path, final int status, final String header, final String value, final String method)
    {
        final GatewayRequest request = mock(GatewayRequest.class);
        when(request.path()).thenReturn(path);
        when(request.uri()).thenReturn(path);
        when(request.method()).thenReturn(method);
        final GatewayResponse response = mock(GatewayResponse.class);
        when(response.status()).thenReturn(status);
        final MutableFastGatewayHeaders headers = new MutableFastGatewayHeaders();
        if (header != null)
        {
            headers.add(header, value);
        }
        when(response.headers()).thenReturn(headers);
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
                return leg == Leg.CLIENT ? response : null;
            }

            @Override
            public double sampleDraw(final Draw draw)
            {
                return 0.5;
            }
        };
    }

    private record Resolved(JournalLevel request, JournalLevel response, List<String> rules, List<String> errors)
    {
    }

    private static StatefulJournal resolveJournal(final JournalPolicy policy, final ConditionInput input)
    {
        final StatefulJournal journal = new StatefulJournal(mock(Journal.class), policy, JournalSecurity.SAFE_REQUEST_HEADERS,
                JournalSecurity.SAFE_RESPONSE_HEADERS, QueryParameterNameSet.NONE, TestFingerprints.FINGERPRINT);
        final FastGatewayAttributes attributes = new FastGatewayAttributes();
        journal.onRequest(input, attributes);
        journal.onResponse(input, attributes);
        return journal;
    }

    private static Resolved resolve(final JournalPolicy policy, final ConditionInput input)
    {
        final StatefulJournal journal = new StatefulJournal(mock(Journal.class), policy, JournalSecurity.SAFE_REQUEST_HEADERS,
                JournalSecurity.SAFE_RESPONSE_HEADERS, QueryParameterNameSet.NONE, TestFingerprints.FINGERPRINT);
        final FastGatewayAttributes attributes = new FastGatewayAttributes();
        journal.onRequest(input, attributes);
        journal.onResponse(input, attributes);
        return new Resolved(journal.requestLevel(), journal.responseLevel(), values(attributes, StatefulJournal.RULE_KEY), values(attributes, StatefulJournal.RULE_ERROR_KEY));
    }

    private static List<String> values(final FastGatewayAttributes attributes, final String key)
    {
        final List<String> values = new ArrayList<>();
        attributes.getAll(key).forEach(values::add);
        return values;
    }

    private static final String LEVELS = """
            version: rules
            journal:
              request:
                level: METADATA
              response:
                level: METADATA
              on_response:
                - id: root-errors
                  match:
                    - Status:
                        include: [5xx]
                  request: { level: HEADERS }
                  response: { level: FULL }
            routes:
              - group: api
                journal:
                  response:
                    level: HEADERS
                  on_response:
                    - id: group-teapot
                      match:
                        - Status:
                            include: [418, 503]
                      response: { level: NONE }
                routes:
                  - id: orders
                    match:
                      - PathPrefix:
                          prefix: /orders
                    upstream:
                      targets:
                        - url: http://localhost:1
                    journal:
                      on_request:
                        - id: orders-debug
                          match:
                            - Path:
                                path: /orders/debug
                          request: { level: HEADERS }
            """;

    @Test
    void baseLevelsAreInheritedPerKeyFromTheInnermostLevelNamingThem() throws IOException
    {
        final JournalPolicy policy = this.policy(LEVELS, "orders");
        assertThat(policy.request()).isEqualTo(JournalLevel.METADATA);
        assertThat(policy.response()).isEqualTo(JournalLevel.HEADERS);
        assertThat(policy.onResponse()).extracting(r -> r.label()).containsExactly("group-teapot", "root-errors");
    }

    @Test
    void noRuleMatchingLeavesTheBaseLevels() throws IOException
    {
        final Resolved resolved = resolve(this.policy(LEVELS, "orders"), exchange("/orders/1", 200, null, null));
        assertThat(resolved).isEqualTo(new Resolved(JournalLevel.METADATA, JournalLevel.HEADERS, List.of(), List.of()));
    }

    @Test
    void anInnerRuleDecidesItsKeyAndAnOuterRuleStillDecidesTheOther() throws IOException
    {
        // group-teapot names only the response, so root-errors still decides the request.
        final Resolved resolved = resolve(this.policy(LEVELS, "orders"), exchange("/orders/1", 503, null, null));
        assertThat(resolved).isEqualTo(new Resolved(JournalLevel.HEADERS, JournalLevel.NONE, List.of("group-teapot", "root-errors"), List.of()));
    }

    @Test
    void onResponseOverridesWhatOnRequestResolved() throws IOException
    {
        final Resolved resolved = resolve(this.policy(LEVELS, "orders"), exchange("/orders/debug", 500, null, null));
        assertThat(resolved).isEqualTo(new Resolved(JournalLevel.HEADERS, JournalLevel.FULL, List.of("orders-debug", "root-errors"), List.of()));
    }

    @Test
    void anUnnamedRuleIsLabelledByItsPath() throws IOException
    {
        final JournalPolicy policy = this.policy(route("""
                on_response:
                  - match:
                      - HasResponseHeader:
                          name: X-Debug
                    response: { level: HEADERS }
                """), "r");
        final Resolved resolved = resolve(policy, exchange("/r", 200, "x-debug", "1"));
        assertThat(resolved.rules()).containsExactly("routes.r.journal.on_response[0]");
        assertThat(resolved.response()).isEqualTo(JournalLevel.HEADERS);
    }

    @Test
    void statusAcceptsNumbersAsWellAsStrings() throws IOException
    {
        final JournalPolicy policy = this.policy(route("""
                on_response:
                  - match:
                      - Status:
                          include: [401, "403", 5XX]
                    response: { level: FULL }
                """), "r");
        assertThat(resolve(policy, exchange("/r", 401, null, null)).response()).isEqualTo(JournalLevel.FULL);
        assertThat(resolve(policy, exchange("/r", 403, null, null)).response()).isEqualTo(JournalLevel.FULL);
        assertThat(resolve(policy, exchange("/r", 599, null, null)).response()).isEqualTo(JournalLevel.FULL);
        assertThat(resolve(policy, exchange("/r", 404, null, null)).response()).isEqualTo(JournalLevel.NONE);
    }

    @Test
    void aRuleThatFailsDoesNotFireEvenUnderNotAndIsRecordedAsAnError() throws IOException
    {
        final JournalPolicy policy = this.policy(route("""
                response:
                  level: HEADERS
                on_response:
                  - id: never-under-not
                    match:
                      - not:
                          MatchResponseHeader:
                            name: X-Trace
                            regexp: "^(\\\\w+)*\\\\1$"
                    response: { level: NONE }
                """), "r");
        final Resolved resolved = resolve(policy, exchange("/r", 200, "x-trace", "a".repeat(30) + "!"));
        assertThat(resolved.response()).isEqualTo(JournalLevel.HEADERS);
        assertThat(resolved.rules()).isEmpty();
        assertThat(resolved.errors()).containsExactly("never-under-not");
        assertThat(policy.onResponse().getFirst().errors()).isEqualTo(1);
    }

    /**
     * A rule that fails is often one meant to raise the level, so its failure must show even when
     * the exchange then ends at NONE and leaves no record: it is counted per rule, and logged the
     * first time after each load only, so a failing rule on a hot route cannot flood the log.
     */
    @Test
    void aFailingRuleIsCountedWithNoRecordAndWarnedOncePerLoad() throws IOException
    {
        final String yaml = route("""
                on_response:
                  - id: raise-on-trace
                    match:
                      - MatchResponseHeader:
                          name: X-Trace
                          regexp: "^(\\\\w+)*\\\\1$"
                    response: { level: FULL }
                """);
        final ConditionInput failing = exchange("/r", 200, "x-trace", "a".repeat(30) + "!");
        final Logger logger = (Logger) LoggerFactory.getLogger(JournalRule.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try
        {
            final JournalPolicy policy = this.policy(yaml, "r");
            assertThat(policy.isSilent()).as("the rule can raise the level").isFalse();
            for (int i = 0; i < 3; i++)
            {
                final Resolved resolved = resolve(policy, failing);
                assertThat(resolved.request()).isEqualTo(JournalLevel.NONE);
                assertThat(resolved.response()).as("no record is written").isEqualTo(JournalLevel.NONE);
            }
            assertThat(policy.onResponse().getFirst().errors()).isEqualTo(3);
            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);

            final JournalPolicy reloaded = this.policy(yaml, "r");
            resolve(reloaded, failing);
            assertThat(reloaded.onResponse().getFirst().errors()).as("counted from the load").isEqualTo(1);
            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).as("warned again after the load").hasSize(2);
        }
        finally
        {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aRouteWhoseBaseAndEveryRuleAreNoneIsSilent() throws IOException
    {
        assertThat(this.policy(route("""
                request: { level: NONE }
                on_response:
                  - match:
                      - Status:
                          include: [5xx]
                    response: { level: NONE }
                """), "r").isSilent()).isTrue();
        assertThat(this.policy(route("""
                request: { level: NONE }
                on_request:
                  - match:
                      - Method:
                          include: [POST]
                    request: { level: METADATA }
                """), "r").isSilent()).as("a rule can raise it").isFalse();
        assertThat(this.policy(LEVELS, "orders").isSilent()).isFalse();
    }

    @Test
    void responsePredicatesAreRefusedInOnRequest()
    {
        assertThatThrownBy(() -> this.build(route("""
                on_request:
                  - match:
                      - Status:
                          include: [500]
                    request: { level: HEADERS }
                """)))
                .hasMessageContaining("routes.r.journal.on_request[0]")
                .hasMessageContaining("use it in on_response");
    }

    @Test
    void remoteAddrTestsOnlyTheClientLeg() throws IOException
    {
        assertThatThrownBy(() -> this.build(route("""
                on_response:
                  - match:
                      - RemoteAddr:
                          source: 10.0.0.0/8
                          leg: upstream
                    response: { level: FULL }
                """)))
                .hasMessageContaining("[routes.r.journal.on_response[0].match[0].RemoteAddr.leg] RemoteAddr tests only the client leg.");
        this.build(route("""
                on_response:
                  - match:
                      - RemoteAddr:
                          source: 10.0.0.0/8
                          leg: client
                    response: { level: FULL }
                """));
    }

    @Test
    void aRuleSettingNoLevelIsRefused()
    {
        assertThatThrownBy(() -> this.build(route("""
                on_request:
                  - match:
                      - Method:
                          include: [POST]
                """)))
                .hasMessageContaining("routes.r.journal.on_request[0]")
                .hasMessageContaining("sets no level");
    }

    @Test
    void anEmptyMatchIsRefused()
    {
        assertThatThrownBy(() -> this.build(route("""
                on_request:
                  - match: []
                    request: { level: HEADERS }
                """)))
                .hasMessageContaining("routes.r.journal.on_request[0]");
    }

    @Test
    void aMissingMatchIsRefused()
    {
        assertThatThrownBy(() -> this.build(route("""
                on_request:
                  - request: { level: HEADERS }
                """)))
                .hasMessageContaining("routes.r.journal.on_request[0]")
                .hasMessageContaining("match is required");
    }

    private static final String CAPS = """
            version: rules
            journal:
              request:
                level: METADATA
                truncate_body_at: 64KB
              response:
                level: METADATA
                truncate_body_at: 64KB
              on_request:
                - id: writes
                  match:
                    - not:
                        Method:
                          include: [GET]
                  request: { level: FULL }
            routes:
              - id: uploads
                match:
                  - PathPrefix:
                      prefix: /uploads
                upstream:
                  targets:
                    - url: http://localhost:1
                journal:
                  response:
                    truncate_body_at: 8KB
                  on_request:
                    - id: small-debug
                      match:
                        - Path:
                            path: /uploads/debug
                      request: { truncate_body_at: 1KB }
            """;

    @Test
    void truncateBodyAtIsInheritedPerKeyApartFromTheLevel() throws IOException
    {
        final JournalPolicy policy = this.policy(CAPS, "uploads");
        assertThat(policy.request()).isEqualTo(JournalLevel.METADATA);
        assertThat(policy.requestTruncateAt()).isEqualTo(64 * 1024);
        assertThat(policy.response()).as("the route names only the cap and keeps the inherited level").isEqualTo(JournalLevel.METADATA);
        assertThat(policy.responseTruncateAt()).isEqualTo(8 * 1024);
    }

    @Test
    void aRuleNamingOnlyTheCapLeavesTheLevelToTheNextRule() throws IOException
    {
        final StatefulJournal journal = resolveJournal(this.policy(CAPS, "uploads"), exchange("/uploads/debug", 200, null, null, "POST"));
        assertThat(journal.requestLevel()).as("from the root's rule").isEqualTo(JournalLevel.FULL);
        assertThat(journal.requestTruncateAt()).as("from the route's rule").isEqualTo(1024);
        assertThat(journal.responseTruncateAt()).isEqualTo(8 * 1024);
    }

    @Test
    void aBaseCapIsTheDefaultForAFullThatARuleSets() throws IOException
    {
        final StatefulJournal journal = resolveJournal(this.policy(CAPS, "uploads"), exchange("/uploads/1", 200, null, null, "POST"));
        assertThat(journal.requestLevel()).isEqualTo(JournalLevel.FULL);
        assertThat(journal.requestTruncateAt()).isEqualTo(64 * 1024);
    }

    @Test
    void requestTruncateBodyAtIsRefusedInOnResponse()
    {
        assertThatThrownBy(() -> this.build(route("""
                on_response:
                  - match:
                      - Status:
                          include: [5xx]
                    request: { truncate_body_at: 8KB }
                """)))
                .hasMessageContaining("routes.r.journal.on_response[0].request.truncate_body_at")
                .hasMessageContaining("the request body has already streamed");
    }

    @Test
    void responseTruncateBodyAtIsAllowedInOnResponse() throws IOException
    {
        final JournalPolicy policy = this.policy(route("""
                response: { level: METADATA }
                on_response:
                  - match:
                      - Status:
                          include: [5xx]
                    response: { level: FULL, truncate_body_at: 4KB }
                """), "r");
        final StatefulJournal journal = resolveJournal(policy, exchange("/r", 503, null, null));
        assertThat(journal.responseLevel()).isEqualTo(JournalLevel.FULL);
        assertThat(journal.responseTruncateAt()).isEqualTo(4 * 1024);
    }

    @Test
    void aTruncateBodyAtOfZeroIsRefused()
    {
        assertThatThrownBy(() -> this.build(route("""
                response: { level: FULL, truncate_body_at: 0B }
                """)))
                .hasMessageContaining("truncate_body_at")
                .hasMessageContaining("must be a positive data size");
    }

    @Test
    void fullRequestIsRefusedInOnResponse()
    {
        assertThatThrownBy(() -> this.build(route("""
                on_response:
                  - match:
                      - Status:
                          include: [5xx]
                    request: { level: FULL }
                """)))
                .hasMessageContaining("routes.r.journal.on_response[0].request")
                .hasMessageContaining("already streamed");
    }

    @Test
    void onResponseCannotLowerARequestThatCanBeFull()
    {
        assertThatThrownBy(() -> this.build("""
                version: rules
                journal:
                  on_request:
                    - match:
                        - Method:
                            include: [POST]
                      request: { level: FULL }
                routes:
                  - id: r
                    match:
                      - PathPrefix:
                          prefix: /r
                    upstream:
                      targets:
                        - url: http://localhost:1
                    journal:
                      on_response:
                        - match:
                            - Status:
                                include: [2xx]
                          request: { level: NONE }
                """))
                .hasMessageContaining("routes.r.journal.on_response[0].request")
                .hasMessageContaining("journal.on_request[0] can make it FULL");
    }

    @Test
    void ruleIdsAreUniqueAcrossTheFile()
    {
        assertThatThrownBy(() -> this.build("""
                version: rules
                journal:
                  on_response:
                    - id: errors
                      match:
                        - Status:
                            include: [5xx]
                      response: { level: FULL }
                routes:
                  - id: r
                    match:
                      - PathPrefix:
                          prefix: /r
                    upstream:
                      targets:
                        - url: http://localhost:1
                    journal:
                      on_request:
                        - id: errors
                          match:
                            - Method:
                                include: [POST]
                          request: { level: HEADERS }
                """))
                .hasMessageContaining("routes.r.journal.on_request[0].id")
                .hasMessageContaining("'errors' is already the id of journal.on_response[0]");
    }

    @Test
    void aDirectionNamingNoKeyIsRefused()
    {
        assertThatThrownBy(() -> this.build(route("""
                request: {}
                """)))
                .hasMessageContaining("routes.r.journal.request")
                .hasMessageContaining("names no key");
    }

    @Test
    void statusOverridesIsAnUnknownOption()
    {
        assertThatThrownBy(() -> this.build(route("""
                response:
                  level: METADATA
                  status_overrides:
                    5xx: FULL
                """)))
                .hasMessageContaining("Unknown configuration option: 'status_overrides'");
    }

    @Test
    void statusOverridesUnderUnroutedIsAnUnknownOption()
    {
        assertThatThrownBy(() -> this.build("""
                version: rules
                unrouted:
                  journal:
                    request:
                      level: METADATA
                      status_overrides:
                        404: NONE
                routes:
                  - id: r
                    match:
                      - PathPrefix:
                          prefix: /r
                    upstream:
                      targets:
                        - url: http://localhost:1
                """))
                .hasMessageContaining("Unknown configuration option: 'status_overrides'");
    }
}
