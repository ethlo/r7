# The 1.0 config model: root, groups, routes, journal rules

State: **spec, for review before implementation.** This is the shape of `routes.yaml` for 1.0.
After 1.0 it can only be extended, never changed, so anything that cannot be added later without
breaking a config is settled here.

## Problem

- **Journal policy is tied to routes.** Levels are set per route and refined only by response
  status (`status_overrides`). Journaling `POST /orders` at `FULL` and `GET /orders` at
  `METADATA` means splitting the route, and a policy such as "every non-GET under `/core/`" (a
  real production config, below) cannot be expressed at all when those paths share one route.
- **There is no gateway-wide journal policy.** Every route repeats its `journal:` block, and a
  route that leaves it out journals nothing.
- **Routes repeat themselves.** Routes for the same tenant repeat the same `Host` match, the same
  rewrites, the same upstream and the same timeouts.

## Principles

1. **Inheritance follows nesting in the file, never a name.** What a route gets is written above
   it: in its group, or at the top of the file. No reserved names, no references to look up.
2. **Add or override, never remove.** An inner level adds filters and rules, and replaces the
   values it names. It cannot disable, remove or reach into what an outer level declared.
3. **First match wins**, for routes and for journal rules alike.
4. **What a value means does not depend on where it appears.** `filters`, `journal` and
   `upstream` take the same shape at the root, on a group and on a route.

## The shape

```yaml
filters:                               # root: one instance for the whole gateway
  - SimpleMetrics

journal:                               # root: applies to every route below
  request:  { level: METADATA }
  response: { level: METADATA }
  on_response:
    - match:
        - Status: { include: [5xx] }
      response: { level: FULL }

upstream:                              # root: inherited per key
  timeouts: { connect: 2s, read: 30s }

unrouted:                              # not a route and not a group: see "Unrouted"
  journal:
    request:  { level: HEADERS }
    response: { level: METADATA }

routes:
  - group: tenant-a                    # a group: shared match, filters, upstream, journal
    match:
      - Host: { hosts: [a.example.com] }
    filters:
      - RateLimiter: { capacity: 100, refill_tokens: 10, refill_period: 1s }
    upstream:
      targets: [ { url: "http://tenant-a.internal" } ]
    routes:
      - id: orders
        match:
          - PathPrefix: { prefix: /orders/ }
        journal:
          on_request:
            - match:
                - Method: { include: [POST, PUT] }
              request: { level: FULL }
      - id: reports
        match:
          - PathPrefix: { prefix: /reports/ }
        upstream:
          timeouts: { connect: 2s, read: 120s }

  - id: catch-all                      # a route at the top level, as today
    match:
      - PathPrefix: { prefix: / }
    upstream:
      targets: [ { url: "http://nginx" } ]
```

There are three levels: the **root** (the file itself), **groups**, and **routes**. The root is
a group without a `match` and without an id.

| Key | Root | Group | Route |
|---|---|---|---|
| `version` | yes, as today | | |
| `id` | | `group: <id>` | `id: <id>` |
| `match` | | optional | required (as today) |
| `filters` | yes | yes | yes |
| `upstream` | yes | yes | yes |
| `journal` | yes | yes | yes |
| `routes` | yes | yes, at least one route | |
| `unrouted` | yes | | |

Groups nest one level: a group holds routes, not groups. Deeper nesting stops reading top to
bottom, and one level covers tenant- and API-family grouping (see "Rejected").

An entry of `routes` is a group or a route, and load refuses anything in between, each error
naming the entry:

- exactly one of `group` and `id`;
- a route has no `routes`; a group has at least one route, and no group inside it;
- group ids and route ids are unique across the file, in one namespace: `gateway.route.group`
  and `fallback.route_id` must never be ambiguous.

## Inheritance

| Key | How an inner level inherits it |
|---|---|
| `match` | ANDed, outer first. A route can only narrow its group. |
| `filters` | Added: outer filters run before inner ones on the request, and after them on the response (onion). |
| `upstream` | Replaced **per key**: `targets`, `strategy`, `timeouts`, `health_check`, `fallback` and `preserve_host` are each taken from the innermost level that names them. A key is replaced whole: a route that names `timeouts` gives both `connect` and `read`. |
| `journal` | `request` and `response` replaced per direction; `on_request` and `on_response` rules checked innermost first, each direction on its own (see "Journal"). |

A route needs an upstream after inheritance, or a filter that answers every request it sees
(`StaticContent`, `ReturnResponse`, `TemplateRedirect` without a `source`); the existing runtime
check stays. A `TemplateRedirect` with a `source` is not one: it answers only when its `source`
matches, so a route whose `match` is wider than the
`source` sends the rest to its upstream, and without one they get the existing `500`. An explicit
`upstream: null` (or `null` for any of its keys, `filters` or `journal`) is refused at load:
written under a group, it reads as "no upstream here", but nothing can remove an inherited value
(principle 2). Leaving the key out inherits. Records cannot tell `null` from absent, so the check
runs on the YAML tree before binding:
`[routes.tenant-a.routes.orders.upstream] null cannot remove an inherited upstream; leave the key out to inherit it.`

### Filter instances

**A filter is one instance for everything beneath where it is declared.**

| Declared on | Instances | Example |
|---|---|---|
| the root | one for the gateway | a `RateLimiter` is one gateway-wide limit, as `global_filters` is today |
| a group | one per group | a `RateLimiter` is one limit for the tenant's routes together |
| a route | one per route | as today |

A `RateLimiter` still keys its buckets by client (or `rate_limit_key`), so "one limit for the
group" means one client's requests to any route of the group draw from the same bucket. A route
can declare its own `RateLimiter` too; a request must then pass both. A `CircuitBreaker` on a
group opens for every route of the group.

**Fallback.** A request handed to a fallback route runs the fallback's filters, from every level,
except the instances whose request phase already ran. Those still receive their later phases
(upstream, response, completion), as today. Because sharing is by instance, this falls out of
the existing rule without a special case:

