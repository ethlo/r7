# Configuration Overview

The r7 proxy is configured using a declarative YAML file. This configuration defines how incoming requests are matched, modified by filters, routed to upstream targets, and logged by the journaling system.

The configuration supports environment variable interpolation (e.g., `${ENV_VAR:default_value}`) and template variables (e.g., `{{git.rev.abbr}}`), allowing you to use a single configuration structure across multiple environments.

## Core Concepts

* **Global Filters:** Applied to every request passing through the proxy, ensuring baseline behaviors like metric collection or correlation ID injection.
* **Routes:** The core mapping logic. Each route requires a unique `id`, a `match` condition (like path prefixes or HTTP methods), and an `upstream` target.
* **Route Filters:** Specific mutations or traffic controls (like Rate Limiting, Circuit Breaking, or Header modification) applied only when a specific route is matched.
* **Journaling:** Granular control over what is logged. You can define base logging levels (e.g., `NONE`, `METADATA`, `HEADERS`, `FULL`) and override these levels based on specific HTTP status codes.

## Example Configuration

The following example demonstrates a standard r7 configuration, showcasing path routing, method restrictions, filter application, and conditional journaling.

```yaml
# Supports template and environment variable interpolation
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
          refill_period: PT2s
      - CircuitBreaker:
          failure_threshold: 10
          cooldown_period: PT12s
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