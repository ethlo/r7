# The config model: root, groups, routes, journal rules

**Status:** current.

This is why `routes.yaml` has the shape it has. The user-facing contract, every key and every
error message, is [`docs/config.md`](../docs/config.md) (§1 for levels and inheritance, §7 for
journaling); this document holds the rules behind it and the alternatives that were turned down.
After 1.0 the shape can only be extended, so a change to anything here is a breaking change.

The plan that built it, with the worked Spring Cloud Gateway translation and the per-slice
order, is [`history/config-model.md`](history/config-model.md).

## Principles

1. **Inheritance follows nesting in the file, never a name.** What a route gets is written above
   it: in its group, or at the top of the file. No reserved names, no references to look up.
2. **Add or override, never remove.** An inner level adds filters and rules, and replaces the
   values it names. It cannot disable, remove or reach into what an outer level declared. An
   explicit `null` is refused at load for the same reason: it would read as a removal.
3. **First match wins**, for routes and for journal rules alike.
4. **What a value means does not depend on where it appears.** `filters`, `journal` and
   `upstream` take the same shape at the root, on a group and on a route, and a predicate tests
   the same message wherever it is written.

A change that breaks one of these needs a new decision, not a patch.

## Levels

There are three: the **root** (the file itself), **groups**, and **routes**. The root is a group
without a `match` and without an id. Groups nest one level and hold routes only.

| Key | How an inner level inherits it |
|---|---|
| `match` | ANDed, outer first. A route can only narrow its group. |
| `filters` | Added. Outer filters run before inner ones on the request and after them on the response. |
| `upstream` | Replaced per key (`targets`, `strategy`, `timeouts`, `health_check`, `fallback`, `preserve_host`); a key is replaced whole. |
| `journal` | Each direction key inherited on its own; rules checked innermost first (see "Journal"). |

Groups are flattened at load. The request path sees flat routes, and a group is not a boundary:
a request that matches a group's `match` but none of its routes continues with the entry after
the group, as if the routes were written there.

**A filter is one instance for everything beneath where it is declared.** A `RateLimiter` at the
root is one gateway-wide limit, on a group one limit for the group's routes together. A filter
therefore holds state shared by those exchanges in its fields, and state for one exchange in a
`StateKey` attachment (the filter contract in [`docs/extensibility.md`](../docs/extensibility.md)).
On a fallback, instances whose request phase already ran are not run again but still receive
their later phases, so their state stays balanced; this falls out of sharing by instance.

**Two routes share one upstream** (one pool, one health state) only when every effective upstream
key comes from the same declarations. Pool and health are never shared between routes
configured differently.

A route that needs a different policy from its group is written outside the group, before it.
Nothing is overridden by id, type or name.

## Journal

Each direction key (`request.level`, `request.truncate_body_at`, `response.level`,
`response.truncate_body_at`) is resolved on its own:

1. **Base:** the innermost level that names it, else the default (`NONE`, no cap).
2. **`on_request`:** the first matching rule naming that key, checking the route's rules, then
   the group's, then the root's.
3. **`on_response`:** the same, on top of step 2.

An inner rule overrides an outer one only for a key both name, so an outer rule is never removed
(principle 2). A base level is not a rule: lowering a route's base leaves every inherited rule in
force.

**The decision points are fixed by when bytes stream.** `on_request` runs after routing and
before the request body streams; `on_response` runs once the response head is final, for proxied
and short-circuited responses alike, before its first byte. So `on_response` may not set or
leave the request at `FULL`, nor touch the request cap: the body has already gone. Load refuses
such rules, naming both the rule and the one that can make the request `FULL`. The response
capture is installed at commit, so a `FULL` response is captured from its first byte.

**A rule whose condition throws does not fire**, and the whole condition counts as false, not the
leaf, since `not` would turn a false leaf into a match. The client still gets its response; the
failure is journaled as `gateway.journal.rule_error` and counted per rule even when the exchange
leaves no record. A route `match` that throws still fails closed with `500`: routing on an
undecided condition is the unsafe choice.

**Every record says which rule decided it** (`gateway.journal.rule`, by id or path). What that
rule said is answered by the config that was live: each load logs the `version` and the source
digest of the file. Nothing about the config is written per exchange.

**A route whose base and every reachable rule are `NONE` is silent** and skips the journal
entirely, which `RequestPathCostTest` gates.

## Predicates and legs

A request predicate tests the request as the client sent it, before any filter, in a route's
`match` and in both rule lists, so a `RewritePath` or `SetRequestHeader` never changes what a rule
matches, and adding a `Status` condition to a rule does not change what its `Path` means. It has
no `leg`.

