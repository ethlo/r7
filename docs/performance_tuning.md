# Performance tuning

r7's defaults favour a small, predictable footprint over peak throughput. Most deployments never
need to change them. When you do, each setting below trades one resource for another, and the
right side of that trade depends on where r7 runs. Each entry says what to tune, when it helps,
what it costs, and how to check the result on your own hardware.

Numbers on this page were measured on one machine (20 cores, JDK 25, ZGC, an nginx backend on
the same host) and are there to show direction and size, not to promise throughput. Measure
before and after with `benchmark/run.sh` (see [Benchmarks](benchmarks.md)); only the
passthrough-relative deltas carry over to other hardware.

---

## Journal storage: `shard_count`, `pre_fault` and where segments live

### Symptom

With journaling on at high request rates, throughput falls well below what the same gateway
does with journaling off. p99 latency climbs into tens or hundreds of milliseconds, and it often
gets worse the longer the load lasts. Header-heavy traffic at `HEADERS` or `FULL` shows it
first. It is worst on servers that run a thread per connection, such as the experimental
Helidon gateway, and grows with the number of open connections.

### Why it happens

The journal is split into `shard_count` shards (default `1`). Each request goes to one shard,
chosen by its request id. Each shard writes into one memory-mapped segment at a time, and
writers take turns: encoding happens in parallel, but placing each entry in the segment is done
one writer at a time per shard. That step is a short copy, cheap unless something stalls it.
Two things do:

- **Page faults.** Segments are mapped lazily: the first write to each 4 KB page asks the
  kernel for the page, and every other writer to that shard waits meanwhile. At a few hundred
  MB/s of journal that is tens of thousands of stalls a second.
- **Writeback throttling.** On a disk, once enough journal pages are dirty, the kernel makes
  the writing thread wait for the disk to catch up. That happens at the same moment, and it
  builds up as dirty pages accumulate, which is why the slowdown grows over a run.

How much a stall costs depends on how many writers are waiting. Undertow writes from a small,
fixed set of threads (one I/O thread per core by default). A thread-per-connection server can
have a writer for every open connection, all queued on the same shard.

### The settings

| Setting | Effect | Cost |
|---|---|---|
| `storage.shard_count` | Fewer writers per shard: less queueing and a shorter tail. Must be a power of two. | More segment files open at once. No memory cost unless `pre_fault` is on. |
| `storage.pre_fault` | Touches each segment's pages while warming it, off the request path, so writes never fault. | Each shard's warmed segments are charged to memory at once: the active segment plus 4 queued plus 1 being warmed. That is about 1.2 GB per shard at the default `shard_size` of 200 MB. They also take real disk space immediately rather than as written. |
| `storage.shard_size` | Scales what `pre_fault` charges per shard (about 6 × `shard_size`). Smaller segments also rotate more often. | Each rotation does a little work under the shard's lock, so very small segments add their own stalls. |
| Journal medium | Where `work_dir` lives: a disk-backed volume or tmpfs. | tmpfs is memory that cannot be reclaimed. See below. |

### What they did, measured

On tmpfs, which removes disk writeback and isolates the writer lock. `HEADERS` journaling,
header-heavy workload, 200 connections at saturation:

| Storage | Undertow | Helidon (experimental) |
|---|---|---|
| defaults (1 shard) | 64k req/s, p99 14 ms | 41k req/s, p99 20 ms |
| 4 shards | 81k req/s, p99 12 ms | 81k req/s, p99 22 ms |
| 1 shard, `pre_fault: true` | 89k req/s, p99 12 ms | 93k req/s, p99 55 ms |
| 4 shards, `pre_fault: true` | 88k req/s, p99 12 ms | 95k req/s, p99 17 ms |

On a local disk with spare memory, `pre_fault: true` raised throughput by 15–25% on both
servers.

**In a container with a memory limit (`-m 8g`), `pre_fault` did the opposite.** Throughput fell
30–40% compared with leaving it off: the journal's page cache fills the container's limit within
seconds at full load either way, and the pre-faulted pages crowd out what the kernel can
reclaim. Measured with cgroup v2 `memory.stat`, journals on a disk volume:

| Storage | Mapped journal at idle | Working set at idle | Throughput under load |
|---|---|---|---|
| 1 shard | ~190 MB | ~340 MB | 48k req/s |
| 1 shard, `pre_fault: true` | ~1.4 GB | ~1.6 GB | 34k req/s |
| 4 shards | ~180 MB | ~340 MB | 48k req/s |
| 4 shards, `pre_fault: true` | ~5.0 GB | ~5.3 GB | 30k req/s |

### Recommendations

**In a container or anywhere memory is limited (the common case):**

- **Leave `pre_fault` off.** Its memory is charged up front, and under a limit it costs more
  than it saves.
- **Set `shard_count: 4` when running a thread-per-connection server, or many concurrent
  connections.** Without `pre_fault`, extra shards cost no memory.
- **Give the container headroom for page cache, not just heap.** The journal is written through
  the page cache, which counts toward the container's memory limit. Up to the limit, that cache
  is reclaimable. How much stays dirty depends on how fast the disk and your tailer keep up.

**On a host or VM with memory to spare:**

- **`pre_fault: true` with `shard_count: 4`** gave the best throughput and tail of any setting
  measured. Budget about 6 × `shard_size` × `shard_count` of memory and disk for it: about
  4.8 GB at the defaults. Lower `shard_size` to shrink that.

**Never put journals on tmpfs** (`emptyDir: {medium: Memory}`, `--tmpfs`, `/dev/shm`) unless
something deletes sealed segments as fast as they are written. tmpfs pages cannot be reclaimed
and count fully against the container's limit, and r7 does not delete its own journals. A
container with journals on tmpfs was OOM-killed within 30 seconds of full load in these tests.

### Reading the numbers in a container

The kernel's per-container memory counters (cgroup v2 `memory.stat`) break the usage down:

- **`file_mapped` and `file`:** journal segments. Mapped segments count as active, so they show
  up in the working set Kubernetes and `docker stats` report, even though most of it can be
  reclaimed.
- **`shmem`:** with ZGC, this is mostly the Java heap (ZGC backs it with shared memory), not
  journals, unless the journals are on tmpfs.
- **`file_dirty`:** journal data not yet written to disk. If it stays high, the disk is the
  bottleneck, not r7.

A working set near the limit is normal under journaling load and is mostly page cache. An OOM
kill is not: check `shmem` for journals on tmpfs, and the heap settings.

### How to check yours

```bash
benchmark/run.sh --scenario journal --journal-levels HEADERS,FULL --workload headers --repeat 3
```

Run it once with your current `storage` settings and once with the change, against the same
backend. Compare the `vs r7` column, and the p99 at the rate you actually serve, not only
saturation throughput. In a container, also watch `memory.stat` while it runs.
