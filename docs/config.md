# r7 Proxy Reference

## Server Configuration

The `server.yaml` file controls the foundational infrastructure of the r7 proxy. This includes network binding, HTTP limits, upstream connection pooling, and disk-backed storage configurations for journaling.

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

---

## Routing Configuration

The r7 proxy is configured using a declarative YAML file called `routes.yaml`. This configuration defines how incoming requests are matched, modified by filters, routed to upstream targets, and logged by the journaling system.

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

**Example of Nesting:**

```yaml
match:
  - and:
    - PathStartsWith:
        prefix: /secure/
    - or:
      - Method:
          include: 
          - POST
          - PUT
      - not:
          RemoteAddr:
            source: 10.0.0.0/8

```

### Cookie

Matches the incoming request based on the presence of a specific cookie, or validates its value against a regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie to check for. |
| `regexp` | String | No | A regex pattern. If provided, the cookie value must match. If omitted, acts as a pure presence check. |

### Host

Matches the incoming request against a list of allowed `Host` headers. It automatically handles matching with or without port numbers included in the header.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `hosts` | List of Strings | Yes | A list of acceptable hostnames. Must contain at least one element. |

### Method

Matches the HTTP method (e.g., `GET`, `POST`, `PUT`) of the incoming request.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `include` | List of Strings | Yes | A list of allowed HTTP methods. Must contain at least one element. |

### Path

Matches the incoming request against an exact, fully qualified URI path.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `path` | String | Yes | The exact URI string (e.g., `/_internal/health`) to match against the request path. |

### PathStartsWith

Matches if the request path begins with a specific string prefix.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `prefix` | String | Yes | The exact string prefix to match against the request path. |

### QueryParameter

Matches the incoming request based on the presence of a specific query parameter, or validates its value against a regular expression.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the query parameter. |
| `regexp` | String | No | A regex pattern. If provided, the parameter value must match. If omitted, acts as a pure presence check. |

### RegexPath

Matches the full request URI against a defined regular expression pattern.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `regexp` | String | Yes | A valid Java regular expression pattern to evaluate against the URI. |

### RemoteAddr

Matches the client's IP address against a specific IP or a CIDR subnet block. It supports both IPv4 and IPv6 network definitions.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `source` | String | Yes | The IP address or CIDR notation (e.g., `192.168.1.5` or `10.0.0.0/24`) to match against the client's remote address. |

---

## Filters

This document outlines the available filters for the r7 proxy, their behaviors, and their configuration parameters.

### AddQueryParameter

Appends a new query parameter to the request before forwarding to the upstream target. Handles multi-value parameters securely.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the query parameter to add. |
| `value` | String | Yes | The value of the query parameter. |

### AddRequestCookie

Injects a new cookie directly into the `Cookie` header of the incoming request before it is routed upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie. |
| `value` | String | Yes | The value of the cookie. |

### AddRequestHeader

Adds or overrides an HTTP header before the request is forwarded to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |
| `override` | Boolean | No | If `true`, overwrites existing headers with the same name. If `false` or omitted, appends the value. |

### AddResponseCookie

Injects a new `Set-Cookie` header into the response returned to the client, complete with necessary security metadata.

| Parameter    | Type     | Required | Description |
|--------------|----------| --- | --- |
| `name`       | String   | Yes | The name of the cookie. |
| `value`      | String   | Yes | The value of the cookie. |
| `domain`     | String   | No | The domain scope for the cookie. |
| `path`       | String   | No | The path scope for the cookie. |
| `max_age`    | Duration | No | The time-to-live for the cookie. |
| `secure`     | Boolean  | No | Requires HTTPS. Defaults to `true` if omitted. |
| `http_only`  | Boolean  | No | Prevents client-side script access. Defaults to `true` if omitted. |
| `same_site` | String   | No | Cross-site request forgery protection (`Strict`, `Lax`, `None`). Defaults to `Lax`. |

### AddResponseHeader

Adds or overrides an HTTP header on the client response before returning it to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |
| `override` | Boolean | No | If `true`, overwrites existing headers with the same name. If `false` or omitted, appends the value. |

### CircuitBreaker

Monitors upstream responses and temporarily blocks routing to the target if a specified threshold of `5xx` server errors is reached. Fast-fails with `503 Service Unavailable` while open.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `failure_threshold` | Integer | Yes | The number of consecutive `5xx` failures required to open the circuit. |
| `cooldown_period` | Duration | Yes | The time to wait (e.g., `12s`) before transitioning to a half-open state to probe upstream health. |

### CorrelationIdHeader

Automatically injects the gateway's internal request ID into both the upstream request and the client response using the `X-Correlation-Id` header.

*This filter requires no configuration parameters.*

### Cors

Handles Cross-Origin Resource Sharing (CORS). Intercepts `OPTIONS` preflight requests returning `204 No Content`, and decorates standard responses with appropriate Access-Control headers.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `allowed_origins` | String | Yes | Comma-separated list of permitted origins, or `*` to allow any origin. |
| `allowed_methods` | String | No | Value mapped to `Access-Control-Allow-Methods`. |
| `allowed_headers` | String | No | Value mapped to `Access-Control-Allow-Headers`. |
| `max_age` | String | No | Value mapped to `Access-Control-Max-Age`. |
| `allow_credentials` | Boolean | No | If `true`, sets `Access-Control-Allow-Credentials` to `true`. |