A response predicate tests one leg, chosen by `leg`: `client` (the default), the response as the
client receives it, or `upstream`, the response as the upstream sent it. They differ when r7
answers itself (its own `502`/`504`) or a filter changes the response. On an exchange no upstream
answered, an upstream-leg predicate is false.

The request side had a `leg` too until beta.2: `leg: upstream` tested the request as forwarded.
It was dropped: a rule on the route already pins down its traffic, the journal records the
forwarded request, and it needed a per-predicate opt-out (`RemoteAddr` would test the client's
address on the upstream leg).

Predicates are pure: no side effects, the same answer however often they run. r7 evaluates them
in routing, in both rule lists and under `unrouted`, and skips them when a condition
short-circuits. `Sample` draws once per exchange per decision (routing, journaling), so it keeps
that contract within an exchange. `leg` is handled by the condition builder; no response
predicate implements it.

## Unrouted

`unrouted.journal` covers requests r7 answers itself before or instead of routing. It does not
inherit the root `journal`: refusals are mostly scanner traffic, and levels chosen for real
routes would journal it by accident. It has no `on_response` (the answer is fixed by the reason)
and no `FULL` (the body is never read).

A rule that reads the request is checked only on a route miss, as a whole, not leaf by leaf: a
miss is the one refusal whose request passed every guard routing relies on, and gating leaves
would let `not` turn a skipped leaf into a match, so a rule written to silence misses would also
silence a smuggling attempt. A rule of only `UnroutedReason` and `Sample` is checked on every
refusal.

## Management view

The management API shows each route's effective configuration: its match with the group's ANDed
in, its filters in run order with the level each was declared on, its upstream, and its journal
policy with the rules in the order they are checked. A rule is shown as **may be overridden**
for a key by every rule that takes precedence for that key when it matches: the rules checked
before it in its stage that name the key (an inner level's, or an earlier one at its own level),
and, for an `on_request` rule, every `on_response` rule naming the key, since `on_response`
decides last. The view never
says an outer rule is overridden outright: whether one condition covers another is not decidable
for third-party predicates, and an audit view must not state what it cannot prove. The journal
answers it per exchange.

## Rejected

| Alternative | Why not |
|---|---|
| `default_filters`, a `defaults:` section | "Default" suggests a route can override them, and the root already is the default. |
| `group: default`, `group: unrouted` | Inheritance by name. `unrouted` is not a group: almost none of a group's keys apply to it. |
| Nested groups | One level covers tenant and API-family grouping; deeper nesting stops reading top to bottom. |
| Override by id, type or name (`replaces:`, `inherit_rules: false`), `unless:` on a filter | Removal, and lookups across the file. A filter type can legitimately be stacked, so override by type drops one silently. |
| Locked root rules, or a warning when an inner rule overrides | One file, one author, one review: a lock guards only against that author, and a warning would fire on every legitimate override. The journal records which rule fired, the view shows what may be overridden. |
| `upstream` replaced as a whole | A route needing another timeout would repeat its targets. |
| Status as a predicate in one `when:` list | Mixing request and response rules in one ordered list breaks "first match wins": a request rule captures the body, then a status rule matches first. |
| `on_upstream_response` / `on_client_response` stages | A stage exists where a level must be fixed before bytes stream, and there are two such points. Which message a rule tests is the predicate's `leg`, not the stage. |
| `UpstreamStatus`, `UpstreamResponseHeader`, … | A copy of every response predicate per leg; one `leg` parameter does it. |
| `phase:` instead of `leg:` | A phase in r7 is *when* something runs; a leg is *which message*, the journal's own term. |
| A `Journal` filter | Filters run after the levels are fixed, and journaling would depend on filter order. |
| "Most detailed level wins" | Rules could not lower a level (health checks to `NONE`). |
| An allowlist of predicates allowed on refusals | It cannot cover custom predicates. Gating any request-reading rule to `no_route` does. |
| Scalar levels (`request: FULL`), a `status:` shorthand | Two ways to write the same thing in the docs, the schema and the editor. |
| `max_body_size` for the journal cap | Reads as a condition, and `max_*` elsewhere in r7 refuses traffic. `truncate_body_at` names the action. |
| A startup warning for `FULL` without a cap | It would fire on every route where whole bodies are the point. |
| `id` on filter entries | A label nothing reads. Rule ids pay for themselves in the journal. |
| `not:` taking a list | Ambiguous between "neither" and "not both". `not` takes one condition. |
| Rule reuse through YAML anchors | Jackson binds an alias to a mapping as a string. Reuse is what nesting is for. |
| `{{VAR}}` for environment variables | Unquoted it parses as a flow mapping, and template tools in front of r7 would expand it. Templates use `{name}` instead. |
| The config version on every record | Bytes on every exchange for what deployment history answers. |
