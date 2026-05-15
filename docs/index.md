# r7 Gateway

A high-performance JVM gateway for routing, filtering, and observability with a deterministic execution model and minimal runtime overhead.

r7 sits between services and traffic sources, providing a stable and predictable execution layer for HTTP request handling, routing, and auditing.

---

## Designed for predictable systems

r7 is built for environments where performance consistency matters more than peak benchmarks:

- Stable low-latency request processing under load
- Predictable behavior under memory constraints
- Minimal garbage collection pressure in the hot path
- Full request visibility without blocking execution

---

## Core capabilities

- Composable routing engine (predicates + filters)
- High-throughput HTTP entrypoint (Undertow-based)
- Memory-mapped journaling for full request/response auditing
- Real-time operational dashboard
- Plugin system via JVM ServiceLoader (SPI)

---

## Deterministic execution model

r7 uses a straightforward synchronous request execution model for routing and filtering.

This avoids the complexity introduced by reactive or coroutine-based pipelines, where request flow is distributed across multiple execution contexts.

Benefits:

- Predictable request lifecycle from ingress to response
- Easier debugging with direct stack traces
- No reactive state management or callback chains
- Reduced cognitive overhead in failure analysis under load

---

## Performance under constraint

r7 is designed to maintain stable latency under constrained memory conditions where traditional gateways exhibit tail latency degradation.

See [Benchmarks](/benchmarks) for measured results under controlled memory and load conditions.

---

## Operational visibility

r7 includes a built-in dashboard for live system introspection:

- Route-level inspection
- Request and response journaling
- Error-level filtering and escalation
- Real-time traffic visibility

![r7 Dashboard Snapshot](assets/overview.png)

---

## Declarative configuration model

r7 is fully configured using a simple, self-documenting YAML model.

Routes, predicates, filters, and journaling behavior are defined declaratively and evaluated consistently at runtime without hidden conventions or external orchestration systems.

This allows:

- Fast onboarding without framework-specific knowledge
- Explicit routing behavior that is easy to reason about
- Configuration that can be reviewed, versioned, and audited like code
- No runtime DSLs or embedded scripting required

---

## Example configuration

```yaml
routes:
  - id: static-content
    match:
      - Method:
          include: [GET, POST]
    upstream:
      targets:
        - url: http://localhost:1111
    filters:
      - CorrelationIdHeader
      - RateLimiter:
          capacity: 50000
          refill_period: PT1s
    journal:
      request:
        level: HEADERS
      response:
        level: FULL
````

![r7 Dashboard Snapshot - route view](assets/route_view.png)

---

## Architecture overview

r7 separates execution into two planes:

### Data plane

Handles request routing and filtering in a low-allocation hot path executed per request.

### Visibility plane

Asynchronous journaling and telemetry processing decoupled from request execution, ensuring observability does not impact latency.

---

## Extensibility

Custom behavior can be added via JVM ServiceLoader (SPI):

* Authentication and JWT validation
* Rate limiting strategies
* Request enrichment
* Audit and telemetry adapters

See [Extensibility](/extensibility) page for documentation.

---

## Performance characteristics

r7 is designed for stable behavior under constrained environments, where traditional gateways begin to exhibit tail latency degradation due to memory pressure.

---

## Technical profile

* Platform: Java 25+
* Server: Undertow (XNIO)
* Design: JVM-native, high-throughput gateway architecture

