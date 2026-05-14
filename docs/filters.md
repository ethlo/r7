# r7 Proxy Filters Reference

This document outlines the available filters for the r7 proxy, their behaviors, and their configuration parameters.

## AddRequestHeader

Adds or overrides an HTTP header before the request is forwarded to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |
| `override` | Boolean | No | If `true`, overwrites existing headers with the same name. If `false` or omitted, appends the value. |

## AddResponseHeader

Adds or overrides an HTTP header on the client response before returning it to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The name of the HTTP header. |
| `value` | String | Yes | The value to assign to the header. |
| `override` | Boolean | No | If `true`, overwrites existing headers with the same name. If `false` or omitted, appends the value. |

## CircuitBreaker

Monitors upstream responses and temporarily blocks routing to the target if a specified threshold of `5xx` server errors is reached. Fast-fails with `503 Service Unavailable` while open.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `failureThreshold` | Integer | Yes | The number of consecutive `5xx` failures required to open the circuit. |
| `cooldownPeriod` | Duration | Yes | The time to wait before transitioning to a half-open state to probe upstream health. |

## CorrelationIdHeader

Automatically injects the gateway's internal request ID into both the upstream request and the client response using the `X-Correlation-Id` header.

*This filter requires no configuration parameters.*

## Cors

Handles Cross-Origin Resource Sharing (CORS). Intercepts `OPTIONS` preflight requests returning `204 No Content`, and decorates standard responses with appropriate Access-Control headers.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `allowedOrigins` | String | Yes | Comma-separated list of permitted origins, or `*` to allow any origin. |
| `allowedMethods` | String | No | Value mapped to `Access-Control-Allow-Methods`. |
| `allowedHeaders` | String | No | Value mapped to `Access-Control-Allow-Headers`. |
| `maxAge` | String | No | Value mapped to `Access-Control-Max-Age`. |
| `allowCredentials` | Boolean | No | If `true`, sets `Access-Control-Allow-Credentials` to `true`. |

## InjectBasicAuth

Generates a Base64 encoded Basic Authentication string and injects it into the `Authorization` header of the upstream request.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `username` | String | Yes | The authentication username. |
| `password` | String | Yes | The authentication password. |

## RateLimiter

Provides token-bucket rate limiting based on the client's IP address or a custom rate-limit key. Requests exceeding the limit are rejected with `429 Too Many Requests`. Injects `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Retry-After` headers.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `capacity` | Long | Yes | Maximum number of tokens the bucket can hold. |
| `refillTokens` | Long | Yes | Number of tokens added to the bucket per refill period. |
| `refillPeriod` | Duration | Yes | The time interval for the token refill. |
| `maxBuckets` | Long | No | Maximum number of buckets to track. Defaults to `10000`. |
| `maxBucketTTL` | Duration | No | Time-to-live for idle buckets. Defaults to `max(refillPeriod * 10, 30s)`. |

## RemoveRequestHeader

Strips a specified HTTP header from the client request before it is forwarded to the upstream target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

## RemoveResponseHeader

Strips a specified HTTP header from the upstream response before it is returned to the client.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | String | Yes | The exact name of the header to remove. |

## RequestSize

Evaluates the `Content-Length` header of incoming requests. Rejects payloads exceeding the configured limit with `413 Payload Too Large`.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `maxBytes` | Long | Yes | The maximum allowed request size in bytes (must be > 0). |

## RequireAuthorizationHeader

Validates that incoming requests contain an `Authorization` header starting with either `Bearer ` or `Basic `. Rejects requests with a `401 Unauthorized` status if the header is missing or invalid.

*This filter requires no configuration parameters.*

## RewritePath

Rewrites the upstream request path using regular expressions before forwarding it to the target.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `regexp` | String | Yes | The regular expression pattern to match against the request path. |
| `replacement` | String | Yes | The replacement string applied to the matched path. |

## SetStatus

Overrides the HTTP response status code returned to the client, regardless of the upstream target's response.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `status` | Integer | Yes | The valid HTTP status code (100-599) to enforce on the response. |

## StripCacheHeaders

Strips cache validation headers (`If-Modified-Since`, `If-None-Match`) and injects strict no-cache directives (`Cache-Control: no-cache`, `Pragma: no-cache`) into the upstream request.

*This filter requires no configuration parameters.*

## StripPathPrefix

Removes a specified number of structural path segments from the beginning of the request path before it is routed upstream.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `parts` | Integer | Yes | The number of path segments (separated by `/`) to strip. Must be greater than 0. |

## TemplateRedirect

Intercepts the request and immediately issues an HTTP redirect (3xx) based on a regex match of the path and a substitution template. Supports capturing regex groups using `{{1}}` or `{{var}}` syntax.

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| `source` | String | Yes | The regular expression pattern to match against the request path. |
| `target` | String | Yes | The destination URL template. Regex capture groups can be referenced using `{{name}}` or `{{index}}`. |
| `status` | Integer | No | The HTTP redirect status code. Defaults to `302` (Found). |