| Filter declared on | Request phase | Later phases |
|---|---|---|
| the root | once, before the first route | once |
| the first route's group, fallback in the same group | once, before the first route | once |
| the first route's group, fallback in another group | once, before the first route | once, so its state stays balanced |
| the fallback's group, when it is another group | for the fallback | for the fallback |
| the first route | ran | carried |
| the fallback route | for the fallback | for the fallback |

The fallback's chain is the first route's, followed by the fallback's own: root, the first
route's group and the first route (those whose request phase ran), then the fallback's group
and the fallback route. Response and completion phases run in reverse, as for any route, so
the fallback's filters see the response first and a `CircuitBreaker` on the first route's
group sees it last. That breaker counts a failure, whatever the fallback answered: it measures
the upstream outcomes of the routes beneath it, and its route had no target available (see
`CircuitBreaker` below), not what the client received. A first route's filter that
only begins at the upstream phase never runs: it shaped the request for an upstream that was
not used.

### Not overriding

Filters, rules and upstreams are never overridden by id, type or name. A route that needs a
different policy from its group sits outside the group, before it:

```yaml
routes:
  - id: bulk-import                    # its own limit; listed first, so it matches first
    match:
      - Host: { hosts: [a.example.com] }
      - PathPrefix: { prefix: /import/ }
    filters:
      - RateLimiter: { capacity: 10, refill_tokens: 1, refill_period: 1s }
    upstream:
      targets: [ { url: "http://tenant-a.internal" } ]
  - group: tenant-a
    ...
```

## Journal

A `journal` block has the same shape at every level (under `unrouted`, without `on_response`:
see "Unrouted"):

| Key | Type | Meaning |
|---|---|---|
| `request` | direction | Base level for the request direction. |
| `response` | direction | Base level for the response direction. |
| `on_request` | list of rules | Checked once the route is chosen, before any route filter runs and before the request body streams. |
| `on_response` | list of rules | Checked once the response head is final, after the response filters and just before it is written to the client. |

A **direction** is an object:

| Key | Default | Meaning |
|---|---|---|
| `level` | `NONE` | `NONE`, `METADATA`, `HEADERS` or `FULL`. |
| `truncate_body_at` | none | At `FULL`, the journal keeps the body's first `truncate_body_at` bytes (a size: `10KB`, `1MB`) and records that it stopped. Not a condition and not a limit on traffic: the body still streams whole to the upstream or the client, and the exchange is journaled whatever its size. Ignored below `FULL`. Without it the journal keeps whole bodies, as today; request bodies stay bounded by `limits.max_entity_size`. |

A key left out of a direction takes no part at that level: `request: { truncate_body_at: 8KB }`
on a route replaces the cap and keeps the inherited `level`. The defaults apply only when no
level names the key. A direction that names no key (`request: {}`) is refused.

Each key of a direction is inherited on its own (see "Evaluation"), so a cap set at the root is
the default for every `FULL` beneath it, including one a rule sets; a level or rule that names
its own `truncate_body_at` replaces it:

```yaml
journal:
  request:  { level: METADATA, truncate_body_at: 64KB }
  response: { level: METADATA, truncate_body_at: 64KB }
  on_request:
    - match:
        - not:
            - Method: { include: [GET] }
      request: { level: FULL }               # captured up to 64KB
```

**A capped body is marked in the file.** `EndExchange` gains `request_body_truncated` and
`response_body_truncated` (booleans, appended to the FlatBuffers table, so existing readers
ignore them). A tailer cannot tell a capped body from an exchange that ended early by comparing
byte counts, and decisions one component makes about bytes another cannot see travel in the
file. The body checksum covers the journaled prefix. The WARC output writes
`WARC-Truncated: length` and the payload digest of the prefix; the JSON output adds
`body_truncated: true` to the leg.

A **rule**:

| Key | Required | Meaning |
|---|---|---|
| `id` | no | A label, unique among all rules in the file. Journaled in place of the rule's position (see "Recording"), shown in the management view. Never referenced from anywhere and never overrides anything. |
| `match` | yes | Predicates, as for a route's `match`: a list is AND, `and`/`or`/`not` nest. An empty `match`, or an empty `and`, `or` or `not` anywhere in it, is refused at load. |
| `request` | at least one of the two | The direction keys to use when the rule matches. |
| `response` | | |

### Evaluation

Each direction key (`request.level`, `request.truncate_body_at`, `response.level`,
`response.truncate_body_at`) is resolved on its own:

1. **Base:** from the innermost level that names it (route, group, root), else its default.
2. **`on_request`:** the first matching rule *that names that key*, checking the route's list,
   then the group's, then the root's, replaces it.
3. **`on_response`:** the same, on top of the result of step 2.

Per stage and key, at most one rule fires. A rule that matches first for every key it names
wins all of them. A rule that names only `request` never hides an outer rule's `response`: an
inner rule overrides an outer one only for a key both name, so an outer rule is never removed
(principle 2). For example:

```yaml
# root
journal:
  on_response:
    - match:
        - Status: { include: [5xx] }
      response: { level: FULL }

# group
journal:
  on_response:
    - match:
        - Method: { include: [POST] }
      request: { level: HEADERS }
```

A `POST` answered with `500` gets `request: HEADERS` from the group's rule and `response: FULL`
from the root's; both paths are recorded in `gateway.journal.rule`.

A base level is not a rule: `request: { level: NONE }` on a route replaces the inherited base
level for that direction, and every inherited rule still applies on top of it. `truncate_body_at`
is an inherited default, not a ceiling: an inner level or a rule replaces it with a larger or a
smaller size. There is no value for "no cap"; leaving it out everywhere keeps whole bodies.

After a fallback, the exchange keeps the levels resolved for the route it first matched, and
`on_response` rules come from that route and its levels, as today.

