# r7 Config Reference

## Routing Configuration

The r7 gateway is configured using a declarative YAML file called `routes.yaml`. This configuration defines how incoming requests are matched, modified by filters, routed to upstream targets, and logged by the journaling system.

The configuration supports environment variable interpolation (e.g., `${ENV_VAR:default_value}`), allowing you to use a single configuration structure across multiple environments.

### Core Concepts

* **Global Filters:** Applied to every request passing through the gateway, ensuring baseline behaviors like metric collection or correlation ID injection. Declared using the `global_filters` key at the root of the configuration.
* **Routes:** The core mapping logic. Each route requires a unique `id`, a `match` condition (like path prefixes or HTTP methods), and an `upstream` target.
* **Route Filters:** Specific mutations or traffic controls (like Rate Limiting, Circuit Breaking, or Header modification) applied only when a specific route is matched. Declared using the `filters` key inside a specific route block.
* **Short-Circuiting & Static Serving:** Routes can bypass the upstream proxy client entirely using filters like `ReturnResponse` or `StaticContent`. In these cases, the `upstream` block can be omitted or set to `null`.
* **Journaling:** Granular control over what is logged. You can define base logging levels (e.g., `NONE`, `METADATA`, `HEADERS`, `FULL`) and override these levels based on specific HTTP status codes.

### Route Matching

Routes are evaluated in declaration order. The first route whose predicates fully match the incoming request is selected. If no route matches the request, r7 returns
404 Not Found.

Predicate evaluation is sequential and short-circuiting:

* `and` stops on first failure
* `or` stops on first success
* `not` evaluates exactly one child predicate

### Filter Execution

Filters execute sequentially in the order they are declared.

A filter may:

* mutate the request
* mutate the response
* short-circuit execution entirely
* terminate processing with an immediate response

### Upstream Execution

If the route is not terminated by a filter, the request is forwarded to the configured upstream target.

### Journaling

Request and response journaling occurs asynchronously and does not block request processing.

---

## Upstream Configuration

The `upstream` block defines where r7 forwards matched requests. It manages load balancing, health monitoring, timeouts, and resilient fallback behaviors.

### Core Properties

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `strategy` | Enum | `ROUND_ROBIN` | The load balancing strategy applied across the defined targets. |
| `targets` | List | Required | A list of downstream nodes capable of handling the request. |
| `health_check` | Object | None | Configuration for active background health monitoring. |
| `timeouts` | Object | None | Networking timeouts explicitly for this upstream group. |
| `fallback` | Object | None | Defines alternate routing logic if all primary targets fail. |

### Targets

Defines the physical endpoints requests will be routed to. The upstream must contain at least one target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `url` | String | Yes | The fully qualified URL (must begin with `http://` or `https://` and include a valid host). |

### Health Check (`health_check`)

Configures active background probes to automatically evict unhealthy nodes and restore them once recovered.

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `path` | String | `/health` | The URI path appended to the target URL for the ping request. Must start with `/`. |
| `interval` | Duration | `10s` | The frequency of background HTTP probes. Must be positive. |
| `rise` | Integer | `2` | Consecutive successful probes required to mark an offline node as healthy. |
| `fall` | Integer | `2` | Consecutive failed probes required to evict a healthy node from the pool. |
| `override` | Enum | `NONE` | Forces the target state (`NONE`, `FORCE_UP`, `FORCE_DOWN`). |

### Timeouts (`timeouts`)

Granular limits for network interactions with the specific upstream group.

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `read` | Duration | `30s` | Maximum time to wait for a response after sending the request. Must be positive. |

### Fallback (`fallback`)

Configures the gateway's behavior if the upstream connection fails completely (e.g., all targets offline or connection refused).

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `route_id` | String | Yes | The `id` of another defined route to hand execution over to (such as a stubbed mock route). |

---

## Predicates

Predicates define the matching conditions that determine whether an incoming request should be routed to a specific upstream target. A route is only executed if its predicates evaluate to true.

### Logical Meta-Predicates

Predicates can be nested and combined using logical operators. This allows for complex routing rules based on multiple conditions.

* `and`: Evaluates to true only if **all** child predicates evaluate to true. Expects a list of predicates.
* `or`: Evaluates to true if **at least one** child predicate evaluates to true. Expects a list of predicates.
* `not`: Inverts the result of a **single** child predicate. Expects exactly one predicate object.

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

