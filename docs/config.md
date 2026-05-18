# r7 Proxy Reference

## Routing Configuration

The r7 proxy is configured using a declarative YAML file called `routes.yaml`. This configuration defines how incoming requests are matched, modified by filters, routed to upstream targets, and logged by the journaling system.
The configuration supports environment variable interpolation (e.g., `${ENV_VAR:default_value}`), allowing you to use a single configuration structure across multiple environments.

### Core Concepts

* **Global Filters:** Applied to every request passing through the proxy, ensuring baseline behaviors like metric collection or correlation ID injection.
* **Routes:** The core mapping logic. Each route requires a unique `id`, a `match` condition (like path prefixes or HTTP methods), and an `upstream` target.
* **Route Filters:** Specific mutations or traffic controls (like Rate Limiting, Circuit Breaking, or Header modification) applied only when a specific route is matched.
* **Journaling:** Granular control over what is logged. You can define base logging levels (e.g., `NONE`, `METADATA`, `HEADERS`, `FULL`) and override these levels based on specific HTTP status codes.

### Example Configuration

The following example demonstrates a standard r7 configuration, showcasing path routing, method restrictions, filter application, and conditional journaling.

Sample `routing.yaml`

```yaml
version: '{{git.rev.abbr}}'

# Global filters applied to all routes
filters:
  - SimpleMetrics
  - CorrelationIdHeader

routes:
  # 1. Simple routing with environment variable fallback
  - id: forward-status
    upstream:
      targets:
        - url: http://localhost:18888
    match:
      - PathStartsWith:
          prefix: ${SECRET_URL:/status} # Uses SECRET_URL or defaults to /status

  # 2. Routing with authorization enforcement
  - id: reject-benchmark
    upstream:
      targets:
        - url: http://localhost:1111
    match:
      - PathStartsWith:
          prefix: /benchmark
    filters:
      - RequireAuthorizationHeader

  # 3. Complex routing with method matching, configured filters, and conditional journaling
  - id: undertow-internal-backend
    match:
      - PathStartsWith:
          prefix: /hello
      - Method:
          include:
            - GET
            - POST
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
          value: Ethlo R7
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

  # 4. Routing with path stripping
  - id: home-assistant
    upstream:
      targets:
        - url: http://192.168.50.103:8123
    match:
        - PathStartsWith:
          prefix: /ha
    filters:
      # Strips the "/ha" prefix before forwarding to the upstream target
      - StripPathPrefix:
          parts: 1
    journal:
      request:
        level: METADATA
        status_overrides:
          5xx: HEADERS
      response:
        level: HEADERS

```

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

### PathStartsWith

Matches if the request path begins with a specific string prefix.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `prefix` | String | Yes | The exact string prefix to match against the request path. |

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

### AddRequestHeader

Adds or overrides an HTTP header before the request is forwarded to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |
| `override` | Boolean | No | If `true`, overwrites existing headers with the same name. If `false` or omitted, appends the value. |

**Example:**

```yaml
filters:
  - AddRequestHeader:
      name: X-Custom-Header
      value: my-custom-value
      override: true
```

### AddResponseHeader

Adds or overrides an HTTP header on the client response before returning it to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |
| `override` | Boolean | No | If `true`, overwrites existing headers with the same name. If `false` or omitted, appends the value. |

**Example:**

```yaml
filters:
  - AddResponseHeader:
      name: X-Powered-By
      value: Ethlo R7
```

### CircuitBreaker

Monitors upstream responses and temporarily blocks routing to the target if a specified threshold of `5xx` server errors is reached. Fast-fails with `503 Service Unavailable` while open.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `failure_threshold` | Integer | Yes | The number of consecutive `5xx` failures required to open the circuit. |
| `cooldown_period` | Duration | Yes | The time to wait (e.g., `12s`) before transitioning to a half-open state to probe upstream health. |

**Example:**

```yaml
filters:
  - CircuitBreaker:
      failure_threshold: 10
      cooldown_period: 12s
```

### CorrelationIdHeader

Automatically injects the gateway's internal request ID into both the upstream request and the client response using the `X-Correlation-Id` header.

*This filter requires no configuration parameters.*

**Example:**

```yaml
filters:
  - CorrelationIdHeader
```

### Cors

Handles Cross-Origin Resource Sharing (CORS). Intercepts `OPTIONS` preflight requests returning `204 No Content`, and decorates standard responses with appropriate Access-Control headers.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `allowed_origins` | String | Yes | Comma-separated list of permitted origins, or `*` to allow any origin. |
| `allowed_methods` | String | No | Value mapped to `Access-Control-Allow-Methods`. |
| `allowed_headers` | String | No | Value mapped to `Access-Control-Allow-Headers`. |
| `max_age` | String | No | Value mapped to `Access-Control-Max-Age`. |
| `allow_credentials` | Boolean | No | If `true`, sets `Access-Control-Allow-Credentials` to `true`. |

