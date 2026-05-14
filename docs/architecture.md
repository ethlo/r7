# Architecture



r7 achieves its throughput not by reinventing the wheel, but by aggressively optimizing how data flows between the network socket and the business logic.

## The Low-Allocation Hot Path
The most expensive operation in a Java-based network proxy isn't I/O; it is the Garbage Collector pausing the world to clean up millions of short-lived objects.

r7’s filter pipeline is built to respect the heap:
* **Array-Backed Execution:** Filter chains compile down to flat arrays, allowing the JVM to iterate via raw index loops rather than instantiating `Iterator` objects per request.
* **Stateful Attachments:** Request context (like Rate Limit keys and JWT Identities) is passed via a lock-free attachment registry rather than creating new wrapper objects at every layer.
* **Pre-Computed Cryptography:** Expensive operations, like SHA-256 Vault authentication, utilize `ThreadLocal` message digests to reuse cryptographic contexts across the Undertow worker pool.

## The Filter Ecosystem
Authentication, traffic mutation, and resiliency are handled by a strict pipeline of modular filters:
* **Security:** `RequireAuthorizationHeader`, `TokenAuth`, `JwtAuth`
* **Resiliency:** `RateLimiter` (Bucket4j/Caffeine), `CircuitBreaker`
* **Mutation:** `AddRequestHeader`, `RewritePath`, `StripPathPrefix`