### InjectBasicAuth

Generates a Base64 encoded Basic Authentication string and injects it into the `Authorization` header of the upstream request.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `username` | String | Yes | The authentication username. |
| `password` | String | Yes | The authentication password. |

### RateLimiter

Provides token-bucket rate limiting based on the client's IP address or a custom rate-limit key. Requests exceeding the limit are rejected with `429 Too Many Requests`. Injects `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Retry-After` headers.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `capacity` | Long | Yes | Maximum number of tokens the bucket can hold. |
| `refill_tokens` | Long | Yes | Number of tokens added to the bucket per refill period. |
| `refill_period` | Duration | Yes | The time interval (e.g., `2s`) for the token refill. |
| `max_buckets` | Long | No | Maximum number of buckets to track. Defaults to `10000`. |
| `max_bucket_ttl` | Duration | No | Time-to-live for idle buckets (e.g., `30s`). Defaults to `max(refillPeriod * 10, 30s)`. |

### RemoveQueryParameter

Strips a specific query parameter from the URL before forwarding it to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the query parameter to remove. |

### RemoveRequestCookie

Strips a specific cookie from the `Cookie` header before the request is routed upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the cookie to remove. |

### RemoveRequestHeader

Strips a specified HTTP header from the client request before it is forwarded to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

### RemoveResponseHeader

Strips a specified HTTP header from the upstream response before it is returned to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

### RequestSize

Evaluates the `Content-Length` header of incoming requests. Rejects payloads exceeding the configured limit with `413 Payload Too Large`.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `max_size` | Size | Yes | The maximum allowed request size formatted with a size suffix (e.g., `10MB`). |

### RequireAuthorizationHeader

Validates that incoming requests contain an `Authorization` header starting with either `Bearer ` or `Basic `. Rejects requests with a `401 Unauthorized` status if the header is missing or invalid.

*This filter requires no configuration parameters.*

### ReturnResponse

Short-circuits the routing pipeline, halting execution and immediately returning a mock or static response to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The HTTP status code to return. |
| `body` | String | Yes | The plain text or JSON payload to return in the response body. |

### RewritePath

Rewrites the upstream request path using regular expressions before forwarding it to the target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `regexp` | String | Yes | The regular expression pattern to match against the request path. |
| `replacement` | String | Yes | The replacement string applied to the matched path. |

### SetStatus

Overrides the HTTP response status code returned to the client, regardless of the upstream target's response.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The valid HTTP status code (100-599) to enforce on the response. |

### StaticContent

Short-circuits the pipeline to serve static files directly from the disk using a high-performance native handler. When used, the `StripPathPrefix` filter must run first to properly map the incoming URI relative to the base directory.

| Parameter        | Type | Required | Description |
|------------------| --- | --- | --- |
| `base_directory` | String | Yes | The absolute physical path on the disk (e.g., `/var/www/html/`) containing the static assets. |

### StripCacheHeaders

Strips cache validation headers (`If-Modified-Since`, `If-None-Match`) and injects strict no-cache directives (`Cache-Control: no-cache`, `Pragma: no-cache`) into the upstream request.

*This filter requires no configuration parameters.*

### StripPathPrefix

Removes a specified number of structural path segments from the beginning of the request path before it is routed upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `parts` | Integer | Yes | The number of path segments (separated by `/`) to strip. Must be greater than 0. |

### TemplateRedirect

Intercepts the request and immediately issues an HTTP redirect (3xx) based on a regex match of the path and a substitution template. Supports capturing regex groups using `{{1}}` or `{{var}}` syntax.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `source` | String | Yes | The regular expression pattern to match against the request path. |
| `target` | String | Yes | The destination URL template. Regex capture groups can be referenced using `{{name}}` or `{{index}}`. |
| `status` | Integer | No | The HTTP redirect status code. Defaults to `302` (Found). |

---

## Complete Example Configuration

The following example demonstrates a standard r7 configuration, showcasing path routing, method restrictions, filter application, static serving, conditional journaling, resilient fallback routing, and active health checks.

Sample `routes.yaml`

```yaml
version: '{{git.rev.abbr}}'

# Global filters applied to all routes
filters:
  - SimpleMetrics
  - CorrelationIdHeader

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
      - PathStartsWith:
          prefix: /assets/
    filters:
      - StripPathPrefix:
          parts: 1
      - StaticContent:
          baseDirectory: /var/www/html/
    upstream: null

  # Mock response serving (Short-circuits upstream phase)
  - id: stubbed-api
    match:
      - PathStartsWith:
          prefix: /api/v1/beta/
    filters:
      - ReturnResponse:
          status: 418
          body: "Beta API offline"
    upstream: null

  # Full option upstream config with active health checking, limits, and strategy
  - id: search-api
    match:
      - PathStartsWith:
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
        # override: FORCE_DOWN # Uncomment to manually force all targets in this pool up or down
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
      - PathStartsWith:
          prefix: /hello
      - Method:
          include:
            - GET
            - POST
      - QueryParameter:
          name: tenant_id
          regexp: "^[A-Za-z0-9]+$"
    upstream:
      targets:
        - url: http://localhost:1111
    filters:
      # Filters can be declared with specific configuration arguments
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
      - CorrelationIdHeader
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