# Performance tuning

r7's defaults aim for high throughput, a short tail and a small footprint at the same time. Most
deployments never need to change them. When you do, each setting below trades one resource for another, and the
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
first. It grows with the number of open connections, since every connection has a thread of its
own that may be writing.

### Why it happens

The journal is split into `shard_count` shards (by default one per CPU the JVM sees, so a
container's CPU limit counts, rounded down to a power of two, from 2 to 16). Each request goes to one shard,
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

How much a stall costs depends on how many writers are waiting. The gateway runs each
connection on a virtual thread of its own, so there can be a writer for every open connection,
all queued on the same shard.

### What r7 does about it: fault-ahead

On Linux 5.14 and later, r7 handles page faults without any configuration. A background thread
per shard keeps the next 8 MB past the write position faulted in, using
`madvise(MADV_POPULATE_WRITE)`, so writers almost never fault. That costs about 8 MB of memory
per shard beyond what has been written, and nothing up front. The call prepares pages without
writing to them, so it never touches journal data.

Fault-ahead switches itself off where it can't work: on other operating systems, on older
kernels, or when `pre_fault` is on. Writers then fault as before. It uses the JDK's native
interface, which the gateway jars allow in their manifest (`Enable-Native-Access`). If you start
r7 some other way than `java -jar`, add `--enable-native-access=ALL-UNNAMED` (already part of
the recommended JVM flags), or the JVM prints a warning.

Fault-ahead does nothing about writeback throttling, which depends on the disk and on how
quickly a tailer consumes sealed segments.

### The settings

| Setting | Effect | Cost |
|---|---|---|
| `storage.shard_count` | Fewer writers per shard: less queueing and a shorter tail. Must be a power of two. | More segment files open at once, and per shard a fault-ahead thread and about 8 MB kept faulted in (up to 128 MB at the default's cap of 16). No other memory cost unless `pre_fault` is on. |
| `storage.pre_fault` | Touches each segment's pages while warming it, off the request path. With fault-ahead available, it was slower than leaving it off in every case measured. Use it only where fault-ahead is not available. | Each shard's warmed segments are charged to memory at once: the active segment plus 4 queued plus 1 being warmed. That is about 1.2 GB per shard at the default `shard_size` of 200 MB. They also take real disk space immediately rather than as written. |
| `storage.shard_size` | Scales what `pre_fault` charges per shard (about 6 × `shard_size`). Smaller segments also rotate more often. | Each rotation does a little work under the shard's lock, so very small segments add their own stalls. |
| Journal medium | Where `work_dir` lives: a disk-backed volume or tmpfs. | tmpfs is memory that cannot be reclaimed. See below. |

### What they did, measured

`HEADERS` journaling, header-heavy workload, 200 connections at saturation. On tmpfs, which
removes disk writeback and isolates the writer lock:

| Storage | Throughput and tail |
|---|---|
| 1 shard, without fault-ahead (before r7 had it) | 41k req/s, p99 20 ms |
| 1 shard, `pre_fault: true` | 93k req/s, p99 55 ms |
| 1 shard, with fault-ahead | 98k req/s, p99 50 ms |
| 4 shards | 96k req/s, p99 24 ms |

On a local disk, one shard: 63k req/s without fault-ahead, 77k with `pre_fault`, 84k with
fault-ahead.

Under a container memory limit (`-m 8g`), journals on a disk volume, with cgroup v2
`memory.stat`:

| Storage | Mapped journal at idle | Working set at idle | Throughput under load |
|---|---|---|---|
| 1 shard, without fault-ahead | ~190 MB | ~340 MB | 48k req/s |
| 1 shard, `pre_fault: true` | ~1.4 GB | ~1.6 GB | 34k req/s |
| 4 shards, `pre_fault: true` | ~5.0 GB | ~5.3 GB | 30k req/s |
| 1 shard, with fault-ahead | ~190 MB | ~330 MB | 54k req/s |
| 4 shards | ~220 MB | ~380 MB | 52k req/s |

In the container, the journal's page cache fills the memory limit within seconds at full load,
and reclaiming it, not the writers' lock, sets the pace. That is why every setting is slower
there than on the host. `pre_fault` makes it worse, because the pages it maps up front are the
ones the kernel can least easily reclaim.

### Recommendations

- **Leave `pre_fault` off.** Fault-ahead does its job at a fraction of the memory. Turn it on
  only on a platform without fault-ahead (not Linux, or a kernel older than 5.14), with memory to
  spare and outside a memory-limited container.
- **Keep the default of one shard per CPU.** Every connection's writer queues on its shard,
  and with zstd on, compression runs while the shard is held, so the count has to grow with the
  cores doing the writing: 4 shards halved p99 compared with one. Each shard is one more open
  segment, a fault-ahead thread and 8 MB kept faulted in, and without `pre_fault` costs no
  other memory of note. Past 16 CPUs the default stops at 16; set `shard_count` explicitly to
  go higher.
- **Give a container headroom for page cache, not just heap.** The journal is written through
  the page cache, which counts toward the container's memory limit. Up to the limit, that cache
  is reclaimable. How much stays dirty depends on how fast the disk and your tailer keep up.

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

---

## Journal compression: `compression` and `compression_level`

### What it does

With `compression: zstd`, the default, each 32KB block of a journal segment carries one zstd
stream. Every entry is fed into it and flushed as it is written, so it is still its own
committed record: a tailer reads it as soon as it is written, a crash loses at most the entry
being written, and damage still costs at most the rest of one block. Because an entry is
compressed against the entries before it in the block, repetitive traffic — the same headers,
similar bodies — compresses far better than any entry would on its own.

### What it costs

Compression runs on the writing thread, under the shard's lock, because a block's stream is
shared and its order is the journal's order. That costs CPU per request, which shows only near
saturation:

| Workload, journal level | Throughput at saturation | Latency at 1,000 req/s (p50 / p99) | Journal bytes per request |
|---|---|---|---|
| headers, `HEADERS`, none | 76,500 req/s | 1.21 / 2.88 ms | 3,659 |
| headers, `HEADERS`, zstd 1 | 63,500 req/s (-17%) | 1.29 / 2.66 ms | 273 |
| POST, `FULL`, none | 108,700 req/s | 1.28 / 2.56 ms | 2,872 |
| POST, `FULL`, zstd 1 | 65,800 req/s (-39%) | 1.24 / 2.72 ms | 329 |

Single short runs on the machine described above; the benchmark sends near-identical requests,
so real traffic compresses less than this. Levels above 1 gave a few percent less disk for
another 5-15% of throughput, and 4 shards instead of 2 won back only a little: the cost is CPU,
not lock contention.

What it saves is everything downstream of the write: about a tenth of the disk, of the page
cache charged to a container's memory limit, of the writeback, and of what a tailer reads.

### Recommendations

- **Leave it on.** At any load a real deployment sees, the cost does not show, and the
  journal is a fraction of the size.
- **Turn it off (`compression: none`) only if the gateway runs close to its CPU limit with
  journaling at `FULL`,** and disk and page cache are cheap where it runs.
- **Leave `compression_level` at 1.**

---

## Latency at saturation: the JDK's I/O pollers

### Symptom

Driven past the request rate it can sustain, a gateway on the JDK's default I/O pollers answers
a few requests very late: hundreds of milliseconds where p99 is single-digit milliseconds. The
gateway started through `R7Helidon.main` (the jar and the images) does not run on those
defaults. This section matters if you override them, or embed `R7Helidon` without its `main`,
which then has to set `-Djdk.pollerMode=3` itself.

### Why it happens

Every connection runs on a virtual thread, and when it waits for the network the JDK parks it
and hands the socket to an I/O poller. By default those pollers are themselves virtual threads,
scheduled on the same carrier threads as the requests. When every carrier is busy, a poller can
wait for one, and every connection registered with it waits too, then resumes in the same
millisecond. Measured at saturation on Java 25 (200 connections, 20 cores): about 141k req/s,
p99 7 ms, worst case about 540 ms.

Java 27 has per-carrier pollers (`-Djdk.pollerMode=3`), which poll as part of each carrier's own
scheduling; with them the worst case is about 20 ms. The gateway turns them on by itself.

### What to do

- **Leave `jdk.pollerMode` unset** when starting through `R7Helidon.main`: an explicit value
  replaces the gateway's choice. An embedder sets `-Djdk.pollerMode=3` itself.
- **Do not switch to platform-thread pollers** (`-Djdk.pollerMode=1`). They compete with the
  carriers for the cores: on Java 25 they took p99 from 7 ms to 15-50 ms at every load.
- **Do not run the gateway at saturation.** Size it so that peak load stays below the rate it
  sustains.


---

## Startup: the AOT cache and the garbage collector

### What the image does

The gateway image starts from an AOT cache (the JDK's ahead-of-time cache, JEP 483): while the
image is built, a training run starts the gateway, sends requests through it, and records the
classes it loaded and linked. The image then starts from that record instead of reading and
linking those classes from the jars again, which is most of what a JVM does while it starts.
The first requests after a start benefit too, as the classes they need are in the cache.
Nothing needs configuring: the cache is at `/app/r7.aot` and the entrypoint uses it.

A cache only fits the exact JDK, jars and JVM flags it was made with. A JVM that finds it does
not fit prints a warning and starts without it, as it would with no cache.

### Choosing a garbage collector

The image names no collector, so the JVM picks one for the container: G1, or SerialGC with
fewer than two CPUs or less than about 2 GB of memory. To choose one, set it in
`JAVA_TOOL_OPTIONS`, for example `-XX:+UseZGC` for the shortest pauses under load. The
throughput and latency numbers on this page were measured with ZGC.

ZGC cannot use the AOT cache at all: the cache is made with compressed object pointers, which ZGC
does not use, so the JVM says the cache does not fit and starts without it, noticeably slower
than with G1, ParallelGC or SerialGC. Where startup matters more than pause times (an autoscaled
or frequently restarted gateway, a gateway with little CPU), leave the collector to the JVM.

### Running the jar yourself

`java -jar r7-helidon-<version>.jar` needs the `lib/` directory beside the jar, as the build
leaves it. To make a cache for it, run the training once with the JDK and flags you will run
with, then start with the cache:

```bash
R7_ROUTES_CONFIG=docker/aot-training/routes.yaml R7_JOURNAL_DIR=/tmp/aot-training \
  R7_FINGERPRINT_KEY=aot-training-only-fingerprint-key \
  java -XX:AOTCacheOutput=r7.aot -Dr7.aot.training=true -jar r7-helidon-<version>.jar
java -XX:AOTCache=r7.aot -jar r7-helidon-<version>.jar
```

The training run exits by itself once it has served its requests. Its throwaway key is only for
the training requests; the second command needs your deployment's own
[fingerprint key](journaling.md#redacted-header-and-query-parameter-values).
