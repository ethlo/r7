# r7 Proxy Filters Reference

This document outlines the available filters for the r7 proxy, their behaviors, and their configuration parameters.

## AddRequestHeader

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

## AddResponseHeader

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

## CircuitBreaker

Monitors upstream responses and temporarily blocks routing to the target if a specified threshold of `5xx` server errors is reached. Fast-fails with `503 Service Unavailable` while open.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `failure_threshold` | Integer | Yes | The number of consecutive `5xx` failures required to open the circuit. |
| `cooldown_period` | Duration | Yes | The time to wait before transitioning to a half-open state to probe upstream health. |

**Example:**

```yaml
filters:
  - CircuitBreaker:
      failure_threshold: 10
      cooldown_period: PT12s

```

## CorrelationIdHeader

Automatically injects the gateway's internal request ID into both the upstream request and the client response using the `X-Correlation-Id` header.

*This filter requires no configuration parameters.*

**Example:**

```yaml
filters:
  - CorrelationIdHeader

```

## Cors

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

## InjectBasicAuth

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

## RateLimiter

Provides token-bucket rate limiting based on the client's IP address or a custom rate-limit key. Requests exceeding the limit are rejected with `429 Too Many Requests`. Injects `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Retry-After` headers.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `capacity` | Long | Yes | Maximum number of tokens the bucket can hold. |
| `refill_tokens` | Long | Yes | Number of tokens added to the bucket per refill period. |
| `refill_period` | Duration | Yes | The time interval for the token refill. |
| `max_buckets` | Long | No | Maximum number of buckets to track. Defaults to `10000`. |
| `max_bucket_ttl` | Duration | No | Time-to-live for idle buckets. Defaults to `max(refillPeriod * 10, 30s)`. |

**Example:**

```yaml
filters:
  - RateLimiter:
      capacity: 5
      refill_tokens: 1
      refill_period: PT2s

```

## RemoveRequestHeader

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

## RemoveResponseHeader

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

## RequestSize

Evaluates the `Content-Length` header of incoming requests. Rejects payloads exceeding the configured limit with `413 Payload Too Large`.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `max_bytes` | Long | Yes | The maximum allowed request size in bytes (must be > 0). |

**Example:**

```yaml
filters:
  - RequestSize:
      max_bytes: 10485760 # 10MB

```

## RequireAuthorizationHeader

Validates that incoming requests contain an `Authorization` header starting with either `Bearer ` or `Basic `. Rejects requests with a `401 Unauthorized` status if the header is missing or invalid.

*This filter requires no configuration parameters.*

**Example:**

```yaml
filters:
  - RequireAuthorizationHeader

```

## RewritePath

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

## SetStatus

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

## StripCacheHeaders

Strips cache validation headers (`If-Modified-Since`, `If-None-Match`) and injects strict no-cache directives (`Cache-Control: no-cache`, `Pragma: no-cache`) into the upstream request.

*This filter requires no configuration parameters.*

**Example:**

```yaml
filters:
  - StripCacheHeaders

```

## StripPathPrefix

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

## TemplateRedirect

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