### What each step may change

The decision points are fixed by when bytes stream, and validation enforces them at startup and
on hot reload.

| Step | Request | Response |
|---|---|---|
| Base, `on_request` | any `level`, any `truncate_body_at` | any |
| `on_response` | `level` `NONE`, `METADATA` or `HEADERS`, never to or from `FULL`; never `truncate_body_at` | any, including `NONE` → `FULL` |

- **Request body:** it streams to the upstream before any response exists. An `on_response` rule
  cannot capture it after the fact, and cannot un-write it.
- **Request headers:** the snapshot exists for the filters anyway, and records below `FULL` are
  written at completion, so an `on_response` rule can still raise or lower them.
- **Response:** decided at commit, before the first body byte, so at `FULL` the journal holds
  the body from its first byte: whole, or its first `truncate_body_at` bytes.

Startup errors, each naming the rule by its path as validation does today:

| Case | Message (draft) |
|---|---|
| `on_response` rule sets `request: FULL` | `[routes.orders.journal.on_response[0].request] FULL cannot be set in on_response: the request body has already streamed. Set it in on_request or as the base level.` |
| `on_response` rule names `request` while the base or any reachable `on_request` rule can make the request `FULL` | `[routes.orders.journal.on_response[0].request] cannot change the request level: on_request[0] can make it FULL, and a request body that was journaled cannot be un-written.` |
| Response predicate in `on_request` or in a route or group `match` | `[routes.orders.journal.on_request[0].match] Status needs the response; use it in on_response.` |
| Rule without `match` | `[routes.orders.journal.on_request[0]] match is required` |
| Rule naming neither direction | `[routes.orders.journal.on_request[0]] sets no level; name request, response or both` |
| `on_response` rule names `request.truncate_body_at` | `[journal.on_response[0].request.truncate_body_at] the request body has already streamed; set it in on_request or as the base.` |
| Two rules with the same `id` | `[routes.orders.journal.on_request[1].id] 'event-bodies' is already the id of journal.on_request[0]` |
| `leg: upstream` outside `on_response` | `[routes.orders.match[0].Host.leg] the upstream leg does not exist yet here; leg: upstream is allowed only in on_response.` |
| `leg` on `Sample` | `[journal.on_request[0].match[0].Sample.leg] Sample draws once per exchange and has no leg.` |
| `FULL` anywhere under `unrouted` | as today |

### Predicates

All request predicates work in both lists, and in both they see **the request as the client sent
it**: the immutable snapshot taken before any filter runs (`ex.clientRequest`, which the journal
already records). A `RewritePath` or `SetRequestHeader` therefore never changes what a rule
matches, and adding a `Status` condition to a rule does not change what its `Path` means.

**Every predicate tests one leg, chosen by `leg`**, a parameter every predicate takes, built-in or
third-party:

| `leg` | Request predicates see | Response predicates see |
|---|---|---|
| `client` (default) | the request as the client sent it, before any filter | the response as the client receives it, after response filters |
| `upstream` | the request as forwarded, after the upstream-phase filters | the response as the upstream sent it, before response filters |

```yaml
on_response:
  - id: masked-upstream-failure          # the upstream failed, a filter answered 200
    match:
      - Status: { include: [5xx], leg: upstream }
      - Status: { include: [2xx] }
    response: { level: FULL }
```

- The default is `client` wherever a predicate appears, so a predicate never changes meaning
  with its position. A filter that turns an upstream `503` into a `200` makes `Status` see `200`.
- `leg: upstream` is allowed only in `on_response`: in a route's `match` and in `on_request` the
  upstream leg does not exist yet.
- On an exchange that was not proxied (short-circuited, static, refused) an upstream-leg
  predicate is false, and `not` inverts it as any other false.
- **The upstream request and the upstream response exist independently.** The upstream request
  exists once r7 hands it to the upstream, after the upstream-phase filters, whether or not its
  bytes left (a refused connect included); that is the point the journal records it today
  (`proxyStartNanos`). The upstream response exists only when an upstream sent a response head.
  There is at most one per exchange: a fallback is chosen before any upstream call (when the
  route has no available target), never after a response, and a retry happens only on a
  transport failure before a response head arrived.

  | Exchange | Upstream request predicates | Upstream response predicates |
  |---|---|---|
  | Answered by a filter, a fallback-less route with no target, or refused | false | false |
  | Handed to the upstream, connect refused | the forwarded request | false |
  | Request sent, read timeout or protocol error before a valid response head | the forwarded request | false |
  | Head received, then the body times out or breaks framing | the forwarded request | the upstream's response (status and headers) |
  | Response head received | the forwarded request | the upstream's response |

  Without a head, r7's own `502`/`504` is the client leg only, and the journal records no
  upstream response. Today `snapshotResponse()` copies r7's own error into the upstream leg on a
  proxy failure; that is fixed with this change.
- `leg` is taken out by the condition builder before a predicate's own parameters bind. No
  predicate implements it, no factory sees it, and `leg` is a reserved parameter name.

One predicate is new for both lists:

| Predicate | Parameters | Matches |
|---|---|---|
| `Sample` | `rate`: a fraction, `0 < rate <= 1` | A share of exchanges. Each exchange draws one uniform number the first time a `Sample` is evaluated, and keeps it, from a random source, not from a value a client can choose (such as a request ID); `Sample` matches when the draw is below `rate`. One draw serves every `Sample` in the exchange, so `on_request` and `on_response` agree, and `rate: 0.01` selects a subset of `rate: 0.1`. Allowed everywhere, a route's `match` included (a canary). It has no `leg`. |

New in 1.0, allowed only in `on_response`:

| Predicate | Parameters | Matches |
|---|---|---|
| `Status` | `include`: list of codes (`404`) and classes (`5xx`, case-insensitive) | The leg's status: by default the one the client receives. |
| `ResponseHeader` | `name`, `value` | Exact value, as `RequestHeader`. |
| `HasResponseHeader` | `name` | Presence, as `HasRequestHeader`. |
| `MatchResponseHeader` | `name`, `regexp` | Regex, as `MatchRequestHeader`, under the same regex budget. |

