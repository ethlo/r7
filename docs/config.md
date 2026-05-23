# r7 Config Reference

## Routing Configuration

The r7 gateway is configured using a declarative YAML file called `routes.yaml`. This configuration defines how incoming requests are matched, modified by filters, routed to upstream targets, and logged by the journaling system.

The configuration supports environment variable interpolation (e.g., `${ENV_VAR:default_value}`), allowing you to use a single configuration structure across multiple environments.

### Core Concepts

* **Global Filters:** Applied to every request passing through the proxy, ensuring baseline behaviors like metric collection or correlation ID injection.
* **Routes:** The core mapping logic. Each route requires a unique `id`, a `match` condition (like path prefixes or HTTP methods), and an `upstream` target.
* **Route Filters:** Specific mutations or traffic controls (like Rate Limiting, Circuit Breaking, or Header modification) applied only when a specific route is matched.
* **Short-Circuiting & Static Serving:** Routes can bypass the upstream proxy client entirely using filters like `ReturnResponse` or `StaticContent`. In these cases, the `upstream` block can be omitted or set to `null`.
* **Journaling:** Granular control over what is logged. You can define base logging levels (e.g., `NONE`, `METADATA`, `HEADERS`, `FULL`) and override these levels based on specific HTTP status codes.

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

* `and`: Evaluates to true only if **all** child predicates evaluate to true.
* `or`: Evaluates to true if **at least one** child predicate evaluates to true.
* `not`: Inverts the result of a **single** child predicate.

### URI & Path Matching

| Predicate | Parameter | Required | Description |
| --- | --- | --- | --- |
| `Path` | `path` | Yes | Matches the incoming request against an exact, fully qualified URI path. |
| `PathPrefix` | `prefix` | Yes | Matches if the request path begins with a specific string prefix. |
| `MatchPath` | `regexp` | Yes | Matches the full request URI path against a regular expression pattern. |

### Header Matching

| Predicate | Parameter | Required | Description |
| --- | --- | --- | --- |
| `RequestHeader` | `name`, `value` | Yes | Matches if the header exists and exactly matches the value. |
| `HasRequestHeader` | `name` | Yes | Matches if the header exists, ignoring its value. |
| `MatchRequestHeader` | `name`, `regexp` | Yes | Matches if the header exists and matches the regex pattern. |

### Query Parameter Matching

| Predicate | Parameter | Required | Description |
| --- | --- | --- | --- |
| `Query` | `name`, `value` | Yes | Matches if the query parameter exists and exactly matches the value. |
| `HasQuery` | `name` | Yes | Matches if the query parameter exists (even as a flag with no value). |
| `MatchQuery` | `name`, `regexp` | Yes | Matches if the query parameter exists and matches the regex pattern. |

### Cookie Matching

| Predicate | Parameter | Required | Description |
| --- | --- | --- | --- |
| `Cookie` | `name`, `value` | Yes | Matches if the cookie exists and exactly matches the value. |
| `HasCookie` | `name` | Yes | Matches if the cookie exists, ignoring its value. |
| `MatchCookie` | `name`, `regexp` | Yes | Matches if the cookie exists and matches the regex pattern. |

### Network & Environment Matching

| Predicate | Parameter | Required | Description |
| --- | --- | --- | --- |
| `Host` | `hosts` (List) | Yes | Matches against a list of allowed `Host` headers. |
| `Method` | `include` (List) | Yes | Matches the HTTP method (e.g., `GET`, `POST`) against the allowed list. |
| `RemoteAddr` | `source` | Yes | Matches the client IP against a specific IP or CIDR subnet (e.g., `10.0.0.0/24`). |

---

## Filters

Filters modify requests, shape traffic, or enforce security rules after a route is matched but before (or after) it reaches the upstream target.

### Mutation: Headers, Cookies, and Parameters

