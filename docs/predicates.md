# r7 Proxy Predicates Reference

Predicates define the matching conditions that determine whether an incoming request should be routed to a specific upstream target. A route is only executed if its predicates evaluate to true.

## Logical Meta-Predicates

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

---

## Available Predicates

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