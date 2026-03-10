# Performance & The Memory Squeeze

Benchmarking API gateways often devolves into synthetic "apples-to-oranges" comparisons. Instead of claiming arbitrary high scores, we benchmarked r7 to demonstrate its behavior under severe memory constraints.

The goal: **Sustain 20,000 Requests Per Second (RPS) with a 12-thread worker pool, while progressively shrinking the container's RAM.**

**The Test Vector:**
`wrk2 -t12 -c200 -d30s -R20000 -H "Authorization: Bearer <token>" http://localhost:9999/hello --latency`

## The Results

| Container Size | Median Latency (P50) | Tail Latency (P99) | Max Latency | Result |
|----------------|----------------------|--------------------|-------------|--------|
| **990 MB** | 1.10 ms              | 6.54 ms            | 21.25 ms    | Stable |
| **300 MB** | 1.05 ms              | 5.13 ms            | 32.00 ms    | Stable |
| **200 MB** | 1.02 ms              | 78.08 ms           | 176.38 ms   | GC Squeeze |

## Analysis: Finding the Floor
The data reveals exactly how r7 interacts with the HotSpot JVM:

1. **The P50 Consistency:** Across all three memory tiers, the median latency remained locked at `~1.05ms`. This proves the core routing engine and zero-locking hot path execute instantly when the CPU is unimpeded.
2. **The 300MB Sweet Spot:** Dropping from 1GB to 300MB had almost zero impact on tail latencies. r7 is highly memory-efficient and can run comfortably in heavily constrained cloud environments.
3. **The 200MB GC Squeeze:** At 200MB, the container reached its physical limit. Between thread stacks, XNIO direct buffers, and the heap, the JVM ran out of breathing room. To survive the 20k RPS barrage without OOMing, the Garbage Collector triggered frequent "Stop-The-World" pauses, directly causing the P99 latency to spike to 78ms.

By understanding these boundaries, infrastructure engineers can accurately provision r7 for Envoy-tier throughput using standard Kubernetes resource limits (`requests: 250Mi`, `limits: 350Mi`).