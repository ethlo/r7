# Venturi

**A focused, high-performance JVM gateway built for predictable throughput and low-impact visibility.**

It provides:

- A composable routing engine (predicates + filters)
- A low-allocation execution model
- Memory-mapped, append-only audit logging
- Pluggable tailers for structured export and ingestion
- JVM-native extensibility via ServiceLoader

Venturi is designed for stacks where throughput, observability, and control matter.

---

## Overview

Venturi provides a clean separation between:

- **API** – Stable interfaces for predicates, filters, requests, and attributes
- **Core** – The routing engine and execution pipeline
- **Audit** – High-throughput, low-allocation journaling
- **Server integration** – Undertow-based HTTP entrypoint

The system is intentionally narrow in scope: evaluate requests, apply predicates and filters, route efficiently, and optionally audit. It does not try to be an application framework.

---

## Design Goals

### Ease-of-use and
Keeping the API contract clean and focused allows for writing predicates and filters that can be evaluated and tested without ceremony. 

### Performance by design

- Extremely efficient access logging
- Predicate and filter singletons
- Minimal per-request object creation inside the core

The routing pipeline is designed to remain allocation-stable under sustained load.

---

### Composability

Routing is built from small building blocks:

- `GatewayPredicate`
- `GatewayFilter`
- Route definitions combining both

Predicates are simple boolean tests against a `GatewayRequest`. Filters can mutate or enrich request attributes.

Example:

```java
new Route(
    new And(
        new MethodPredicate("GET"),
        new HeaderPredicate("Authorization")
    ),
    new JwtFilter(...)
);
````

Predicates and filters are singletons and evaluated per request.

---

### Extensibility via Service Provider Interface (SPI)

Venturi stays on the JVM intentionally.

Predicates and filters can be provided via Java’s `ServiceLoader`, allowing plugins to be added without modifying the core.

This enables extensions such as:

* JWT validation (including JWK rotation strategies)
* Rate limiting
* Custom authentication mechanisms
* Request enrichment
* Audit adapters

The core remains focused; behavior is extended via modules.

---

## Full Audit Logging Without Runtime Penalty

Most gateways force a trade-off between visibility and performance.

Venturi’s audit module takes a different approach:

* Requests are written to a memory-mapped journal
* Writes are append-only and mechanically simple
* No per-request serialization in the hot path
* No blocking I/O during request handling

A separate **tailer** process owns the lifecycle of journal segments and is responsible for:

* Converting entries to JSON, plain text, MsgPack, or other formats
* Routing output to Vector, ClickHouse, S3-compatible storage, or other sinks
* Applying predicates (e.g., only errors, specific routes, sampling)

This separates:

* The **data plane** (fast request handling)
* The **visibility plane** (formatting, export, ingestion)

The result is full audit capability with minimal impact on request latency.

---

## Shared mmap for Metrics and Throughput

The same memory-mapped approach can be used for:

* Throughput counters
* Status code metrics
* Route-level statistics
* Lightweight health signals

Because metrics are written to shared memory instead of being pushed through logging frameworks, they can be consumed by:

* Sidecar processes
* Vector
* Custom monitoring agents
* ClickHouse ingestion pipelines

This allows high-frequency metrics without adding allocation pressure or network overhead in the request path.

---

## Why Venturi

Venturi is useful when you need:

* A lightweight gateway core
* Full control over routing behavior
* Predictable performance characteristics
* Plugin-based extensibility
* JVM-native deployment
* Tight integration with existing Java systems
* High-throughput environments requiring full audit visibility

It is not intended to replace full-featured API gateways. It is intended for cases where you want a focused, inspectable, and tunable core.

---

## Technical Notes

* Java 25
* Undertow for HTTP integration
* G1-compatible allocation profile
* Emphasis on avoiding unnecessary intermediate objects
* Designed for sustained high request rates

---