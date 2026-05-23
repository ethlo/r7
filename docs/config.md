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

### Environment Variable Interpolation

Configuration values support environment variable injection using the `${VAR_NAME:default_value}` syntax.

* Values are injected prior to type-casting.
* If a variable is missing and no default is provided, configuration validation fails.

### Header Case Sensitivity

In strict accordance with RFC 7230, **all HTTP header evaluations in r7 are case-insensitive**. This applies to predicate matching (`RequestHeader`), filter mutations (`SetRequestHeader`), and CORS validations.

---

## 2. Operational Guarantees

As a deterministic request execution engine, r7 provides strict operational guarantees for infrastructure reliability.

### Deterministic Behavior

* **Evaluation:** Route evaluation order is strictly stable.
* **Execution:** Filter execution order is strictly stable. No implicit parallel filter execution occurs.
* **Routing:** Upstream target selection is strictly deterministic within the chosen load-balancing strategy.

### Filter Failure Semantics

r7 is designed to **fail-closed**. If a filter encounters a runtime exception (e.g., a malformed header mutation, regex execution failure, or invalid template substitution), the pipeline immediately halts and returns a `500 Internal Server Error`. This prevents unsafe, partially mutated requests from bleeding into the upstream network.

### Hot Reloads & State

r7 supports zero-downtime configuration reloads.

* Swapping the `routes.yaml` configuration is an **atomic operation**.
* In-flight requests are gracefully drained using the pipeline configuration that was active when the request was accepted.
* Stateful filter data (like `CircuitBreaker` tripping states and `RateLimiter` token buckets) is intentionally reset upon reload to guarantee immediate, strict adherence to the new configuration parameters.

---

## 3. Execution Semantics

Understanding the exact pipeline order is critical for operating r7. For a given HTTP request, processing occurs strictly in this order:

1. **Global Request Filters:** Executed on every incoming request.
2. **Route Predicate Evaluation:** Routes are evaluated in declaration order.
3. **Route Match & Halt:** The *first* route whose predicates evaluate to `true` is selected. **Once a route is matched, no further routes are evaluated.** If no route matches, a `404 Not Found` is returned.
4. **Route Request Filters:** Pre-upstream mutations and enforcements execute in declaration order.
5. **Upstream Proxy Execution:** The request is dispatched to the load-balanced target.
6. **Route Response Filters:** Post-upstream mutations execute.
7. **Global Response Filters:** Final global response mutations.
8. **Async Journaling:** The request/response pair is dispatched to the disk-backed storage.

### Phase-Aware Filters