| Filter | Parameters | Description |
| --- | --- | --- |
| `AddRequestHeader` | `name`, `value`, `override` (bool) | Adds or overwrites an HTTP header before forwarding upstream. |
| `AddResponseHeader` | `name`, `value`, `override` (bool) | Adds or overwrites an HTTP header on the client response. |
| `RemoveRequestHeader` | `name` | Strips a specific header from the request. |
| `RemoveResponseHeader` | `name` | Strips a specific header from the response. |
| `AddRequestCookie` | `name`, `value` | Injects a new cookie directly into the `Cookie` header upstream. |
| `AddResponseCookie` | `name`, `value`, `domain`, `path`, `max_age`, `secure`, `http_only`, `same_site` | Injects a `Set-Cookie` header into the response with security metadata. |
| `RemoveRequestCookie` | `name` | Strips a specific cookie from the upstream request. |
| `AddQueryParameter` | `name`, `value` | Appends a new query parameter to the request URL. |
| `RemoveQueryParameter` | `name` | Strips a specific query parameter from the URL. |
| `AddCorrelationId` | None | Automatically injects the internal request ID into both request and response headers (`X-Correlation-Id`). |
| `RemoveCacheHeaders` | None | Strips cache validation headers and injects strict `no-cache` directives upstream. |

### Mutation: Path & Routing

| Filter | Parameters | Description |
| --- | --- | --- |
| `StripPathPrefix` | `parts` (int) | Removes a specified number of structural path segments (e.g., `/api/v1`) from the URI. |
| `RewritePath` | `regexp`, `replacement` | Rewrites the upstream path using regex matching and capture group replacement. |
| `TemplateRedirect` | `source`, `target`, `status` (int) | Intercepts the request and issues an HTTP redirect (default `302`) based on regex substitution. |

### Security & Validation

| Filter | Parameters | Description |
| --- | --- | --- |
| `RequireRequestHeader` | `name`, `reject_status_code` (int) | Ensures a header is present. Rejects with `400` (or custom status) if missing. |
| `RequireMatchRequestHeader` | `name`, `regexp`, `reject_status_code` (int) | Ensures a header is present and matches a regex. Rejects if invalid. |
| `RequireAuthorizationHeader` | None | Validates that an `Authorization` header exists and starts with `Bearer ` or `Basic `. Rejects with `401`. |
| `InjectBasicAuth` | `username`, `password` | Generates and injects a Base64 `Basic` auth header upstream. |
| `RequestSizeLimit` | `max_size` (Size) | Evaluates `Content-Length`. Rejects payloads exceeding the limit with `413`. |
| `Cors` | `allowed_origins`, `allowed_methods`, `allowed_headers`, `max_age`, `allow_credentials` | Handles preflight `OPTIONS` and decorates responses with Access-Control headers. |

### Traffic Shaping & Reliability

| Filter           | Parameters | Description |
|------------------| --- | --- |
| `RateLimiter`     | `capacity`, `refill_tokens`, `refill_period`, `max_buckets`, `max_bucket_ttl` | Token-bucket rate limiting. Rejects excess requests with `429` and sets `X-RateLimit` headers. |
| `CircuitBreaker` | `failure_threshold`, `cooldown_period` | Monitors `5xx` responses. Trips open to protect upstreams, fast-failing requests with `503`. |

### Short-Circuiting & Overrides

| Filter | Parameters | Description |
| --- | --- | --- |
| `ReturnResponse` | `status`, `body` | Halts execution and immediately returns a mock/static response. (Omit upstream). |
| `StaticContent` | `base_directory` | Short-circuits the pipeline to serve static files from the local disk using native handlers. |
| `SetStatus` | `status` (int) | Overrides the final HTTP response status code returned to the client (100-599). |

---

## Complete Example Configuration

The following example demonstrates a standard r7 configuration, showcasing path routing, method restrictions, filter application, static serving, conditional journaling, resilient fallback routing, and active health checks.

```yaml
version: '{{git.rev.abbr}}'

# Global filters applied to all routes
filters:
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
      - RateLimit:
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

Defines the primary listening interfaces and ports for the proxy.

| Parameter | Type | Description |
| --- | --- | --- |
| `host` | String | The IP address or interface the primary proxy binds to (e.g., `0.0.0.0` for all interfaces). |
| `port` | Integer | The primary port the proxy listens on for incoming traffic. |

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