Codes are a YAML list (`include: [401, 403, 5xx]`); the comma form of the `status_overrides`
keys goes away with them. Header predicates see the client response headers after response
filters, so a header a filter sets counts.

**API.** Two public predicate contracts, one per message, each seeing only that message:
`GatewayPredicate.test(GatewayRequest)`, unchanged, and a sibling
`GatewayResponsePredicate.test(GatewayResponse)` in `r7-api`, with its own factory SPI in
`r7-core`, registered by `@AutoService`. Neither knows about legs, exchanges or stages: `leg` in
the config decides which request or response the predicate is handed. Both are frozen at 1.0.

The condition tree is r7's own, not built from these interfaces: `and`, `or`, `not` and `Sample`
are internal nodes evaluated over the exchange, and each leaf is wrapped by the builder in an
adapter that fetches its leg's message from the exchange and passes it in. That is what lets
one rule test the upstream response in one condition and the client response in the next, and
lets every `Sample` read the exchange's one draw. Leaves and adapters are built at load; the
exchange already holds the messages and the draw, so evaluating allocates nothing. A route's
`match` is such a tree too, so routing takes the exchange rather than a bare `GatewayRequest`,
and `GatewayRoute` no longer exposes its match as a `GatewayPredicate`.

**The predicate contract**, stated in `docs/extensibility.md` and on both interfaces: a predicate
is a function of what it is given. It has no side effects and gives the same answer for the same
request (and response) however often, in whatever order, and whether or not it runs at all; r7
evaluates predicates in routing, in rules at both stages and under `unrouted`, and skips them
when a condition short-circuits. `Sample` is the one built-in that looks random, and it reads a
draw fixed once per exchange, so it keeps the contract within an exchange.

**A rule whose condition throws does not fire.** A journal rule is evaluated after routing, and
at `on_response` after the response is decided, so a failure there must not cost the client its
response. When any predicate in a rule's condition throws (a regex over its budget, or a
custom predicate's bug), the whole condition counts as false, not the leaf: under `not`, a
false leaf would turn the rule on. Later rules still run, the exchange is journaled at what the
remaining rules and the base resolve to, and the record carries
`gateway.journal.rule_error` with the rule's id or path, so an audit can tell a rule that did
not match from one that could not be evaluated. A `match` that throws still fails closed with
`500`, as today: routing a request whose condition could not be decided is the unsafe choice.

### Recording which rule fired

A rule *fires* when it decides at least one key: it matched, and it was the first matching rule
naming that key in its stage. A rule that matched but was shadowed for every key it names by an
inner rule did not fire and is not recorded. Each rule that fired is journaled in the
multi-valued attribute `gateway.journal.rule`: its `id` when it has one, else its path in the
form validation errors use. An id stays the same when rules are added or reordered; a path does
not. With keys resolved separately, several rules can fire for one exchange:

```
gateway.journal.rule = masked-upstream-failure
gateway.journal.rule = journal.on_response[0]
```

Every record then answers "which rule decided this?". What that rule said at the time is
answered by the config that was live: on every load and hot reload r7 logs the root `version`
and a SHA-256 digest of the file as read, the **source digest** (before `${VAR}` interpolation,
so no secret enters it), and the management view shows both for the live config. The source
digest identifies the file, not the effective config: the same file under a changed environment
variable has the same digest, and the deployment's own revision of its environment covers that.
The journal is unchanged: no version or digest on each exchange. A route in a group is also
journaled with `gateway.route.group`.

## Unrouted

`unrouted` covers requests refused before or instead of routing: an ambiguous path, a bad
`Transfer-Encoding`, `TRACE`, a pattern that exhausted its regex budget, and a route miss. It is a
top-level section, not a group:

- Its only key is `journal`. A group's `match`, `filters`, `upstream` and `routes` mean nothing
  for a request that was refused before routing, so the schema does not offer them.
- Root `filters` do not run on a refused request, as `global_filters` do not today: a
  refusal is answered before any filter (`GatewayPipeline.handle`), so a root `SimpleMetrics`
  does not count it. Route misses are counted on their own (`unroutedRequests`, shown on the
  dashboard). Whatever the reason, the refusal's record is its journal entry under `unrouted`.
- Its `journal` has base levels, `request` and `response`, for every refusal, and `on_request`
  rules, checked on every refusal. The reason is journaled in `gateway.unrouted.reason`, as
  today.
- One predicate exists only here: `UnroutedReason: { include: [...] }`, matching the refusal's
  reason: `no_route` (404), `trace` (501), `ambiguous_path` (400), `transfer_encoding` (400),
  `regex_budget` (500). It is an internal node, as `Sample` is, and has no `leg`. Anywhere
  else it is refused: `[routes.api.match[0].UnroutedReason] UnroutedReason applies only under unrouted.`
- Every other predicate reads the request, and a rule that contains one, anywhere in its
  `match`, is **checked only on `no_route`**; on the other four refusals it is skipped, as if
  it did not match. A rule made only of `UnroutedReason` and `Sample` is checked on every
  refusal. The whole rule is gated, not each leaf: a leaf that is merely false would let `not`
  turn it true, so `not: { Host: ... }`, written for misses, would also set `NONE` on a
  smuggling attempt and hide it. A
  miss is the one refusal where the request passed every guard routing relies on (an
  unambiguous path, valid framing, an allowed method), so a predicate sees a request in the
  same state a route's `match` does. The other four happen before r7 interprets the request
  (for `ambiguous_path` it has no path it trusts; for `regex_budget` routing itself was cut
  off). The builder decides this from the tree at load, so custom predicates need no
  classification. On a miss a rule may run predicates routing never reached (routing
  short-circuits, and a group that does not match skips its routes); that is safe because of
  the predicate contract (see "API").