Matches the full request URI path against a regular expression pattern.

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

#### Query

Matches if a specific query parameter exists in the URL and its value exactly matches the provided string.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the query parameter. |
| `value` | String | Yes | The exact value the query parameter must contain. |

#### HasQuery

Matches if a specific query parameter exists in the URL. This will match even if the parameter is used as a flag with no value (e.g., `?debug`).

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the query parameter to check for. |

#### MatchQuery

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

Matches the client's IP address against a specific IP or a CIDR subnet block. It supports both IPv4 and IPv6 network definitions.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `source` | String | Yes | The IP address or CIDR notation (e.g., `192.168.1.5` or `10.0.0.0/24`) to match against the client's remote address. |

---

## Filters

Filters modify requests, shape traffic, or enforce security rules after a route is matched but before (or after) it reaches the upstream target.

### Mutation: Headers, Cookies, and Parameters

#### AddRequestHeader

Adds or overwrites an HTTP header before forwarding the request to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |
| `override` | Boolean | No | If `true`, overwrites existing headers with the same name. If `false` or omitted, appends the value. |

#### AddResponseHeader

Adds or overwrites an HTTP header on the client response before it is returned to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |
| `override` | Boolean | No | If `true`, overwrites existing headers with the same name. If `false` or omitted, appends the value. |

#### RemoveRequestHeader

Strips a specified HTTP header from the client request before it is forwarded to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

#### RemoveResponseHeader

Strips a specified HTTP header from the upstream response before it is returned to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

#### AddRequestCookie

Injects a new cookie directly into the `Cookie` header of the incoming request before it is routed upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie. |
| `value` | String | Yes | The value of the cookie. |

#### AddResponseCookie

Injects a new `Set-Cookie` header into the response returned to the client, complete with necessary security metadata.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the cookie. |
| `value` | String | Yes | The value of the cookie. |
| `domain` | String | No | The domain scope for the cookie. |
| `path` | String | No | The path scope for the cookie. |
| `max_age` | Duration | No | The time-to-live for the cookie. |
| `secure` | Boolean | No | Requires HTTPS. Defaults to `true` if omitted. |
| `http_only` | Boolean | No | Prevents client-side script access. Defaults to `true` if omitted. |
| `same_site` | String | No | Cross-site request forgery protection (`Strict`, `Lax`, `None`). Defaults to `Lax`. |

#### RemoveRequestCookie

Strips a specific cookie from the `Cookie` header before the request is routed upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie to remove. |

#### AddQueryParameter

Appends a new query parameter to the request URL before forwarding to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the query parameter to add. |
| `value` | String | Yes | The value of the query parameter. |

#### RemoveQueryParameter

Strips a specific query parameter from the URL before forwarding it to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the query parameter to remove. |

#### AddCorrelationId

Automatically injects the gateway's internal request ID into both the upstream request and the client response using the `X-Correlation-Id` header.
*This filter requires no configuration parameters.*

#### RemoveCacheHeaders

Strips cache validation headers (`If-Modified-Since`, `If-None-Match`) and injects strict no-cache directives (`Cache-Control: no-cache`, `Pragma: no-cache`) into the upstream request.
*This filter requires no configuration parameters.*

---

### Mutation: Path & Routing

#### StripPathPrefix

Removes a specified number of structural path segments from the beginning of the request path before it is routed upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `parts` | Integer | Yes | The number of path segments (separated by `/`) to strip (e.g., `1` removes `/api` from `/api/v1`). Must be greater than 0. |

#### RewritePath

Rewrites the upstream request path using regular expressions before forwarding it to the target.

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

Ensures an HTTP header is present. Rejects the request if the header is missing.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required header. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireMatchRequestHeader

Ensures an HTTP header is present and its value matches a specified regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required header. |
| `regexp` | String | Yes | The regex pattern the header value must match. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireQueryParameter

Ensures a specific query parameter is present in the request URL.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required query parameter. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireMatchQueryParameter

Ensures a query parameter is present and its value matches a specified regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required query parameter. |
| `regexp` | String | Yes | The regex pattern the parameter value must match. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireCookie

