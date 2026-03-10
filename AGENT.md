# r7 Proxy: Agent Context & Rules

## Core Philosophy: Mechanical Sympathy
- **Zero-Allocation**: Avoid `new` in the hot path. Use `CharSequence`, `ByteBuffer`, and reusable objects.
- **Off-Heap Primary**: Use `MappedByteBuffer` for sharded journals to bypass the GC.
- **Direct I/O**: Prioritize `DirectByteBuffer` for socket-to-journal transfers.

## Architectural Constraints
- **Sharded Journals**: Audit logs are sharded by `requestId` hash to prevent lock contention.
- **Binary First**: All journaling is binary-encoded via `Marker` constants (BEGIN=0x01, BODY=0x02, END=0x03).
- **Sidecar Processing**: The `r7Tailer` handles the conversion from binary to JSON; the Gateway does NOT write JSON.

## Target Metrics
- **Throughput**: 100k+ req/s.
- **Latency**: P99 under 1ms.
- **Buffer Target**: 8KB (to optimize for CPU L1/L2 cache).

## Style Guide
- Use LaTeX for complex math/science equations.
- Maintain strict separation between the `api` (contract) and `core` (engine).