- `on_response` is refused under `unrouted`: the answer is r7's own and fixed by the reason
  (`404` for a miss), so there is nothing left to decide.
  `[unrouted.journal.on_response] r7's answer to a refused request is fixed by its reason; use on_request.`

```yaml
unrouted:
  journal:
    request:  { level: HEADERS }         # every refusal
    response: { level: METADATA }
    on_request:
      - id: api-misses                   # first match wins: shields these from the next rule
        match:                           # reads the request: checked on no_route only
          - Host: { hosts: [api.example.com] }
        request:  { level: HEADERS }
        response: { level: METADATA }
      - id: skip-other-misses
        match:
          - UnroutedReason: { include: [no_route] }
        request:  { level: NONE }
        response: { level: NONE }
```

A `404` on `api.example.com` journals its request headers, so a client calling a removed route
can be found; other misses, mostly scanners, are not journaled; `TRACE`, `/%2e%2e/` and
smuggling attempts are journaled at `HEADERS`.
- It does **not** inherit the root `journal`. Refusals are mostly scanner traffic, and levels
  chosen for real routes would journal it by accident. The docs say so in one sentence, and the
  management view shows it.
- `FULL` stays refused: a refused request's body is never read.

## Management view

The management API and `r7-editor` show each route's effective configuration: its match with the
group's ANDed in, its filters in the order they run and the level each was declared on, its
resolved upstream, and its journal policy (base levels, then its rules, its group's and the
root's, in the order they are checked). The dashboard says "root" or "gateway-wide" where it says
"global" today.

## Implementation notes

- **Groups** are flattened at config load: each route gets its effective `match` (a group
  predicate ANDed with its own, evaluated once per group so a non-matching group skips its
  routes), its filter chain built from shared instances, its upstream, and its journal policy.
  The request path keeps working on flat `DefaultGatewayRoute`s.
- **Shared upstreams.** Today each route builds its own `HttpUpstream`
  (`BlockingGateway`), holding the targets, the pool, the health state and the timeouts
  together: a pooled socket gets its read timeout when it is opened. Two routes share one
  `HttpUpstream`, and so one pool and one health state, when they inherit every effective
  upstream key from the same declarations as each other. Two routes of a group that take
  `timeouts` from the root and `targets` from the group share; a third that names its own
  `timeouts` gets an upstream of its own, as every route does today. Pool size and health are
  then never shared between routes configured differently. Sharing is decided within one
  loaded configuration: `${VAR}` is resolved per scalar at load, so routes inheriting from one
  declaration see one value. A reload builds new upstreams for the new generation, as today,
  and retiring the old generation stops each shared upstream once. Sharing a pool across timeouts
  (setting the read timeout per use) is an optimisation for later, with no change to the config.
- **Journal decision points** already exist in `GatewayPipeline`:
  - `open()`, after routing and before filters: resolve base and `on_request`; journal the
    request start line and install the request-body capture only for `FULL`, as today.
  - `onResponseCommit()`, after the response filters and before the head is written
    (`BlockingServerExchange.commit()`): evaluate `on_response`, fix the final levels, install the
    response-body capture if the response is `FULL`. Every body byte passes the capture after this
    point: proxied chunks through `onResponseBody`, r7's own answers through `sendBody`, which
    commits first. The response capture is installed here in every case, no longer at `open()`.
  - **Short circuits do not reach `onResponseCommit()` today.** `shortCircuit()` runs the
    response filters itself and registers only completion, and `commit()` calls the pipeline only
    when a commit was requested. Response finalisation becomes one path for both: run the
    response filters once, evaluate `on_response`, install the capture, write the head. Without
    that, a `ReturnResponse`, an auth refusal, a redirect or static content would skip
    `on_response`, and the same final response would journal differently by where it came from.
  - `completeJournal()`: write the upstream legs and the client response at the **resolved**
    levels. Today it uses the base levels, which a raised exchange would get wrong.
  - `StatefulJournal` resolves at completion against the final levels instead of
    `JournalDirectionConfig.resolve(status)`.
  - WebSocket upgrades: commit runs before the 101 goes out, so the handshake's levels are final;
    tunnelled frames are not journaled, as today.
- **Cost.** At config load each route is marked *silent* when its base levels and every rule
  result it can reach are `NONE`. A silent route skips the journal entirely: no
  `StatefulJournal`, no start lines, nothing at completion. That is cheaper than today, where
  `completeJournal` builds start lines even for a route that journals nothing. A route that is not
  silent pays, for an exchange that ends at `NONE`, its predicate evaluations and the request
  start line (which could be built at completion from the immutable snapshot instead). No body is
  ever captured speculatively. `RequestPathCostTest` gates the result.

## Migration

Beta allows breaking changes. Each removed key fails at startup through
`FAIL_ON_UNKNOWN_PROPERTIES`, with a message that names the replacement instead of only
"unknown property".

| Before | After |
|---|---|
| `global_filters:` | `filters:` at the root. Same semantics: one instance for the gateway. |
| `status_overrides` | `on_response` rules with `Status` (below). |
| a `journal:` repeated on every route | `journal:` at the root, or on a group. |

```yaml
# before
journal:
  response:
    level: METADATA
    status_overrides:
      5xx: HEADERS
      401,403: HEADERS

# after
journal:
  response: { level: METADATA }
  on_response:
    - match:
        - Status: { include: [5xx, 401, 403] }
      response: { level: HEADERS }
```

Overrides of both directions under the same key become one rule naming both. The old rule that a
response override may only be `FULL` if the base is `FULL` goes away; the request restrictions
carry over as the `on_response` checks above.

## Worked example: a Spring Cloud Gateway config

A production config, abridged. It logged bodies by path and method independently of its routes
(`http-logging.matchers`, `Path=...` + `NotMethod=GET` → body `STORE`), and repeated host
matches, credentials and rewrites across tenant routes.

```yaml
filters:
  - SimpleMetrics

journal:
  request:  { level: METADATA }
  response: { level: METADATA }
  on_request:
    - match:
        - not:
            - Method: { include: [GET] }
        - or:
            - Path:       { path: /core/events/capture }
            - PathPrefix: { prefix: /core/masterdata/ }
            - PathPrefix: { prefix: /core/batches/ }
            - Path:       { path: /core/track/v1/capture }
            - Path:       { path: /core/gateway/production-api/v1/bundles }
            - PathPrefix: { prefix: /core/gateway/events/v2/ }
      request:  { level: FULL }
      response: { level: FULL }

upstream:
  timeouts: { connect: 2s, read: 30s }

routes:
  - group: tenant-a
    match:
      - Host: { hosts: ["${TENANT_A_HOST_LIST}"] }
    routes:
      - id: codes
        match:
          - PathPrefix: { prefix: /api/ }
        filters:
          - InjectBasicAuth: { username: "${TENANT_A_USERNAME}", password: "${TENANT_A_PASSWORD}" }
          - SetRequestHeader: { name: x-realm, value: "${TENANT_A_REALM}" }
          - RewritePath: { regexp: "/api/item-info/(.*)", replacement: "/core/items/v1/info/$1" }
          - RewritePath: { regexp: "/api/validate", replacement: "/core/verify/v2/proxy" }
        upstream:
          targets: [ { url: "${TENANT_A_UPSTREAM_URI}" } ]
      - id: shortlinks                   # the route's match equals the redirect's source,
        match:                         # so every request it takes is redirected
          - MatchPath: { regexp: "/01/(.*)/21/(.*)" }
        filters:
          - TemplateRedirect: { source: "/01/(.*)/21/(.*)", target: "/?uid=$2" }

  - group: presentation
    match:
      - PathPrefix: { prefix: /api/ }
    filters:
      - RewritePath: { regexp: "/api/product-presentation/(.*)/(.*)", replacement: "/api/presentation/$1/product/$2" }
      - RewritePath: { regexp: "/api/batch-presentation/(.*)/(.*)", replacement: "/api/presentation/$1/batch/$2" }
      - RewritePath: { regexp: "/api/item-presentation/(.*)/(.*)", replacement: "/api/presentation/$1/item/$2" }
    upstream:
      targets: [ { url: "${PRESENTATION_URI}" } ]
    routes:
      - id: customer-b
        match:
          - Host: { hosts: ["${CUSTOMER_B_HOST_LIST}"] }
        filters:
          - InjectBasicAuth: { username: "${CUSTOMER_B_USERNAME}", password: "${CUSTOMER_B_PASSWORD}" }
          - SetRequestHeader: { name: x-realm, value: "${CUSTOMER_B_REALM}" }
      - id: customer-c
        match:
          - Host: { hosts: ["${CUSTOMER_C_HOST_LIST}"] }
        filters:
          - InjectBasicAuth: { username: "${CUSTOMER_C_USERNAME}", password: "${CUSTOMER_C_PASSWORD}" }
          - SetRequestHeader: { name: x-realm, value: "${CUSTOMER_C_REALM}" }

  - id: all                            # last, so no NotPath for the static routes is needed
    match:
      - PathPrefix: { prefix: / }
    upstream:
      targets: [ { url: "http://nginx" } ]
```

The body policy sits in one place, separate from routing, as it did in the original; with
per-route journaling only, it could not be expressed, because most of those paths go through the
`all` route. The gaps this example exposed are listed under "Filter and upstream changes in 1.0".

## Filter and upstream changes in 1.0

Found by translating the config above. Each changes the config surface 1.0 freezes, so each
lands before it, with its own docs and tests.

| Change | Shape | Existing configs |
|---|---|---|
| `RewritePath` can set the query | A literal `?` in `replacement` starts the query. Captures after it are encoded as a single query value, as `TemplateRedirect` does, so a captured value cannot add a parameter; the client's own query is passed on as it came, its parameters included. The two are merged: a name the rewrite sets replaces every occurrence the client sent, so `?uid=123&uid=456` rewritten to `?uid={query.refid}` reaches the upstream as one `uid`, and every other parameter keeps its occurrences and order. | **Changes meaning:** today a `?` in `replacement` is sent as `%3F` inside the path. r7 cannot tell which was meant, so the docs and migration notes say so. |
| Template placeholders in braces | In `RewritePath` `replacement` and `TemplateRedirect` `target`, a named group is `{name}` and a query value `{query.refid}`; `$1`, `$2` stay, and `\{` is a literal brace in the string YAML hands r7 (plain or single-quoted `replacement: /a\{b`; double-quoted, YAML needs its own escape: `"/a\\{b"`). `${...}` is then only ever environment interpolation, and no template needs `$$`; the `$${...}` escape stays for any other value that needs a literal `${` (`docs/config.md` §1 loses its template example). Raw braces are not allowed in a URI (RFC 3986), so a placeholder cannot clash with a literal one in practice; Spring Cloud Gateway users know `{segment}` from its path templates. `{{ }}` for environment variables was rejected instead (see Rejected). | **Changes syntax:** `$${name}` is refused at load: `[routes.x.filters[0].TemplateRedirect.target] Write a named group as {name}, not $${name}.` A `{` in a replacement today is a literal one and becomes a placeholder; one naming no group is refused at load. |
| Several `RewritePath`s documented | Each tests the path the client sent (`RewritePathFactory:74`), never another rewrite's output, so rewrites cannot cascade; when several match, the last one declared wins, and a route's rewrite therefore wins over its group's. Unchanged behaviour, written down. No `rules:` list: one would only restate this. | Unchanged. |
| Query values in `TemplateRedirect` | `{query.refid}`, encoded as a query value like path captures are; a repeated parameter is refused with `400` unless every occurrence agrees, and so is a missing one: there is no value to redirect with. | Additive. |
| `TemplateRedirect` `status` limited to redirects | `301`, `302`, `303`, `307` or `308`; anything else is refused at load. Today any code is accepted, and `status: 200` sends a `Location` no client follows. | **Refuses** a config that loads today; none can have worked as a redirect. |
| `TemplateRedirect` `source` optional | Left out, the filter answers every request it sees, with no captures (`target: "/?uid=urn:example:{query.refid}"`). It then always answers, so a route with no upstream may rely on it, as on `StaticContent` and `ReturnResponse`. A `{name}` placeholder without `source` is refused at load (`[routes.x.filters[0].TemplateRedirect.target] {name} needs a source pattern with that named group.`); `{query.*}` placeholders stay valid. | Additive. |
| `Host` entries split on commas | `hosts: ["${HOST_LIST}"]` works for a comma-separated variable. Only `Host` splits; interpolation keeps producing one string. | Additive: a host with a comma never matched. |
| `upstream.preserve_host` | Sends the client's `Host` instead of the target's (`Http1.java:76`); TLS SNI stays the target's. An upstream key, so it inherits per key. | Additive. |
| `CircuitBreaker` measures upstream outcomes | It measures the upstream outcomes of the routes beneath its declaration, which may be several upstreams (a group whose routes name their own `targets`, or the root); it is not tied to one physical upstream. Decided at completion, once per exchange: **failure** for an upstream `5xx`; no valid upstream response head (r7's own `502`/`504`); a head followed by a body that timed out or broke framing; or a route beneath it handing the request to a fallback. **Success** for an upstream response under `500` whose body completed (a client that disconnects is not the upstream's fault, and counts as success). **Neither** for a response a filter answered (`ReturnResponse`, a redirect, a `401`, a `429`): no upstream was asked. When one instance covers both the first route and its fallback (a root breaker, or one group holding both), the fallback event takes precedence: one failure, and the fallback's response is not also counted. A breaker declared only on the fallback's own levels counts the fallback's upstream as usual. The pipeline tells a carried filter that its route fell back, so the breaker does not read the `gateway.fallback.id` attribute, which is telemetry. Today it counts the status the client receives at the head, so a `5xx` from `ReturnResponse` opens it, a response filter that turns an upstream `503` into a `200` hides the failure, a fallback's `200` counts as a success, and a broken body counts as a success. | **Changes meaning** for those four cases; the docs and migration notes say so. |

`StaticContentFallback` `mounts:` (one route for many sub-path → directory mappings) belongs to
a third-party plugin, not to r7.

Skipped on purpose: a general `Retry` filter (bodies stream, so only bodiless requests could be
retried, and a failed connect already moves to the next target) and journaling identities from
tokens that were not verified (a forgeable username has no place in an audit record).

## Rejected

| Alternative | Why not |
|---|---|
| `default_filters` instead of `global_filters` | "Default" suggests a route can override them. With nesting, `filters` at the root says it all. |
| A `defaults:` section | A second concept for what the root already is. |
| `group: default` and `group: unrouted` | Inheritance by name. `unrouted` is not a group of routes: almost none of a group's keys apply to it. |
| Status as a predicate in one `when:` list | Request and response rules in one ordered list make "first match wins" false: a request rule captures the body, then a status rule matches first. |
| `on_upstream_response` / `on_client_response` stages | A stage exists where a level must be fixed before bytes stream; there are two such points. The upstream head is snapshotted at the client commit, nothing streams in between, and one `response` level covers both legs (the upstream leg carries no body). A short-circuited exchange has no upstream response, so such a rule would silently never fire. Which response a rule tests is said by the predicate, not the stage: unqualified predicates are the client's leg, `leg: upstream` the upstream's, and one rule can combine both. |
| Scalar level (`request: FULL`) and a `status:` shorthand on rules | Sugar: two ways to write the same thing, in the docs, the schema and the editor. |
| `max_body_size` for the journal cap | Next to `level` it reads as a condition ("only bodies up to"), and `max_size`/`max_entity_size` elsewhere in r7 refuse traffic. `truncate_body_at` names the action, in the same word as the record's flag and `WARC-Truncated`. |
| `{{VAR}}` for environment variables | A plain YAML value starting with `{` is a flow mapping, so `url: {{GEO_URI}}` would not parse unquoted; Helm, Jinja, Ansible and Go templates use `{{ }}`, so a preprocessing step would expand r7's variables; `${VAR:default}` is what Spring, Spring Cloud Gateway and Docker Compose users already write. The clash with named groups is solved on the template side instead. |
| A startup warning for `FULL` without `truncate_body_at` | r7 cannot tell from the config whether a route serves large bodies, so it would warn on every route where whole bodies are the point, and a warning that always fires is ignored. The risk is stated once, in `docs/journaling.md` next to `truncate_body_at`. |
| `id` on filter entries | A filter entry is `- Type: { … }`; an id needs a second key on the entry or a reserved parameter, for a label nothing reads. Rule ids pay for themselves in the journal; filter ids would not. |
| `unless:` on a filter entry | An exception to an inherited filter is a removal by condition, and it puts a second predicate list on every filter. A route that needs another policy sits outside its group. |
| Nested groups | One level covers tenant and API-family grouping; deeper nesting stops reading top to bottom. |
| An allowlist of predicates permitted on refused requests | An allowlist cannot cover custom predicates: r7 cannot know whether one reads the path or runs a regex. Instead a rule containing any request predicate is checked only on `no_route`, and `UnroutedReason` tells refusals apart. |
| `UpstreamStatus`, `UpstreamResponseHeader`, … | A copy of every predicate type and factory per leg. One optional `leg` parameter on the same predicate does the same. |
| `phase:` instead of `leg:` | In r7 a phase is *when* something runs: the lifecycle phases in `docs/config.md`, phase-aware filters, and the `on_request`/`on_response` stages themselves; `phase: upstream` inside `on_response` reads as running at another time. A leg is *which message*, the term the journal already uses for its four records (`client_request`, `upstream_request`, `upstream_response`, `client_response`, `docs/journaling.md`). |
| A `Journal` filter | Filters run after the levels are fixed, and journaling would depend on filter order. |
| "Most detailed level wins" | Rules could not lower a level (health checks to `NONE`). |
| Override by id, type or name (`replaces:`, `inherit_rules: false`, `journal: NONE` meaning "ignore outer rules") | Removal, and lookups across the file; it no longer reads top to bottom. A filter type can legitimately be stacked, so override by type drops one silently. |
| `upstream` replaced as a whole | A route that only needs another timeout would repeat its targets, and the root could not set timeouts for everyone. |
| Rule reuse through YAML anchors | Jackson 3.2.3's `readTree` turns an alias to a mapping into the anchor's name as a string, so a reused rule fails to bind. Reuse is what nesting is for; templating belongs to Helm or Kustomize. |
| Journaling the config version with every exchange | Bytes on every record for what the deployment history already answers. |
| Changing the built-in level from `NONE` | Kept; the root `journal` sets a gateway-wide base explicitly. |

## Implementation order

One branch and pull request per slice, each green on `./mvnw clean install` with its docs,
`docs/schemas/latest.yaml` and tests in the same change. A slice lists the slices it builds on.

1. **Independent filter and upstream fixes**, in any order, one pull request each:
   - the upstream response leg holds no r7-made `502`/`504` (the `snapshotResponse()` fix);
   - `TemplateRedirect`: `status` limited to redirects, `source` optional, `{query.*}` values;
   - `Host` entries split on commas;
   - `upstream.preserve_host`;
   - `CircuitBreaker` measures upstream outcomes (needs the fallback signal to carried
     filters, which this slice adds).
2. **Template placeholders in braces**, then **`RewritePath` can set the query** (builds on the
   brace syntax).
3. **Root, group and route levels**: `routes` holding groups, per-key `upstream` inheritance,
   filter instances per declaration level, the fallback chain, `version`, the source digest,
   `global_filters` refused with its migration message.
4. **Shared upstreams** (builds on 3).
5. **Predicate API**: `GatewayResponsePredicate`, `leg`, the internal `and`/`or`/`not`/`Sample`
   nodes, routing over the exchange (builds on 3).
6. **Journal rules**: root/group/route `journal`, `on_request`/`on_response`, per-direction
   resolution, `truncate_body_at` and the truncation flags, rule recording, silent routes
   (builds on 3 and 5).
7. **`unrouted`** with `UnroutedReason` (builds on 6).

## When this ships

- `docs/config.md`: §2 states the instance rule; §3 the order with group filters; a new section
  for groups and inheritance; §7 rewritten around `on_request`, `on_response` and the root
  `journal`; §8 and every snippet migrated (`DocsConfigSnippetsTest`).
- `docs/journaling.md`: `gateway.journal.rule`, `gateway.route.group`, `truncate_body_at` and the
  truncation flags. Next to `truncate_body_at`, the reason to set it: request bodies are bounded
  by `limits.max_entity_size`, response bodies by nothing, so at `FULL` a 2 GB download writes
  2 GB to the journal, and a tailer holds every fragment of an in-flight exchange in its heap
  until the exchange ends (`r7-journal-mmap/README.md` §11.1). `journal.fbs` gains the two `EndExchange` booleans (`FORMAT.md` unchanged:
  the payload schema grows, the framing does not); `docs/warc.md` gains the
  `WARC-Truncated: length` row.
- `docs/extensibility.md`: `GatewayResponsePredicate` and its factory, `leg`, and the predicate
  contract (no side effects, same answer however often it runs); the filter contract: one
  instance serves every concurrent exchange beneath its declaration, so state shared by those
  exchanges (rate-limit buckets, breaker state) is the instance's, and state for one exchange
  goes in a `StateKey` attachment, never an instance field.
- `docs/schemas/latest.yaml` regenerated (`JsonSchemaGenerator`); `r7-editor` and the
  management view updated.
- `global_filters` replaced in the benchmark, docker and test configs.
- Tests: inheritance per key; explicit `null` refused; filter instances shared per level;
  fallback across and within groups, with phase order across both groups; journal evaluation order across the three levels, with
  route, group and root rules matching at once for different directions; rule predicates
  matching the client's request after `RewritePath` and header filters changed it; upstreams
  shared only when every key is; `unrouted` rules with a request predicate checked only on `no_route` (including under `not`), rules of only `UnroutedReason`/`Sample` on every refusal, `UnroutedReason` refused outside `unrouted`, `on_response` refused; empty `match: []` never matching and nested empty conditions refused; `leg` on built-in and third-party
  predicates, false on unproxied exchanges; upstream request predicates matching and response predicates false after a refused connect and after a read timeout (no upstream response journaled); root filters not running on any refusal; a rule whose `MatchResponseHeader` exceeds its budget not firing, under `not` too, with the response delivered and `gateway.journal.rule_error` set; upstream response predicates matching the head when the body then times out; a group `CircuitBreaker`, and a root one covering both routes, counting exactly one failure when its route falls back to a route answering `200`; a breaker counting a `200` whose body times out as a failure; a breaker counting neither for a `ReturnResponse` `503` nor success for an upstream `503` a response filter turned into `200`; the mixed-leg rule through a status-changing filter; one `Sample` draw per exchange; `truncate_body_at`
  inherited into rules, the truncation flag in the record and in both tailer outputs, unset for a body exactly at the cap and set one byte over; rule ids
  journaled in place of paths; each validation error; the response
  capture raised at commit for proxied and short-circuited responses; upstream legs at resolved
  levels; silent routes skipping the journal; migration messages for removed keys.
- This plan moves to `design/history/`, and its durable parts into a design doc.