Filters are inherently phase-aware. Although they are declared in a single, unified list (either in `global_filters` or a route's `filters` block), they automatically participate only in the lifecycle phases relevant to their behavior.

* *Example:* `AddRequestHeader` executes immediately during phase 4. However, response-mutating filters like `SetResponseHeader` are registered during phase 4 but their execution is **deferred** until phase 6 (after the upstream response is received).

### Short-Circuiting

If any filter in the pipeline short-circuits execution (e.g., a `RequireAuthorizationHeader` fails, or a `ReturnResponse` executes), all subsequent request filters and the Upstream Proxy Execution are **skipped**. The pipeline immediately transitions to the response phase (phase 6), executing any deferred route response filters and global response filters against the generated response context.

---

## 4. Upstream Configuration

The `upstream` block defines where r7 forwards requests, managing load balancing, active health monitoring, and resiliency.

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `strategy` | Enum | `ROUND_ROBIN` | The load balancing strategy applied across the targets. |
| `targets` | List | Required | A list of downstream nodes (`url`) capable of handling the request. |
| `health_check` | Object | None | Active background health monitoring. |
| `timeouts` | Object | None | Networking timeouts for this upstream. |
| `fallback` | Object | None | Alternate routing logic if primary targets fail. |

### Targets

Defines the physical endpoints requests will be routed to. The upstream must contain at least one target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `url` | String | Yes | The fully qualified URL (must begin with `http://` or `https://`). |

### Health Check (`health_check`)

Configures background probes to automatically evict and restore nodes.

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `path` | String | `/health` | The URI path appended to the target URL for the ping request. |
| `interval` | Duration | `10s` | The frequency of background HTTP probes. |
| `rise` | Integer | `2` | Consecutive successes required to mark an offline node healthy. |
| `fall` | Integer | `2` | Consecutive failures required to evict a healthy node. |
| `override` | Enum | `NONE` | **Warning:** `FORCE_DOWN` evicts the target regardless of probe success. `FORCE_UP` routes to the target regardless of probe failure. |

### Timeouts (`timeouts`)

*Currently, only response-read timeouts are configurable at the upstream level. Connect timeouts are handled globally by the proxy client.*

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `read` | Duration | `30s` | Maximum time to wait for a response after sending the request. |

### Fallback (`fallback`)

Configures behavior if the upstream connection fails completely. **Fallback recursion is not permitted; cyclic references are rejected at startup.**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `route_id` | String | Yes | The `id` of another route to execute (e.g., a stubbed mock route). |

---

## 5. Predicates

Predicates determine whether an incoming request matches a route.

* **Regex Semantics:** All regex predicates use standard Java Regex syntax. Matching is **partial by default** unless explicitly anchored (`^`, `$`). Matching is **case-sensitive** unless the inline flag `(?i)` is used.
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

Matches the client's IP address against a specific IP or a CIDR subnet block. It supports both IPv4 and IPv6. **Evaluates the physical TCP peer address**; it does not read `X-Forwarded-For` to prevent IP spoofing behind untrusted proxies.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `source` | String | Yes | The IP address or CIDR notation (e.g., `192.168.1.5` or `10.0.0.0/24`). |

---

## 6. Filters

Filters mutate requests, shape traffic, or enforce security rules after a route is matched.

* **`Add*` Semantics:** Safely appends a non-destructive key/value pair.
* **`Set*` Semantics:** Destructively replaces existing keys with the new value.
* **`Remove*` Semantics:** Deletes the specified key entirely.
* **`Require*` Semantics:** Validates presence or format, terminating the request if validation fails.

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

Automatically injects the gateway's internal request ID into both the upstream request and the client response using the `X-Correlation-Id` header.
*This filter requires no configuration parameters.*

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

#### TemplateRedirect

Intercepts the request and immediately issues an HTTP redirect (3xx) based on a regex match of the path and a substitution template.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `source` | String | Yes | The regular expression pattern to match against the request path. |
| `target` | String | Yes | The destination URL template. Regex capture groups can be referenced using `{{name}}` or `{{index}}`. |
| `status` | Integer | No | The HTTP redirect status code. Defaults to `302` (Found). |

---

### Security & Validation

#### RequireRequestHeader

Validates an HTTP header is present. Rejects the request if the header is missing.

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

Validates that incoming requests contain an `Authorization` header starting with either `Bearer ` or `Basic `. Rejects requests with a `401 Unauthorized` status if the header is missing or invalid.
*This filter requires no configuration parameters.*

#### InjectBasicAuth

Generates a Base64 encoded Basic Authentication string and injects it into the `Authorization` header of the upstream request.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `username` | String | Yes | The authentication username. |
| `password` | String | Yes | The authentication password. |

#### RequestSizeLimit

Evaluates the `Content-Length` header of incoming requests. If the header is missing or the request uses chunked transfer encoding, r7 actively monitors the streamed byte count. Terminates the connection immediately with `413 Payload Too Large` if the limit is exceeded.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `max_size` | Size | Yes | The maximum allowed request size formatted with a size suffix (e.g., `10MB`, `500KB`). |

#### Cors

Handles Cross-Origin Resource Sharing (CORS). Intercepts `OPTIONS` preflight requests returning `204 No Content`, and decorates standard responses with appropriate Access-Control headers.

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

Provides token-bucket rate limiting. Requests exceeding the limit are rejected with `429 Too Many Requests`. Automatically injects `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Retry-After` headers. **Buckets are keyed by the TCP peer IP address.**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `capacity` | Long | Yes | Maximum number of tokens the bucket can hold. |
| `refill_tokens` | Long | Yes | Number of tokens added to the bucket per refill period. |
| `refill_period` | Duration | Yes | The time interval (e.g., `2s`) for the token refill. |
| `max_buckets` | Long | No | Maximum number of unique identities/buckets to track. Defaults to `10000`. |
| `max_bucket_ttl` | Duration | No | Time-to-live for idle buckets. Defaults to `max(refill_period * 10, 30s)`. |

#### CircuitBreaker

Monitors upstream responses and temporarily blocks routing **for the entire route** if a specified threshold of `5xx` server errors is reached. Fast-fails with `503 Service Unavailable` while open, and allows a single probe request through during half-open state.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `failure_threshold` | Integer | Yes | The number of consecutive `5xx` failures required to trip the circuit open. |
| `cooldown_period` | Duration | Yes | The time to wait (e.g., `12s`) before transitioning to a half-open state to probe upstream health. |

---

### Short-Circuiting & Overrides

#### ReturnResponse

Short-circuits the routing pipeline, halting execution and immediately returning a mock or static response to the client. The response defaults to `text/plain` unless a `SetResponseHeader` is used alongside it to define `Content-Type`. *(Note: Response-phase filters declared after `ReturnResponse` in the configuration still execute against this generated response).*

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The HTTP status code to return (e.g., `200`, `418`). |
| `body` | String | Yes | The plain text or JSON payload to return in the response body. |

#### StaticContent

Short-circuits the pipeline to serve static files directly from the disk using a high-performance native handler. **Security:** Path traversal attempts (`../`) are automatically rejected.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `base_directory` | String | Yes | The absolute physical path on the disk (e.g., `/var/www/html/`) containing the static assets. |

#### SetStatus

Overrides the HTTP response status code returned to the client, regardless of the upstream target's actual response.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The valid HTTP status code (100-599) to enforce on the response. |

---

## 7. Journaling & Storage

Request/response journaling is executed asynchronously to avoid blocking the hot path.

### Storage Configuration (`server.yaml -> storage`)

Storage utilizes memory-mapped files separated into shards. When all shards reach the `shard_size` limit, r7 utilizes a **ring-buffer rollover policy**, overwriting the oldest shard to guarantee disk limits are strictly respected without halting operations.

### Logging Levels (`routes.yaml -> journal`)

Verbosity can be set generically or overridden conditionally based on HTTP status codes.

| Level | Captured Data | Notes |
| --- | --- | --- |
| `NONE` | None | Disables logging completely for the route/status. |
| `METADATA` | URI, Method, Status, Timing, IP | Highly performant, minimal storage footprint. |
| `HEADERS` | Metadata + Headers | Captures both request and response headers. |
| `FULL` | Headers + Bodies | Supports arbitrary binary payload capture. Payloads exceeding 1MB are automatically truncated to prevent runaway storage. |

---

## 8. TLS & Security

r7 expects TLS termination to be handled by an edge load balancer (e.g., AWS ALB, Cloudflare) directly in front of it.

---

## 9. Complete Example Configuration

The following example demonstrates a standard r7 configuration, showcasing path routing, method restrictions, filter application, static serving, conditional journaling, resilient fallback routing, and active health checks.

```yaml
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
          5xx: FULL
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

## 10. Server Configuration

The `server.yaml` file controls the foundational infrastructure of the r7 gateway. This includes network binding, HTTP limits, upstream connection pooling, and disk-backed storage configurations for journaling.

### Server Configuration (`server`)

Defines the primary listening interfaces and ports for the gateway.

| Parameter | Type | Description |
| --- | --- | --- |
| `host` | String | The IP address or interface the primary gateway binds to (e.g., `0.0.0.0` for all interfaces). |
| `port` | Integer | The primary port the gateway listens on for incoming traffic. |

### Management Configuration (`management`)

Defines the interfaces for the internal status and metrics endpoints.

| Parameter | Type | Description |
| --- | --- | --- |
| `host` | String | The interface for the internal management server. |
| `port` | Integer | The port for the internal management server. |

### HTTP Options (`http`)

Configures the HTTP server layer, including protocol support and request parsing behaviors.

| Parameter | Type | Description |
| --- | --- | --- |
| `enable_http2` | Boolean | Enables HTTP/2 protocol support. |
| `always_set_keep_alive` | Boolean | Forces the server to send the `Connection: keep-alive` header to maintain persistent connections. |
| `request_parse_timeout` | Duration | The timeout (e.g., `2s`) for parsing an incoming HTTP request. |

### Limits Configuration (`limits`)

Configures boundaries and payload restrictions for incoming HTTP requests to prevent resource exhaustion.

| Parameter | Type | Description |
| --- | --- | --- |
| `max_header_size` | Size | The maximum allowed size for a single HTTP header (e.g., `8KB`). |
| `max_header_count` | Integer | The maximum number of HTTP headers allowed per request. |
| `max_entity_size` | Size | The maximum allowed request payload/entity size (e.g., `2MB`). |
| `max_parameter_count` | Integer | The maximum number of parameters allowed per request. |
| `max_cookie_count` | Integer | The maximum number of cookies allowed per request. |

### Proxy Client (`proxy`)

Configures the behavior of the internal reverse proxy client that connects to upstream targets.

| Parameter | Type | Description |
| --- | --- | --- |
| `connections_per_thread` | Integer | The maximum number of pooled upstream connections allowed *per worker thread*. |
| `max_queue_size` | Integer | The maximum number of pending requests allowed to queue while waiting for an available upstream connection. |
| `max_request_time` | Duration | The absolute maximum time (e.g., `60s`) a proxy request is allowed to take before timing out. |
| `ttl` | Duration | The time-to-live (e.g., `30s`) for idle upstream connections in the pool. |

### Storage & Journaling (`storage`)

Configures the disk-backed storage mechanism used for high-speed request and response journaling.

| Parameter | Type | Description |
| --- | --- | --- |
| `work_dir` | String | The directory path where the memory-mapped journal files are stored. |
| `shard_size` | Size | The target size limit for a single journal shard (e.g., `200MB`). |
| `shard_count` | Integer | The number of shards (files) to split the journal across to reduce lock contention and manage file sizes. |
| `pre_fault` | Boolean | When `true`, pre-allocates and forces the OS to fault the memory-mapped pages immediately, trading startup time for reduced runtime latency. |