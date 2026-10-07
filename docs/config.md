# r7 Config Reference

## 1. Core Architecture & Semantics

The r7 gateway is configured using a declarative YAML file called `routes.yaml`. This configuration defines how incoming requests are matched, mutated, routed to upstream targets, and logged.

### Configuration Validation

The r7 configuration engine is strictly validated at startup. The gateway will **fail-fast and refuse to start** if it detects:

* Unknown filters, predicates, or configuration keys.
* Invalid regular expressions or malformed CIDR blocks.
* Cyclic/recursive `fallback` routing loops.
* Unresolvable environment variables without default values.
* Duplicate Route IDs (Route IDs must be globally unique).
* Malformed journal `status_overrides` keys (see [Status Overrides](#status-overrides-status_overrides)).

### Environment Variable Interpolation

Configuration values support environment variable injection using the `${VAR_NAME:default_value}` syntax.

* The file is parsed as YAML first, then each individual scalar value is interpolated - not the
  raw file text. An environment value can therefore never add, remove, or restructure YAML nodes
  (e.g. by containing a colon or a newline); it can only become part of the text of the value it
  was substituted into.
* Values are injected prior to type-casting.
* If a variable is missing and no default is provided, configuration validation fails.
* `$${...}` is not interpolated: it stands for the literal text `${...}`. Use it where a value
  needs `${` for its own syntax, such as a named capture group in a `TemplateRedirect` target
  (`/new/$${rest}`). Written as `${rest}`, it would be read as a variable called `rest`.

### Header Case Sensitivity

In strict accordance with RFC 7230, **all HTTP header evaluations in r7 are case-insensitive**. This applies to predicate matching (`RequestHeader`), filter mutations (`SetRequestHeader`), and CORS validations. It also holds for the headers a journal consumer reads back through `R7Tailer`: a lookup for `content-type` finds a header journaled as `Content-Type`, while iteration still shows each name as it was written. Folding is ASCII-only and does not depend on the JVM's default locale.

---

## 2. Operational Guarantees

As a deterministic request execution engine, r7 provides strict operational guarantees for infrastructure reliability.

### Deterministic Behavior

* **Evaluation:** Route evaluation order is strictly stable.
* **Execution:** Filter execution order is strictly stable. No implicit parallel filter execution occurs.
* **Routing:** Upstream target selection is strictly deterministic within the chosen load-balancing strategy.

### Failure Boundaries (Fail-Closed)

r7 strictly separates intentional request termination from runtime failures. If a filter encounters a runtime exception (e.g., malformed template substitution or regex execution failure), r7 **fails closed**. The pipeline immediately halts, bypasses all remaining filters (including response filters), and returns a `500 Internal Server Error` to prevent unsafe, partially mutated requests from routing upstream.

### Hot Reloads & State

r7 supports zero-downtime configuration reloads.

* Swapping the `routes.yaml` configuration is an **atomic operation**.
* In-flight requests are gracefully drained using the pipeline configuration that was active when the request was accepted.
* Stateful filter data (like `CircuitBreaker` tripping states and `RateLimiter` token buckets) is intentionally reset upon reload. This is **by design and not configurable**, guaranteeing immediate, strict adherence to the new configuration parameters.
* A filter declared in `global_filters` is instantiated **once** and that single instance is shared by every route. A `RateLimiter` in `global_filters` therefore enforces one limit across the whole gateway, drawing from one set of buckets no matter which route a request matched; a `CircuitBreaker` there tracks one upstream health state for all of them. This differs from declaring the same filter under several routes' own `filters:` blocks, where each route gets its own independent instance and state.

---

## 3. Execution Semantics

Understanding the exact pipeline order is critical for operating r7. For a given HTTP request, processing occurs strictly in this order:

0. **Request Validation:** Before anything else runs, a request `Transfer-Encoding` other than exactly `chunked` is rejected with `400 Bad Request` and the connection closed (see [Transfer-Encoding](#transfer-encoding)); then a path an upstream could resolve differently from how route predicates read it is rejected with `400 Bad Request` (see [Ambiguous Paths](#ambiguous-paths)).
1. **Global Request Filters:** Executed on every incoming request.
2. **Route Predicate Evaluation:** Routes are evaluated in declaration order.
3. **Route Match & Halt:** The *first* route whose predicates evaluate to `true` is selected. **Once a route is matched, no further routes are evaluated.** If no route matches, a `404 Not Found` is returned.
4. **Route Request Filters:** Pre-upstream mutations and enforcements execute in declaration order.
5. **Upstream Proxy Execution:** The request is dispatched to the load-balanced target.
6. **Route Response Filters:** Post-upstream mutations execute.
7. **Global Response Filters:** Final global response mutations.
8. **Async Journaling:** The request/response pair is dispatched to disk.

### Ambiguous Paths

Route predicates match the decoded request path, while the upstream receives the raw URI and applies its own normalisation. If the two disagree, a request can match a permissive route yet reach a resource that another route protects — `/public/../admin` matches `PathPrefix: /public`, and most upstreams serve it as `/admin`. r7 therefore refuses, with `400 Bad Request`, any request whose decoded path contains:

* a `.` or `..` segment, including with path parameters (`..;x`, which Tomcat and Spring treat as `..`);
* a backslash (`\`), which some servers treat as `/`;
* a control character (for example from `%00` or `%0a`);
* percent-encoding of `.`, `/`, `\` or `%` that is still present after decoding — an encoded slash (`%2F`) or double-encoding such as `%252e`.

The check always runs and is not configurable: no route is consulted, no filter runs and nothing is journaled for a rejected request. This deliberately refuses some request targets that are valid URIs: a path such as `/a/../b` is legal on the wire, but it is also exactly the shape that lets a gateway and an upstream disagree. Browsers and most HTTP client libraries already resolve dot-segments before sending (RFC 3986 §5.2.4), so their requests are unaffected; a client that sends them literally must normalise its paths first.

### Transfer-Encoding

A request `Transfer-Encoding` other than exactly `chunked` (for example `chunked, identity`, `gzip, chunked`, or the header repeated) is refused with `400 Bad Request` before routing. RFC 9112 §6.3 requires rejecting a request whose final coding is not `chunked`, and a list that r7 and an upstream read differently would make them disagree on where the body ends.

### Phase-Aware Filters

Filters are inherently phase-aware. Although they are declared in a single, unified list (either in `global_filters` or a route's `filters` block), they automatically participate only in the lifecycle phases relevant to their behavior.

* *Example:* `AddRequestHeader` executes immediately during phase 4. However, response-mutating filters like `SetResponseHeader` are registered during phase 4 but their execution is **deferred** until phase 6 (after the upstream response is received or generated).

### Short-Circuiting Flow

Intentional short-circuits (such as a failed `Require*` validation or a `ReturnResponse` execution) are standard pipeline control flows, not runtime exceptions.

If a filter short-circuits execution, phase 4 (remaining request filters) and phase 5 (Upstream Proxy Execution) are **skipped**. The pipeline immediately transitions to phase 6, executing any deferred **Route Response Filters** followed by **Global Response Filters** against the generated response context.

---

## 4. Upstream Configuration

The `upstream` block defines where r7 forwards requests, managing load balancing, active health monitoring, and resiliency.

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `strategy` | Enum | `ROUND_ROBIN` | How requests are spread across the available targets. `ROUND_ROBIN` is currently the only strategy: each request starts at the next target in turn, passing over targets that are down or whose connection pool is full. |
| `targets` | List | Required | A list of downstream nodes (`url`) capable of handling the request. |
| `health_check` | Object | None | Active background health monitoring. |
| `timeouts` | Object | None | Networking timeouts for this upstream. |
| `fallback` | Object | None | Alternate routing logic if primary targets fail. |

### Targets

Defines the physical endpoints requests will be routed to. The upstream must contain at least one target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `url` | String | Yes | The fully qualified URL (must begin with `http://` or `https://`). For `https`, the certificate chain is checked against the JVM trust store but the hostname is not verified, so any trusted certificate is accepted for any upstream. |

### Health Check (`health_check`)

Configures background probes to automatically evict and restore nodes.

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `path` | String | `/health` | The URI path appended to the target URL for the ping request. |
| `interval` | Duration | `10s` | The frequency of background HTTP probes. |
| `rise` | Integer | `2` | Consecutive successes required to mark an offline node healthy. |
| `fall` | Integer | `2` | Consecutive failures required to evict a healthy node. |
| `override` | Enum | `NONE` | **Warning:** `FORCE_DOWN` evicts the target regardless of probe success. `FORCE_UP` routes to the target regardless of probe failure. |

The monitor starts when the routes are loaded, not with a route's first request, so a dead target is found before traffic reaches it. Targets start out healthy; the first probe runs one `interval` after loading, and a target is evicted after `fall` failed probes. A hot reload starts monitors for the new routes and stops the previous ones, so health state starts over.

### Timeouts (`timeouts`)

*Currently, only response-read timeouts are configurable at the upstream level. Connect timeouts are handled globally by the proxy client.*

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `read` | Duration | `30s` | Maximum time to wait for a response after sending the request. At most `24d` (2147483647 ms, the proxy client's int millisecond limit). |

### Fallback (`fallback`)

Configures behavior when none of the route's upstream targets is available (all marked down by `health_check`). **Cyclic references (`a -> b -> a`) are rejected at startup**; a chain `a -> b -> c` is allowed, and each route in it falls back only when its own targets are down.

The request is handed to the fallback route **before** the first route's upstream-phase filters run, and is then processed as if it had matched the fallback route: its request filters, its upstream filters, its upstream (or its own fallback), and its response filters. Upstream-phase filters of the first route (`InjectBasicAuth`, `SetRequestHeader`, rewrites) therefore never reach the fallback's upstream. Filters whose request phase already ran for the first route - global filters and that route's own request filters such as `Cors` or `RateLimiter` - are not run again, but still receive their later phases (upstream, response and completion) on the same instances, so their state stays balanced and their clean-up applies to the fallback upstream too. Global filters run once per request. The exchange is journaled at the first route's journal levels and tagged with `gateway.fallback.id`.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `route_id` | String | Yes | The `id` of another route to execute (e.g., a stubbed mock route). |

---

## 5. Predicates

Predicates determine whether an incoming request matches a route.

* **Regex Semantics:** All regex predicates and `RequireMatch*` filters use standard Java Regex syntax and must match the **whole value**, as if anchored with `^` and `$`: `user` does not match `superuser`. Use `.*user.*` to match anywhere in the value. Matching is **case-sensitive** unless the inline flag `(?i)` is used.
* **Repeated Values:** When a query parameter, header or cookie occurs more than once, a value check (`QueryParameter`, `MatchQueryParameter`, `RequestHeader`, `MatchRequestHeader`, `Cookie`, `MatchCookie`, and the `RequireMatch*` filters) passes only if **every** occurrence passes. Upstream frameworks disagree about which occurrence wins (the first, the last, or all of them combined), so `?role=user&role=admin` must not satisfy a check on `role` that the upstream then reads as `admin`. A header value that is a comma-separated list on one line is matched as one value. Presence checks (`Has*`, `Require*` without a pattern) are unaffected.
* **Regex Cost:** Every match of a configured pattern against request data (predicates, `RequireMatch*`, `RewritePath`, `TemplateRedirect`) is limited to one million character reads, which a runaway match exhausts in a few milliseconds. Java's regex engine backtracks, and patterns with repeated groups around `.*` (`^(.*a){12}$`), several `.*` in a row, or backreferences can take seconds per request on a crafted input; a match that exceeds the limit answers the request with `500` instead of keeping a core busy. In a filter this is an ordinary refusal: response filters still run and the exchange is journaled. A normal, linear pattern never comes close; if requests fail this way, rewrite the pattern.
* **Exact or Regex:** `Path`, `PathPrefix`, `Host`, `RequestHeader`, `QueryParameter` and `Cookie` compare values literally; their `Match*` siblings take a regex. A literal value that looks like a regex (`/api/.*`, `^Bearer .+$`, `[a-f0-9]{32}`), or a path or host with Spring Cloud Gateway wildcards or templates (`/api/**`, `/users/{id}`, `*.example.com`), is logged as a warning when the routes load, with its path and line, because it would only match a client that sends those exact characters.
* **Empty Matches:** An empty match block (`match: []`) never evaluates to true. This behavior is intentional to prevent accidental catch-all routes caused by omitted predicates. It is the standard pattern for defining fallback-only routes.

### Logical Meta-Predicates

* `and`: True if **all** child predicates are true. Short-circuits on first failure.
* `or`: True if **at least one** child predicate is true. Short-circuits on first success.
* `not`: Inverts the result of a **single** child predicate.

---

### URI & Path Matching

#### Path

Matches the incoming request against an exact, fully qualified URI path.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `path` | String | Yes | The exact URI string (e.g., `/_internal/health`) to match against the request path. |

#### PathPrefix

Matches if the request path begins with a specific string prefix.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `prefix` | String | Yes | The exact string prefix (e.g., `/api/v1/`) to match against the request path. |

#### MatchPath

Evaluates the request path against a regular expression pattern.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `regexp` | String | Yes | A valid Java regular expression pattern to evaluate against the URI. |

---

### Header Matching

#### RequestHeader

Matches if a specific HTTP header exists and its value exactly matches the provided string.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the HTTP header. |
| `value` | String | Yes | The exact value the header must contain. |

#### HasRequestHeader

Matches if a specific HTTP header exists in the request, ignoring its value entirely.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the HTTP header to check for. |

#### MatchRequestHeader

Matches if a specific HTTP header exists and its value matches a regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the HTTP header. |
| `regexp` | String | Yes | A regex pattern the header value must match. |

---

### Query Parameter Matching

#### QueryParameter

Matches if a specific query parameter exists in the URL and its value exactly matches the provided string.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the query parameter. |
| `value` | String | Yes | The exact value the query parameter must contain. |

#### HasQueryParameter

Matches if a specific query parameter exists in the URL. This will match even if the parameter is used as a flag with no value (e.g., `?debug`).

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the query parameter to check for. |

#### MatchQueryParameter

Matches if a specific query parameter exists and its value matches a regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the query parameter. |
| `regexp` | String | Yes | A regex pattern the parameter value must match. |

---

### Cookie Matching

#### Cookie

Matches if a specific cookie exists in the request and its value exactly matches the provided string.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie. |
| `value` | String | Yes | The exact value the cookie must contain. |

#### HasCookie

Matches if a specific cookie exists in the request, ignoring its value entirely.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie to check for. |

#### MatchCookie

Matches if a specific cookie exists and its value matches a regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie. |
| `regexp` | String | Yes | A regex pattern the cookie value must match. |

---

### Network & Environment Matching

#### Host

Matches the incoming request against a list of allowed `Host` headers. It automatically handles matching with or without port numbers included in the header.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `hosts` | List of Strings | Yes | A list of acceptable hostnames (e.g., `["api.example.com", "v2.example.com"]`). |

#### Method

Matches the HTTP method of the incoming request against a list of allowed methods.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `include` | List of Strings | Yes | A list of allowed HTTP methods (e.g., `["GET", "POST"]`). |

#### RemoteAddr

Matches the client's IP address against a specific IP or a CIDR subnet block. It supports both IPv4 and IPv6. **Evaluates the resolved remote address**, which is the physical TCP peer address unless that peer is listed in `limits.trusted_proxies` in `server.yaml`, in which case `X-Forwarded-For`/`X-Real-IP` is honored instead. By default `trusted_proxies` is empty, so `X-Forwarded-For` is never read and this predicate cannot be bypassed by a spoofed header.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `source` | String | Yes | The IP address or CIDR notation (e.g., `192.168.1.5` or `10.0.0.0/24`). |

---

## 6. Filters

Filters mutate requests, shape traffic, or enforce security rules after a route is matched.

* **`Add*` Semantics:** Safely appends a non-destructive key/value pair.
* **`Set*` Semantics:** Destructively replaces existing keys with the new value.
* **`Remove*` Semantics:** Deletes the specified key entirely.
* **`Require*` Semantics:** Validates presence or format, transitioning the request to the response phase if validation fails.

### Mutation: Headers, Cookies, and Parameters

#### AddRequestHeader

Appends an HTTP header before forwarding the request to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to append to the header. |

#### SetRequestHeader

Replaces an existing HTTP header before forwarding the request upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |

#### AddResponseHeader

Appends an HTTP header on the client response before it is returned to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to append to the header. |

#### SetResponseHeader

Replaces an existing HTTP header on the client response.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |

#### RemoveRequestHeader

Deletes a specified HTTP header from the client request before it is forwarded.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

#### RemoveResponseHeader

Deletes a specified HTTP header from the upstream response before it is returned.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

#### SetRequestCookie

Injects or replaces a cookie directly in the `Cookie` header of the incoming request.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie. |
| `value` | String | Yes | The value of the cookie. |

#### SetResponseCookie

Injects a `Set-Cookie` response header instructing the client to create or overwrite the cookie.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the cookie. |
| `value` | String | Yes | The value of the cookie. |
| `domain` | String | No | The domain scope for the cookie. |
| `path` | String | No | The path scope for the cookie. |
| `max_age` | Duration | No | The time-to-live for the cookie. |
| `secure` | Boolean | No | Requires HTTPS. Defaults to `true` if omitted. |
| `http_only` | Boolean | No | Prevents client-side script access. Defaults to `true` if omitted. |
| `same_site` | Enum | No | Cross-site request forgery protection (`Strict`, `Lax`, `None`). Defaults to `Lax`. |

#### RemoveRequestCookie

Deletes a specific cookie from the `Cookie` header before the request is routed upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie to remove. |

#### AddQueryParameter

Appends a new query parameter to the request URL. Multiple parameters with the same name are supported.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the query parameter to add. |
| `value` | String | Yes | The value of the query parameter. |

#### SetQueryParameter

Replaces any existing query parameter with the specified name.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the query parameter to set. |
| `value` | String | Yes | The value of the query parameter. |

#### RemoveQueryParameter

Deletes a specific query parameter from the URL before forwarding.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the query parameter to remove. |

#### AddCorrelationId

Automatically injects the gateway's internal request ID into both the upstream request and the client response using the `X-Correlation-Id` header. By default, an `X-Correlation-Id` the client already sent is **replaced**, never appended to: it is client-controlled, and trusting it would let a client plant an arbitrary value in upstream logs and tracing under this gateway's name.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `trust_incoming` | Boolean | No | If `true`, an `X-Correlation-Id` already present on the incoming request is kept (and echoed back to the client) instead of being replaced with r7's own request ID. Defaults to `false`. Only enable this behind a trusted edge that itself controls or strips the header before it reaches r7. |

#### RemoveCacheHeaders

Deletes cache validation headers (`If-Modified-Since`, `If-None-Match`) and injects strict no-cache directives (`Cache-Control: no-cache`, `Pragma: no-cache`) upstream.
*This filter requires no configuration parameters.*

---

### Mutation: Path & Routing

#### StripPathPrefix

Removes a specified number of structural path segments from the beginning of the request path.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `parts` | Integer | Yes | The number of path segments (separated by `/`) to strip (e.g., `1` removes `/api` from `/api/v1`). Must be greater than 0. |

#### RewritePath

Transforms the upstream request path using regular expressions. Uses standard Java Matcher replacement semantics (`$1`, `$2`).

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `regexp` | String | Yes | The regular expression pattern to match against the request path. |
| `replacement` | String | Yes | The replacement string applied to the matched path. |

Both filters work on the **decoded** path and the gateway percent-encodes the result before it is sent upstream, so characters the client encoded as data never turn into URI syntax (`/api/a%3Fb` stripped by one part reaches the upstream as `/a%3Fb`, never as `/a?b`). Needless encoding of ordinary characters is normalised (`%41` is sent as `A`). Write a `replacement` in decoded form too: a literal `%20` in it is sent as `%2520`.

#### TemplateRedirect

Intercepts the request and immediately issues an HTTP redirect (3xx) based on a regex match of the path and a substitution template.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `source` | String | Yes | The regular expression pattern to match against the request path. |
| `target` | String | Yes | The destination URL template. Regex capture groups are referenced as in Java's `Matcher.replaceFirst`: by number (`$1`, `$2`, ...) or by name, written `$${name}` because `${...}` on its own is [environment variable interpolation](#environment-variable-interpolation). `\$` is a literal `$`. |
| `status` | Integer | No | The HTTP redirect status code. Defaults to `302` (Found). |

The capture groups are filled in from the client's request path, so the computed location is checked before it is sent. A path `target` (`/new/$1`) must produce a path: a location starting with `//`, `/\`, `\`, a scheme or whitespace is refused with `400`, because a browser would leave the site for it (`/go//evil.example` would otherwise redirect to `//evil.example`). An absolute `target` must have the form `http://host...`, `https://host...` or `//host...` with a non-empty host; any other scheme (`javascript:`, `data:`, even written with `//`), a scheme without `//`, and leading or trailing whitespace are rejected at startup. It must spell out its scheme and host literally, with any capture group only after the host's `/`, `?` or `#`; a capture group in the scheme or host is rejected at startup, since it would let any request choose where it is sent.

Captured text is decoded request data, so it is percent-encoded before it is placed in the location, for the part of the URL it lands in: before the target's first literal `?` or `#` it is encoded as a path (like `RewritePath`), after it as a single query or fragment value, which also encodes `&`, `=`, `+` and `;`. A client can therefore not add a query, fragment or parameter to the location: with `target: /new/$1`, a request for `/old/a%3Fadmin=true` redirects to `/new/a%3Fadmin=true`, not `/new/a?admin=true`. The part of the path that `source` does not match is carried over (as `replaceFirst` does) and encoded the same way. Write `target` itself in encoded form; it is sent as written.

---

### Security & Validation

The `RequireMatch*` filters follow the same regex and repeated-value rules as the predicates (see [Predicates](#5-predicates)): the whole value must match, and so must every occurrence.

#### RequireRequestHeader

Validates an HTTP header is present. Short-circuits the request if the header is missing.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required header. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireMatchRequestHeader

Validates an HTTP header is present and its value matches a specified regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required header. |
| `regexp` | String | Yes | The regex pattern the header value must match. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireQueryParameter

Validates a specific query parameter is present in the request URL.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required query parameter. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireMatchQueryParameter

Validates a query parameter is present and its value matches a specified regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required query parameter. |
| `regexp` | String | Yes | The regex pattern the parameter value must match. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireCookie

Validates a specific cookie is present in the request.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required cookie. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireMatchCookie

Validates a cookie is present and its value matches a specified regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required cookie. |
| `regexp` | String | Yes | The regex pattern the cookie value must match. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireAuthorizationHeader

Validates that incoming requests contain an `Authorization` header starting with either `Bearer ` or `Basic `. Short-circuits requests with a `401 Unauthorized` status if the header is missing or invalid.
*This filter requires no configuration parameters.*

#### BasicAuth

Verifies HTTP Basic Authentication credentials against a list of bcrypt hashes, and short-circuits with `401 Unauthorized` and a `WWW-Authenticate` challenge when they are missing or wrong. On success a fingerprint of the authenticated username (not the username itself) is recorded in the `gateway.auth.basic.user` attribute for journaling, the same convention used for redacted request headers.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `users` | List | Yes | Entries in htpasswd format, `username:bcrypt-hash`. Generate with `htpasswd -nB -C 12 <user>`, which prompts for the password rather than taking it as an argument, where it would land in shell history and the process list. |
| `realm` | String | No | The authentication realm presented to the client. Defaults to `Secure Area`. |
| `forward_credentials` | Boolean | No | Whether the client's verified `Authorization` header is passed on to the upstream. Defaults to `false`. A header another filter set in its place (such as `InjectBasicAuth`) is always kept. |

Only bcrypt hashes are accepted (`$2a$`, `$2b$`, `$2x$` or `$2y$`, cost 4-31); htpasswd's MD5, SHA-1 and crypt formats are rejected at startup, as are duplicate usernames and malformed entries.

A request carrying more than one `Authorization` value is refused with `401`: the field is a singleton, and only one value could be verified.

bcrypt is deliberately expensive, so the number of verifications running at once is capped at the number of CPU cores, shared by every `BasicAuth` filter. A request that cannot get a slot within 250 ms is answered with `503 Service Unavailable` and `Retry-After: 1`. Credentials that already verified are cached and skip bcrypt, so a flood of wrong passwords sheds its own requests rather than starving the gateway. The listener is plaintext HTTP, so Basic credentials are visible to anyone on the network path between client and gateway: use this filter only on a trusted network segment.

```yaml
filters:
  - BasicAuth:
      realm: "Admin API"
      users:
        - "alice:$2y$12$agcM9nDVmZGTJPT.ldejs.zoYitvQGSKw4FIG2Bt9bpsYf89eaeLG"
        - "bob:${BOB_HTPASSWD_ENTRY}"
```

Because bcrypt is deliberately expensive, successful credentials are cached so that repeat requests do not re-run the hash.

**Password guessing.** `BasicAuth` has no lockout, per user or per client: the bcrypt cost slows each guess down, and the concurrency cap keeps guessing from starving the gateway, but neither limits how many guesses a client gets over time. On any route reachable by untrusted clients, put a `RateLimiter` before `BasicAuth`, so a client is refused before its guess costs a bcrypt:

```yaml
filters:
  - RateLimiter:
      capacity: 20
      refill_tokens: 5
      refill_period: 1m
  - BasicAuth:
      users:
        - "alice:${ALICE_HTPASSWD_ENTRY}"
journal:
  request:
    status_overrides:
      401: METADATA   # every failed login is journaled, even when the route journals nothing else
```

The limiter keys on the client address (an IPv6 /64 by default), so it slows a single source; a guessing campaign spread over many addresses needs limits upstream of r7 as well.

Use a bcrypt cost of at least 10; `htpasswd -B` defaults to 5, so pass `-C 12` (for example `htpasswd -nB -C 12 <user>`). The accepted range is 4-31, and each step doubles the cost of a verification — and of a guess.

**Failed logins are journaled, not logged.** A refused request is answered with `401` and recorded in the route's journal with its request ID, client address, and time. `status_overrides` (above) records it even on a route that otherwise journals nothing.

A failed verification is never cached and always costs a full bcrypt, whether the username exists or not, so response time does not reveal which usernames are configured. That holds as long as every user is hashed at the same cost — mixed cost factors are an enumeration oracle in their own right, since a faster reply then identifies a cheaper user.

#### InjectBasicAuth

Generates a Base64 encoded Basic Authentication string and injects it into the `Authorization` header of the upstream request.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `username` | String | Yes | The authentication username. |
| `password` | String | Yes | The authentication password. |

#### RequestSizeLimit

Limits the size of request bodies.

* **Declared length** (`Content-Length`): a request declaring more than `max_size` is refused with `413 Payload Too Large` before anything is sent upstream; a malformed or negative length is refused with `400`.
* **Undeclared length** (chunked, or HTTP/2 without `Content-Length`): the body is counted as it streams. Once it crosses `max_size`, r7 closes both the client connection and the upstream connection mid-body, so the upstream never receives the request as complete. The response headers may already have been impossible to send, so the client sees the connection close rather than a `413`.

The server-wide `limits.max_entity_size` applies on top of this and cannot be raised by it.

Independently of this filter, r7 never hands an upstream a chunked request body that was cut short (client disconnect, malformed chunk, or `max_entity_size`) as if it were complete: the upstream connection is closed mid-body instead.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `max_size` | Size | Yes | The maximum allowed request size formatted with a size suffix (e.g., `10MB`, `500KB`). |

#### Cors

Handles Cross-Origin Resource Sharing (CORS). Answers CORS preflights (`OPTIONS` carrying both `Origin` and `Access-Control-Request-Method`) with `204 No Content`, and decorates other responses with the appropriate Access-Control headers. Any other `OPTIONS` request is passed to the upstream.

* A preflight from an origin that is not allowed gets `204` with no Access-Control headers at all, so it learns nothing about the policy.
* When `allowed_origins` is a list rather than `*`, every response carries `Vary: Origin` (added to any existing `Vary`), so a shared cache never serves one origin's answer to another.
* The filter's policy is authoritative: if the upstream sends `Access-Control-Allow-Origin` or `Access-Control-Allow-Credentials` for an origin the filter does not allow, they are removed, and with `allow_credentials` off an upstream `Access-Control-Allow-Credentials` is removed for allowed origins too.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `allowed_origins` | String | Yes | Comma-separated list of permitted origins, or `*` to allow any origin. |
| `allowed_methods` | String | No | Value mapped to `Access-Control-Allow-Methods`. |
| `allowed_headers` | String | No | Value mapped to `Access-Control-Allow-Headers`. |
| `max_age` | String | No | Value mapped to `Access-Control-Max-Age`. |
| `allow_credentials` | Boolean | No | If `true`, sets `Access-Control-Allow-Credentials` to `true`. |

---

### Traffic Shaping & Reliability

#### RateLimiter

Provides token-bucket rate limiting. Requests exceeding the limit are rejected with `429 Too Many Requests`. Automatically injects `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Retry-After` headers. **Buckets are keyed by the client's resolved address** (the TCP peer, or the `X-Forwarded-For`/`X-Real-IP` client behind a trusted proxy): an IPv4 address as is, an IPv6 address by its leading `ipv6_prefix_length` bits (a `/64` by default). A filter that sets the `rate_limit_key` attachment overrides this.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `capacity` | Long | Yes | Maximum number of tokens the bucket can hold. |
| `refill_tokens` | Long | Yes | Number of tokens added to the bucket per refill period. |
| `refill_period` | Duration | Yes | The time interval (e.g., `2s`) for the token refill. |
| `max_buckets` | Long | No | Maximum number of unique identities/buckets to track. Defaults to `10000`. |
| `max_bucket_ttl` | Duration | No | Time-to-live for idle buckets. Defaults to the time a fully-drained bucket needs to refill to `capacity` (`ceil(capacity / refill_tokens) * refill_period`), floored at `30s`. A shorter TTL would evict an idle client's bucket before it could refill, handing it a fresh full bucket - effectively resetting its limit - the next time it is seen. |
| `ipv6_prefix_length` | Integer | No | How many leading bits identify one IPv6 client (1-128). Defaults to `64`: a subscriber is typically assigned a whole `/64` and can use any address in it, so limiting per full address would give one client unlimited buckets. IPv4 clients are keyed by their full address. |

#### CircuitBreaker

Monitors upstream responses and temporarily blocks routing **for the entire route** if a specified threshold of `5xx` server errors is reached. Fast-fails with `503 Service Unavailable` while open, and allows a single probe request through during half-open state.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `failure_threshold` | Integer | Yes | The number of consecutive `5xx` failures required to trip the circuit open. |
| `cooldown_period` | Duration | Yes | The time to wait (e.g., `12s`) before transitioning to a half-open state to probe upstream health. |

---

### Short-Circuiting & Overrides

#### ReturnResponse

Short-circuits the routing pipeline, halting execution and immediately returning a mock or static response to the client. The response defaults to `text/plain; charset=utf-8` unless a `SetResponseHeader` is used alongside it to define `Content-Type`. *(Note: Deferred response filters declared after `ReturnResponse` in the configuration still execute against this generated response).*

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The HTTP status code to return (e.g., `200`, `418`). |
| `body` | String | Yes | The plain text or JSON payload to return in the response body. |

#### StaticContent

Short-circuits the pipeline to serve static files directly from the disk. **Security:** Path traversal attempts (`../`) are automatically rejected. Only `GET` and `HEAD` are served (anything else is `405`), every answer carries `X-Content-Type-Options: nosniff`, and a directory addressed without its trailing slash is redirected to the path the client sent plus `/`, whatever filters such as `StripPathPrefix` did to the path. Files carry `Last-Modified` and an `ETag`, answer conditional requests with `304`, and serve a single byte `Range`.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `base_directory` | String | Yes | The absolute physical path on the disk (e.g., `/var/www/html/`) containing the static assets. |
| `follow_symlinks` | Boolean | No (default `false`) | Follow symbolic links found under the base directory. The base directory itself may be a symlink either way (e.g. an atomically swapped `current` release symlink); this is only needed for links inside it. |
| `list_directory` | Boolean | No (default `false`) | Render an HTML directory listing when a request resolves to a directory and no welcome file (e.g. `index.html`) is found there. When disabled, such a request is rejected with `403 Forbidden`. |
| `serve_hidden_files` | Boolean | No (default `false`) | Serve files and directories whose name starts with `.` (e.g. `.env`, `.git/`, `.htpasswd`). When disabled, such a request is answered `404 Not Found`, and a directory listing, if enabled, leaves their names out; `.well-known/` is always served. |

#### SetStatus

Overrides the HTTP response status code returned to the client, regardless of the upstream target's actual response.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The valid HTTP status code (100-599) to enforce on the response. |

---

## 7. Journaling & Storage

Request/response journaling is executed asynchronously to avoid blocking the hot path.

### Storage Configuration (`server.yaml -> storage`)

Storage utilizes memory-mapped files separated into shards of a defined `shard_size` to minimize lock contention and manage disk IO.

### Logging Levels (`routes.yaml -> journal`)

Verbosity can be set generically or overridden conditionally based on HTTP status codes.

| Level | Captured Data | Notes |
| --- | --- | --- |
| `NONE` | None | Disables logging completely for the route/status. |
| `METADATA` | URI, Method, Status, Timing, IP | Highly performant, minimal storage footprint. |
| `HEADERS` | Metadata + Headers | Captures both request and response headers. |
| `FULL` | Headers + Bodies | Captures binary payloads of any size in full, streamed into the journal as they pass. |

#### Status Overrides (`status_overrides`)

Each direction (`request`, `response`) takes an optional `status_overrides` map from a status key to a level, applied when the response status matches:

```yaml
journal:
  response:
    level: METADATA
    status_overrides:
      5xx: HEADERS       # a whole status class
      401,403: HEADERS   # a comma-separated list of codes
      429: HEADERS       # a single code
```

| Key form | Example | Matches |
| --- | --- | --- |
| Status class `Nxx` | `4xx`, `5XX` | Every code in that class. `N` is `1` to `5`; case-insensitive. |
| Single code | `429` | That code. Must be three digits, `100`–`599`. |
| Comma-separated list | `401,403` | Each listed code. Every entry is a single code; classes and ranges are not allowed inside a list. |

Any other key (a range such as `500-599`, `999`, `6xx`, an empty list entry) is rejected at startup and on hot reload with an error naming the field, e.g. `[routes.my-route.journal.response.status_overrides] Invalid status override key '500-599'`. Avoid overlapping keys (`5xx` and `503`): which one wins for the shared codes follows map order and is not part of the contract.

An override may not raise a direction to `FULL` unless its base level is already `FULL` (bodies are captured as they stream, and a lower base installs no capture), and a `FULL` request may not be lowered (its body is written before any status exists). Both are refused at startup.

### Unrouted Requests (`routes.yaml -> unrouted`)

Some requests are refused before any route is chosen, so no route's journal settings apply to them:

| Reason (`gateway.unrouted.reason`) | Status | Cause |
| --- | --- | --- |
| `no_route` | `404` | No route matched. |
| `ambiguous_path` | `400` | See [Ambiguous Paths](#ambiguous-paths). |
| `transfer_encoding` | `400` | See [Transfer-Encoding](#transfer-encoding). |
| `trace` | `501` | TRACE is never forwarded. |
| `regex_budget` | `500` | A route predicate's pattern exhausted its regex budget. |

By default these leave no journal entry. They are mostly what scanners and probes send, so they are worth recording where an audit trail matters. The top-level `unrouted` section journals them under the route ID `<unrouted>`, with the reason in the `gateway.unrouted.reason` attribute. The ID is reserved: a configured route may not use it.

```yaml
unrouted:
  journal:
    request:
      level: HEADERS
      status_overrides:
        404: NONE      # route misses: not journaled at all...
    response:
      level: METADATA
      status_overrides:
        404: NONE      # ...which needs both directions, as overrides apply per direction
```

Levels and `status_overrides` work as for a route, except that `FULL` is refused at startup: a refused request's body is never read, and after a bad `Transfer-Encoding` its boundaries are not known. Either direction may be left out. Leaving out the response journals nothing for it. Leaving out the request does not quite mean nothing: when the response is journaled, the request is recorded at `METADATA` (start line, client address, timing) to anchor it, as for any route. A scanner can produce many of these requests, so pick levels with the journal's retention in mind.

---

## 8. Complete Example Configuration

The following example demonstrates a standard r7 configuration, showcasing path routing, method restrictions, filter application, static serving, conditional journaling, resilient fallback routing, and active health checks.

```yaml title="routes.yaml"
version: '{{git.rev.abbr}}'

# Global filters applied to all routes
global_filters:
  - SimpleMetrics
  - AddCorrelationId

routes:
  # Internal health loopback (Short-circuiting proxy)
  - id: internal-health-proxy
    match:
      - Path:
          path: /_internal/health
    filters:
      - RewritePath:
          regexp: "^/_internal/health$"
          replacement: "/health"
    upstream:
      targets:
        - url: "http://127.0.0.1:18888"

  # Static content handoff (Short-circuits upstream phase)
  - id: static-web-assets
    match:
      - PathPrefix:
          prefix: /assets/
    filters:
      - StripPathPrefix:
          parts: 1
      - SetResponseHeader:
          name: X-Content-Type-Options
          value: nosniff
      - StaticContent:
          base_directory: /var/www/html/
    upstream: null

  # Complex routing with nested logical predicates
  - id: protected-admin-api
    match:
      - and:
          - PathPrefix:
              prefix: /api/admin
          - or:
              - RemoteAddr:
                  source: 10.0.0.0/8
              - HasRequestHeader:
                  name: X-Internal-VPN
          - not:
              - MatchQueryParameter:
                  name: debug
                  regexp: "true|1"
    upstream:
      strategy: ROUND_ROBIN
      health_check:
        interval: 5s
        rise: 2
        fall: 3
        path: /system/health
      timeouts:
        read: 15s
      fallback:
        route_id: fallback-stub
      targets:
        - url: https://admin-1.internal
        - url: https://admin-2.internal
    filters:
      - RequireAuthorizationHeader
      - RateLimiter:
          capacity: 100
          refill_tokens: 10
          refill_period: 1s
      - CircuitBreaker:
          failure_threshold: 5
          cooldown_period: 30s
    journal:
      request:
        level: METADATA
        status_overrides:
          5xx: HEADERS
          401,403: HEADERS
      response:
        level: METADATA

  - id: fallback-stub
    match: [] # Empty match blocks are never hit naturally; used only via fallback
    filters:
      - ReturnResponse:
          status: 503
          body: '{"error": "Admin services currently offline"}'
      - SetResponseHeader:
          name: Content-Type
          value: application/json
    upstream: null

```

---

## 9. Server Configuration

The `server.yaml` file controls the foundational infrastructure of the r7 gateway. This includes network binding, HTTP limits, upstream connection pooling, and disk-backed storage configurations for journaling.

`server.yaml` is optional. Without one, r7 runs on the defaults in the tables below: the gateway listens on `0.0.0.0:8888`, the management endpoint on `127.0.0.1:18888`, journals go to `./journals` (or `R7_JOURNAL_DIR`), and `X-Forwarded-For` is never trusted. The startup log says when the defaults are in use. Add a `server.yaml` only for the settings you need to change; anything it leaves out keeps its default.

r7 reads `routes.yaml` and `server.yaml` from the directory you start it in; `R7_ROUTES_CONFIG` and `R7_SERVER_CONFIG` name other paths. The container images start in `/app/config`, so mount your files there. `routes.yaml` is required, and r7 refuses to start without it. A `server.yaml` named by `R7_SERVER_CONFIG` must exist too: r7 will not fall back to the defaults when the file you pointed it at is missing, since that would quietly drop your limits and trusted proxies.

### Server Configuration (`server`)

Defines the gateway's listening interface and port, and how many connections it holds.

| Parameter | Type | Description |
| --- | --- | --- |
| `host` | String | The IP address or interface the primary gateway binds to (e.g., `0.0.0.0` for all interfaces). |
| `port` | Integer | The primary port the gateway listens on for incoming traffic. |
| `max_connections` | Integer | When this many client connections are open, the gateway stops accepting new ones; they wait in the accept backlog. Defaults to `20000`. Keep it below the process's open-file limit, less what upstream connections and journals need. |
| `idle_timeout` | Duration | Closes a connection that sits between requests for this long, and a WebSocket that carries nothing either way for this long - a client that keeps a quiet WebSocket open must ping more often. Defaults to `30s`; at most `24d`. |
| `backlog` | Integer | Length of the kernel's accept backlog. Defaults to `1000`. |

None of the three can be turned off: a listener without them lets a client hold file descriptors until the gateway stops accepting traffic.

Each connection runs on a virtual thread of its own, and so does every request on it: a filter that blocks (`BasicAuth`'s bcrypt, a lookup) parks its thread and holds up nothing else.

### Management Configuration (`management`)

Defines the interfaces for the internal status and metrics endpoints.

| Parameter | Type | Description |
| --- | --- | --- |
| `host` | String | The interface for the internal management server. Defaults to `127.0.0.1`, or to the `R7_MANAGEMENT_HOST` environment variable when set; the container images set it to `0.0.0.0` so the published status port works. The endpoint has no authentication: publish it only on a private network. |
| `port` | Integer | The port for the internal management server. |
| `request_parse_timeout` | Duration | Time allowed to receive a complete request head. Defaults to `2s`. |
| `idle_timeout` | Duration | Closes a connection that sits between requests for this long. Defaults to `30s`. |
| `max_connections` | Integer | Connections the management port accepts at once; more wait in the accept backlog until one closes. Defaults to `64`. |
| `allowed_hosts` | List of Strings | Host names, besides `localhost` and `host`, that a request's `Host` header may name. Empty by default. |

The management listener shares the process's file descriptors with the gateway itself, so none of its timeouts can be turned off (each must be positive and at most `24d`) and its connections are capped: without these, a client that opens connections and sends part of a request on each could use up descriptors until the gateway stops accepting traffic.

A request whose `Host` names anything other than `localhost`, the configured `host`, an entry in `allowed_hosts` or an IP address gets `421`. This stops DNS rebinding, where a web page re-resolves its own name to `127.0.0.1` and so reads a loopback-only dashboard from the browser of someone on the gateway host. IP addresses are always accepted because rebinding needs a name. If you reach the management port through a DNS name, add that name to `allowed_hosts`.

The management endpoint is read-only (`GET`/`HEAD`; anything else gets `405`) and sends `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and a `Content-Security-Policy` that allows only the dashboard's own script (by hash) and requests back to the same origin. Route configuration shown there has sensitive values replaced with `******`: `InjectBasicAuth` passwords, `BasicAuth` user hashes, request and response cookie values and query parameter values set by filters, credentials embedded in upstream target URLs (`http://user:pass@host`), request and response header values set by filters unless the header is one the journal records as safe in that direction (see `journal_security`), and the patterns of `RequireMatch*` filters. Summaries of filters and predicates show such values as fingerprints, so two routes configured alike can still be told apart.

What the dashboard shows beyond the configuration itself:

* **Response time** per route as p50/p95/p99 over the `SimpleMetrics` window (`period`, default 2 minutes), alongside the lifetime average. Percentiles come from a fixed histogram with four buckets per power of two and are reported as the bucket's upper bound, so they read at most 25% high; the window starts empty after a restart.
* **Reload status** of `routes.yaml`: when the running routes were loaded, and when the most recent edit was rejected. A rejected edit leaves the previous routes running; the dashboard says so but not why, since validation messages can quote configured values - the reason is in the gateway log.
* **Upstream target health** for routes with a `health_check`, from the moment routes are loaded (see [Health Check](#health-check-health_check)); a hot reload resets it.
* **Requests no route matched**, which are answered `404` and are not part of any route's figures.
* **`server.yaml` as in effect**, with every value that differs from the built-in default marked.
* **Component status**: what a route's filters and its upstream report about themselves, as `OK`, `WARN` or `ERROR`. A route with a component in `WARN` or `ERROR` says so in the route list, and the route's page shows each filter's status with its detail and values. `CircuitBreaker` is `ERROR` while open and `WARN` while half-open; `RateLimiter` is `WARN` once it tracks `max_buckets` clients, since it then evicts buckets early and the limit holds less tightly; a health-checked upstream is `WARN` while some of its targets are down and `ERROR` while all are. A custom filter can report its own (see [Reporting a filter's status](extensibility.md#reporting-a-filters-status)).

#### Paths

| Path | Answers |
| --- | --- |
| `/metrics` | Prometheus metrics, in the text format (`text/plain; version=0.0.4`). |
| `/health` | `200` with `{"status":"UP"}`, or `503` with `{"status":"DOWN"}` once the status snapshot is more than 10 seconds old. For liveness probes. |
| any other | The dashboard, or with `Accept: application/json` the data behind it. |

The JSON and the metrics are rendered together every 2 seconds and served as rendered, so any number of dashboards and scrapers cost the gateway the same, and the two always agree. `rendered_at` in the JSON says when its data was read. The snapshot is rendered on the scheduler that also runs health checks and reloads `routes.yaml`, which is why a stale one fails `/health`: the gateway's housekeeping has stopped. `/health` does not turn `DOWN` for an open circuit breaker or a down upstream; those are the gateway doing its job, and restarting it would not help.

#### Metrics

| Metric | Type | Labels | What it counts |
| --- | --- | --- | --- |
| `r7_info` | gauge | `version` | Always `1`; carries the running version. |
| `r7_start_time_seconds` | gauge | | When the process started, in seconds since the epoch. |
| `r7_routes_config_rejected` | gauge | | `1` while the latest edit of `routes.yaml` was rejected and the previous routes still run. |
| `r7_unrouted_requests_total` | counter | | Requests no route matched. |
| `r7_connections_active` | gauge | | Open client connections to the gateway port. |
| `r7_journal_available_bytes` | gauge | | Free space for journals in `work_dir`. |
| `r7_route_requests_total` | counter | `route`, `code` | Responses sent to clients. |
| `r7_route_upstream_responses_total` | counter | `route`, `code` | Responses received from upstreams. |
| `r7_route_active_requests` | gauge | `route` | Requests in progress. |
| `r7_route_active_websockets` | gauge | `route` | Open WebSocket tunnels. |
| `r7_route_journal_bytes_total` | counter | `route` | Bytes written to the journal. |
| `r7_route_request_duration_seconds` | histogram | `route` | Time from request to response. Bucket bounds are powers of two from 256µs to about 16.8s. |
| `r7_component_health` | gauge | `route`, `component`, `position` | A filter's or upstream's status: `0` OK, `1` WARN, `2` ERROR. |
| `r7_component_value` | gauge | `route`, `component`, `position`, `name` | A number a filter or upstream reports, such as a circuit breaker's `rejected_requests`. |
| `r7_jvm_heap_used_bytes`, `r7_jvm_heap_max_bytes` | gauge | | Heap in use, and its limit. |
| `r7_jvm_direct_used_bytes` | gauge | | Direct buffer memory in use. |
| `r7_jvm_gc_seconds_total` | counter | | Time spent in garbage collection. |
| `r7_process_open_fds`, `r7_process_max_fds` | gauge | | Open file descriptors, and the limit. |

The `route_*` metrics cover routes with the `SimpleMetrics` filter. Their counts, other than the duration histogram, are saved in `work_dir` and carry on across restarts, like the dashboard's. `component` is the filter's name, or `upstream`; `position` is the filter's place in the route's pipeline, global filters first, and `0` for the upstream, so two filters of one kind on a route stay apart. A global filter is one instance in front of every route, so it is reported once, without a `route` label. The names and labels above are a contract: a change to them is a breaking change.

### HTTP Options (`http`)

Configures the HTTP server layer, including protocol support and request parsing behaviors.

| Parameter | Type | Description |
| --- | --- | --- |
| `enable_http2` | Boolean | Enables HTTP/2. Defaults to `false`. The listener is plaintext, so this means h2c (prior knowledge or `Upgrade: h2c`): enable it only if clients actually need HTTP/2 to the gateway (for example gRPC behind an L4 load balancer), since it adds a second protocol parser to the attack surface. Upstream connections are unaffected: they stay HTTP/1.1, and a request that HTTP/1.1 cannot express as sent - a header with a line break, a body longer or shorter than its `Content-Length` - is answered `400` rather than forwarded. |
| `request_parse_timeout` | Duration | The timeout (e.g., `2s`) for parsing an incoming HTTP request. At most `24d` (2147483647 ms). |

### Limits Configuration (`limits`)

Configures boundaries and payload restrictions for incoming HTTP requests to prevent resource exhaustion.

A request over `max_header_size` or `max_header_count` is refused before any filter runs, with `400` or `431` depending on which layer caught it. A body over `max_entity_size` is refused with `413` when its `Content-Length` declares it. A chunked body is stopped once it streams past the limit, and never reaches the upstream as a complete request. These limits hold on every server r7 runs on, including the experimental servlet host, whatever the server's own parser settings.

| Parameter | Type | Description |
| --- | --- | --- |
| `max_header_size` | Size | The maximum size of the request line and all request headers combined (e.g., `8KB`), not of each header separately. At most 62500 bytes, the longest input configured regular expressions are budgeted for. |
| `max_header_count` | Integer | The maximum number of HTTP headers allowed per request. |
| `max_entity_size` | Size | The maximum allowed request payload/entity size (e.g., `2MB`). |
| `trusted_proxies` | List of Strings | CIDR ranges (e.g., `["10.0.0.0/8"]`) of reverse proxies allowed to set `X-Forwarded-For`/`X-Real-IP`. Empty by default: the socket peer address is always used, so a direct client cannot spoof its own address. When `X-Forwarded-For` is a multi-hop chain, it is walked from right to left, trusting only the hops that are themselves in `trusted_proxies`; the resolved address is the first (rightmost-to-leftmost) entry that isn't. This stops a client from spoofing the header by prepending a forged entry before the value a trusted proxy appended. |

#### Headers forwarded to the upstream

Before a request is proxied, r7 removes the client headers an upstream must not receive as written. The journal and filters still see the client's original headers.

* **Hop-by-hop headers** (RFC 9110 §7.6.1): `Connection`, `Keep-Alive`, `Proxy-Connection`, `Proxy-Authorization`, `Upgrade`, `TE` (except `TE: trailers`), and every header named in `Connection`. A WebSocket upgrade keeps `Upgrade: websocket` and is sent with `Connection: Upgrade`. `Host`, `Content-Length` and `Transfer-Encoding` are never removed because a client named them in `Connection`.
* **Forwarding and identity claims**, unless the peer is in `trusted_proxies`: `Forwarded`, `X-Forwarded-*`, `X-Real-IP`, `X-Client-IP`, `True-Client-IP`, `X-Cluster-Client-IP`, and the path overrides `X-Original-URL` and `X-Rewrite-URL`. The `X-Forwarded-*` match is on the header name's letters, not its exact spelling: `X_Forwarded_For` and other underscore or mixed-separator variants are stripped too, since CGI-derived backends (classic CGI, FastCGI, and frameworks that read headers out of a CGI-style environment) fold every `-` in a header name to `_` before their application code ever sees it, so such a backend reads either spelling identically.

r7 then sets `X-Forwarded-For`, `-Proto`, `-Host`, `-Port` and `-Server` itself. For a trusted proxy it extends that proxy's values (its client's address stays in `X-Forwarded-For`, followed by the proxy's own); for anyone else they describe the direct connection. Upstreams can therefore trust the `X-Forwarded-*` headers they receive from r7.

### Proxy Client (`proxy`)

Configures r7's upstream client, an HTTP/1.1 client with a keep-alive pool per target.

| Parameter | Type | Description |
| --- | --- | --- |
| `max_connections_per_target` | Integer | Requests in flight to one upstream target, each on a connection of its own. Defaults to `4096`. |
| `max_queue_size` | Integer | Requests that may wait for one of those to finish; past it a request is answered `503`. Defaults to `1000`. |
| `max_request_time` | Duration | The longest a proxied exchange may take, waiting for a connection included, before it is answered `504`. Defaults to `60s`; at most `24d` (2147483647 ms). |
| `ttl` | Duration | How long an idle pooled connection is kept (e.g., `30s`): below the upstream's own keep-alive timeout, since a request that fails on a connection the upstream already closed is retried only when it is idempotent. At most `24d` (2147483647 ms). |

Response framing is checked strictly: conflicting `Content-Length`s, folded headers, malformed chunks and transfer codings other than `chunked` are answered `502`, and the connection is not reused. A target that refuses the connection is skipped for the next. `https` targets are verified against the JVM's trust store, host name included. A WebSocket handshake the upstream accepts (`101`) becomes a tunnel that carries bytes both ways until either side closes; see `idle_timeout` above.

### Storage & Journaling (`storage`)

Configures the disk-backed storage mechanism used for high-speed request and response journaling.

| Parameter | Type | Description |
| --- | --- | --- |
| `work_dir` | String | The directory path where the memory-mapped journal files are stored. Defaults to `journals` (relative to the working directory), or to the `R7_JOURNAL_DIR` environment variable when set; the container images set it to `/journals`. Created with mode `0750`, and journal segments with `0640` (owner read-write, group read): a sidecar tailer running as another user needs to share the gateway's group. The umask can only narrow these; an existing directory or file keeps its mode. |
| `shard_size` | Size | The target size limit for a single journal shard (e.g., `200MB`). Must be a whole number of 32KB journal blocks, from 64KB to just under 2GB; any whole number of megabytes qualifies. |
| `shard_count` | Integer | The number of shards (files) to split the journal across to reduce lock contention and manage file sizes. Defaults to `2`; must be a power of two. See [Performance tuning](performance_tuning.md#journal-storage-shard_count-pre_fault-and-where-segments-live). |
| `pre_fault` | Boolean | When `true`, the warmer touches every page of each segment before the writer gets it. Defaults to `false`, and best left so: on Linux 5.14 and later r7 already faults pages in a few MB ahead of the writer (fault-ahead), which was faster in every measurement and costs a fraction of the memory. `pre_fault` charges every warmed segment to memory at once (about 6 × `shard_size` per shard) and disables fault-ahead. See [Performance tuning](performance_tuning.md#journal-storage-shard_count-pre_fault-and-where-segments-live). |
| `compression` | String | `zstd` (the default) or `none`. With `zstd`, each 32KB block of a journal segment carries one zstd stream and every entry is flushed into it as it is written, so entries stay individually committed and readable. On benchmark traffic it made the journal 9-15x smaller with no measurable latency at 1,000 req/s; it costs CPU on the writing thread, which shows only near saturation. Where zstd's native library cannot be loaded, the journal is written uncompressed and a warning is logged. See [Performance tuning](performance_tuning.md#journal-compression-compression-and-compression_level). |
| `compression_level` | Integer | zstd level, `1` to `19`. Defaults to `1`, which measured best: higher levels cost noticeably more CPU for a few percent less disk. Ignored when `compression` is `none`. |
| `journal_security` | Object | Shapes the whitelists of header and query parameter names journaled in plain text. See below. |

#### Journal Redaction (`storage.journal_security`)

At `HEADERS`/`FULL` journal levels, r7 journals every header name but only writes a header's
*value* verbatim when the name is on a built-in whitelist (things like `host`, `user-agent`,
`content-type`, `etag`, `x-forwarded-for`, `sec-fetch-site`, `sec-fetch-mode`); everything else —
`authorization`, `cookie`, `set-cookie`, custom API keys, and so on — is written as a
fingerprint of its value instead of the value itself. This list is separate per direction
(request headers vs. response headers).

`journal_security` shapes that whitelist in one of two ways, per direction:

| Parameter | Type | Description |
| --- | --- | --- |
| `additional_safe_request_headers` | List of Strings | Request header names to add on top of the built-in whitelist, so their values are journaled in plain text. |
| `additional_safe_response_headers` | List of Strings | Response header names to add on top of the built-in whitelist. |
| `safe_request_headers` | List of Strings | If non-empty, replaces the built-in request whitelist entirely — the effective whitelist is exactly this list. |
| `safe_response_headers` | List of Strings | If non-empty, replaces the built-in response whitelist entirely. |
| `safe_query_parameters` | List of Strings | Query parameter names whose values are journaled in plain text. Empty by default, so every query parameter value is fingerprinted. See [Query parameters](#query-parameters) below. |
| `safe_query_parameters_case_sensitive` | Boolean | Match `safe_query_parameters` exactly, case included. Defaults to `false`: `page` on the list also covers `Page` and `PAGE`. |
| `fingerprint_key` | String | Optional, at least 32 characters. When set, redacted values are written as a keyed HMAC-SHA-256 fingerprint (`id:hmac:` + 16 hex digits) rather than the default unkeyed SHA-256 (`id:sha256:` + 6 hex digits), so that a reader of the journal cannot recover a low-entropy secret by hashing guesses. Supply it with `${VAR}` interpolation. See [Journaling: redacted header and query parameter values](journaling.md#redacted-header-and-query-parameter-values). |

Header names are matched case-insensitively. There is no way to remove a single header from the
built-in whitelist while keeping the rest — use `safe_*_headers` to replace the whole list if
you need exact control. Setting both `additional_safe_*_headers` and `safe_*_headers` for the
same direction is a validation error: a full replacement and an addition to the defaults it
replaces is a contradiction, not something to guess at. Anything not on the resulting whitelist
is fingerprinted, no exceptions (unkeyed unless `fingerprint_key` is set, which it should be
in production). This affects only journaling; it has no effect on what headers
are sent to clients or upstreams.

```yaml title="server.yaml"
storage:
  journal_security:
    # Add a couple of names to the built-in defaults. Only ever add headers whose values
    # are not secrets or credentials - anything on this list is journaled in plain text.
    additional_safe_request_headers:
      - x-tenant-id
    additional_safe_response_headers:
      - x-internal-build-id

    # ...or replace the response whitelist entirely (mutually exclusive with
    # additional_safe_response_headers above):
    # safe_response_headers:
    #   - content-type
    #   - etag

    # Query parameters journaled in plain text; every other value is fingerprinted.
    safe_query_parameters:
      - page
      - sort
```

##### Query parameters

The query is part of the request line, which every journal level records, `METADATA` included.
The same rule as for headers applies to it: each parameter name is journaled as sent, and its
value is written verbatim only when the name is on `safe_query_parameters`; every other value is
replaced by the same fingerprint a header value gets. There is no built-in list, because no
parameter name is safe everywhere, so with nothing configured every value is fingerprinted:

```
GET /search?page=2&api_key=s3cret&q=shoes HTTP/1.1                       # as sent
GET /search?page=2&api_key=id:sha256:1ec1c2&q=id:sha256:01ea5d HTTP/1.1     # journaled, with page safe
```

- **Case.** Names on `safe_query_parameters` match regardless of case, as header names do:
  `page` also covers `Page` and `PAGE`. Query parameter names are case sensitive on the wire,
  so when your upstreams tell `id` from `ID`, set `safe_query_parameters_case_sensitive: true`
  and only the exact spelling on the list is journaled in plain text.
- **Encoding.** Names are matched after percent-decoding, as the upstream reads them: `p%61ge`
  and `page` are the same parameter, and `user+id` and `user%20id` both match `user id`. Write
  names in `safe_query_parameters` decoded; a name containing a percent-escape is refused at
  startup with the decoded form to use instead. Values are fingerprinted decoded too, so
  `q=a+b` and `q=a%20b` get the same fingerprint.
- **Repeats.** Each occurrence of a repeated parameter is redacted on its own; a safe name is
  safe every time it appears.
- **Bare parameters.** A parameter without `=` (`?s3cret-token`) is fingerprinted whole unless
  its name is safe: it is the only thing it carries. Empty values (`?q=`) stay empty.

Both request lines are redacted the same way: the one the client sent and the one forwarded
upstream, after filters such as `SetQueryParameter` have changed it. Redaction applies to the
journal only; it never changes the query an upstream receives.