**Example:**

```yaml
filters:
  - Cors:
      allowed_origins: "https://example.com, https://app.example.com"
      allowed_methods: "GET, POST, PUT, DELETE, OPTIONS"
      allowed_headers: "Authorization, Content-Type"
      max_age: "3600"
      allow_credentials: true
```

### InjectBasicAuth

Generates a Base64 encoded Basic Authentication string and injects it into the `Authorization` header of the upstream request.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `username` | String | Yes | The authentication username. |
| `password` | String | Yes | The authentication password. |

**Example:**

```yaml
filters:
  - InjectBasicAuth:
      username: admin
      password: supersecretpassword
```

### RateLimiter

Provides token-bucket rate limiting based on the client's IP address or a custom rate-limit key. Requests exceeding the limit are rejected with `429 Too Many Requests`. Injects `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Retry-After` headers.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `capacity` | Long | Yes | Maximum number of tokens the bucket can hold. |
| `refill_tokens` | Long | Yes | Number of tokens added to the bucket per refill period. |
| `refill_period` | Duration | Yes | The time interval (e.g., `2s`) for the token refill. |
| `max_buckets` | Long | No | Maximum number of buckets to track. Defaults to `10000`. |
| `max_bucket_ttl` | Duration | No | Time-to-live for idle buckets (e.g., `30s`). Defaults to `max(refillPeriod * 10, 30s)`. |

**Example:**

```yaml
filters:
  - RateLimiter:
      capacity: 5
      refill_tokens: 1
      refill_period: 2s
```

### RemoveRequestHeader

Strips a specified HTTP header from the client request before it is forwarded to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

**Example:**

```yaml
filters:
  - RemoveRequestHeader:
      name: X-Forwarded-Host
```

### RemoveResponseHeader

Strips a specified HTTP header from the upstream response before it is returned to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

**Example:**

```yaml
filters:
  - RemoveResponseHeader:
      name: Server
```

### RequestSize

Evaluates the `Content-Length` header of incoming requests. Rejects payloads exceeding the configured limit with `413 Payload Too Large`.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `max_bytes` | Size | Yes | The maximum allowed request size formatted with a size suffix (e.g., `10MB`). |

**Example:**

```yaml
filters:
  - RequestSize:
      max_size: 10MB
```

### RequireAuthorizationHeader

Validates that incoming requests contain an `Authorization` header starting with either `Bearer ` or `Basic `. Rejects requests with a `401 Unauthorized` status if the header is missing or invalid.

*This filter requires no configuration parameters.*

**Example:**

```yaml
filters:
  - RequireAuthorizationHeader
```

### RewritePath

Rewrites the upstream request path using regular expressions before forwarding it to the target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `regexp` | String | Yes | The regular expression pattern to match against the request path. |
| `replacement` | String | Yes | The replacement string applied to the matched path. |

**Example:**

```yaml
filters:
  - RewritePath:
      regexp: "^/api/v1/(.*)"
      replacement: "/$1"
```

### SetStatus

Overrides the HTTP response status code returned to the client, regardless of the upstream target's response.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The valid HTTP status code (100-599) to enforce on the response. |

**Example:**

```yaml
filters:
  - SetStatus:
      status: 404
```

### StripCacheHeaders

Strips cache validation headers (`If-Modified-Since`, `If-None-Match`) and injects strict no-cache directives (`Cache-Control: no-cache`, `Pragma: no-cache`) into the upstream request.

*This filter requires no configuration parameters.*

**Example:**

```yaml
filters:
  - StripCacheHeaders
```

### StripPathPrefix

Removes a specified number of structural path segments from the beginning of the request path before it is routed upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `parts` | Integer | Yes | The number of path segments (separated by `/`) to strip. Must be greater than 0. |

**Example:**

```yaml
filters:
  - StripPathPrefix:
      parts: 2
```

### TemplateRedirect

Intercepts the request and immediately issues an HTTP redirect (3xx) based on a regex match of the path and a substitution template. Supports capturing regex groups using `{{1}}` or `{{var}}` syntax.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `source` | String | Yes | The regular expression pattern to match against the request path. |
| `target` | String | Yes | The destination URL template. Regex capture groups can be referenced using `{{name}}` or `{{index}}`. |
| `status` | Integer | No | The HTTP redirect status code. Defaults to `302` (Found). |

**Example:**

```yaml
filters:
  - TemplateRedirect:
      source: "^/old-path/(.*)"
      target: "/new-path/{{1}}"
      status: 301
```