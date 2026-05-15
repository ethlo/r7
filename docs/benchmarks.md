## Performance & Memory Behavior

API gateway benchmarks are often misleading due to synthetic comparisons and inconsistent configurations.  
This benchmark focuses on a controlled variable: memory pressure under sustained request load.

### Test Goal

Sustain **20,000 RPS** with a **12-thread worker pool**, while progressively reducing container memory limits to observe latency behavior under constrained runtime conditions.

### Test Configuration

```bash
wrk2 -t12 -c200 -d30s -R20000 \
-H "Authorization: Bearer <token>" \
http://localhost:9999/hello --latency
````

### Results

| Container Size | P50 Latency | P99 Latency | Max Latency | Result      |
| -------------- | ----------- | ----------- | ----------- | ----------- |
| 990 MB         | 1.10 ms     | 6.54 ms     | 21.25 ms    | Stable      |
| 300 MB         | 1.05 ms     | 5.13 ms     | 32.00 ms    | Stable      |
| 200 MB         | 1.02 ms     | 78.08 ms    | 176.38 ms   | GC pressure |

### Observations

* **Median latency stability:** P50 remains ~1.0–1.1ms across all tested memory configurations, indicating stable request-path performance under CPU-bound conditions.
* **300MB operating range:** Reducing memory from 990MB to 300MB shows no measurable degradation in tail latency under this workload.
* **200MB constraint threshold:** At 200MB, the runtime exhibits increased tail latency under sustained load, consistent with elevated garbage collection pressure and reduced JVM headroom.

### Interpretation

The data suggests r7 maintains consistent request-path performance under moderate to tight memory allocations, with degradation appearing only when container memory approaches JVM operational limits under sustained high RPS workloads.

This indicates that practical deployments should provision memory above the observed degradation threshold to avoid GC-induced tail latency spikes.