Ensures a specific cookie is present in the request.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the required cookie. |
| `reject_status_code` | Integer | No | The HTTP status code to return if validation fails. Defaults to `400`. |

#### RequireMatchCookie

Ensures a cookie is present and its value matches a specified regular expression.

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

Evaluates the `Content-Length` header of incoming requests. Rejects payloads exceeding the configured limit with `413 Payload Too Large`.

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

Provides token-bucket rate limiting. Requests exceeding the limit are rejected with `429 Too Many Requests`. Automatically injects `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Retry-After` headers.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `capacity` | Long | Yes | Maximum number of tokens the bucket can hold. |
| `refill_tokens` | Long | Yes | Number of tokens added to the bucket per refill period. |
| `refill_period` | Duration | Yes | The time interval (e.g., `2s`) for the token refill. |
| `max_buckets` | Long | No | Maximum number of unique identities/buckets to track. Defaults to `10000`. |
| `max_bucket_ttl` | Duration | No | Time-to-live for idle buckets. Defaults to `max(refill_period * 10, 30s)`. |

#### CircuitBreaker

Monitors upstream responses and temporarily blocks routing to the target if a specified threshold of `5xx` server errors is reached. Fast-fails with `503 Service Unavailable` while open.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `failure_threshold` | Integer | Yes | The number of consecutive `5xx` failures required to trip the circuit open. |
| `cooldown_period` | Duration | Yes | The time to wait (e.g., `12s`) before transitioning to a half-open state to probe upstream health. |

---

### Short-Circuiting & Overrides

#### ReturnResponse

Short-circuits the routing pipeline, halting execution and immediately returning a mock or static response to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The HTTP status code to return (e.g., `200`, `418`). |
| `body` | String | Yes | The plain text or JSON payload to return in the response body. |

#### StaticContent

Short-circuits the pipeline to serve static files directly from the disk using a high-performance native handler. *(Note: Typically requires `StripPathPrefix` to run first to correctly map the URI).*

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `base_directory` | String | Yes | The absolute physical path on the disk (e.g., `/var/www/html/`) containing the static assets. |

#### SetStatus

Overrides the HTTP response status code returned to the client, regardless of the upstream target's actual response.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The valid HTTP status code (100-599) to enforce on the response. |

---

## Complete Example Configuration

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
      - StaticContent:
          base_directory: /var/www/html/
    upstream: null

  # Mock response serving (Short-circuits upstream phase)
  - id: stubbed-api
    match:
      - PathPrefix:
          prefix: /api/v1/beta/
    filters:
      - ReturnResponse:
          status: 418
          body: "Beta API offline"
    upstream: null

  # Full option upstream config with active health checking, limits, and strategy
  - id: search-api
    match:
      - PathPrefix:
          prefix: /search
    filters:
      - StripPathPrefix:
          parts: 1
    upstream:
      strategy: ROUND_ROBIN
      health_check:
        interval: 5s
        rise: 2
        fall: 3
        path: /system/health
        # override: FORCE_DOWN
      timeouts:
        read: 15s
      fallback:
        route_id: stubbed-api
      targets:
        - url: https://search-1.example.com
        - url: https://search-2.example.com

  # Complex routing with method/query matching, configured filters, and conditional journaling
  - id: my-service
    match:
      - PathPrefix:
          prefix: /hello
      - Method:
          include:
            - GET
            - POST
      - MatchQuery:
          name: tenant_id
          regexp: "^[A-Za-z0-9]+$"
    upstream:
      targets:
        - url: http://localhost:1111
    filters:
      # Filters can be declared with specific configuration arguments (snake_case)
      - RateLimiter:
          capacity: 5
          refill_tokens: 1
          refill_period: 2s
      - CircuitBreaker:
          failure_threshold: 10
          cooldown_period: 12s
      - AddResponseHeader:
          name: X-Powered-By
          value: ethlo r7
      - RemoveRequestCookie:
          name: JSESSIONID
      # Filters requiring no arguments are declared by name only
      - AddCorrelationId
      - RequireAuthorizationHeader
    journal:
      request:
        level: NONE
        # Increase log verbosity dynamically based on the response status
        status_overrides:
          401,403: HEADERS
          429: METADATA
          5xx: HEADERS
      response:
        level: NONE

```

---

## Server Configuration

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