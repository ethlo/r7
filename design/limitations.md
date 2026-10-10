# Known limitations

**Status:** current. This lists what r7 does not do, or does with a known cost, as of the tree it
ships in. Each item says where the reasoning lives. A PR that removes a limitation removes its
line here. A PR that adds one adds it here. Work to lift one belongs in
[`plans/README.md`](plans/README.md), not here.

## By design

- **Plaintext listener.** r7 does not terminate TLS. It runs on a private network or behind a
  TLS-terminating load balancer (`SECURITY.md`, [`asvs-l2.md`](asvs-l2.md) "Scope").
- **The management port is unauthenticated.** It binds to 127.0.0.1 by default. The container
  images bind it to all interfaces so that a published port works, and keeping it off untrusted
  networks is the operator's job (`docs/config.md`, "Management configuration").
- **JVM only.** There is no native image ([`native-image.md`](native-image.md)).
- **HTTP/1.1 to upstreams.** Clients may use h2c, but the upstream hop is always HTTP/1.1.
  There is no HTTP/3, because neither Níma nor the JDK has an HTTP/3 server
  ([`upstream.md`](upstream.md)).
- **Static credentials towards upstreams.** There is no mTLS or token exchange, and no
  per-upstream trust store or pinning. The JVM trust store decides which certificates are
  trusted ([`asvs-l2.md`](asvs-l2.md), V12.3.4 and V13.2.1).
- **No lockout in `BasicAuth`.** Put a `RateLimiter` in front of it on any route that untrusted
  clients can reach (`docs/config.md`, "Password guessing").
- **The journal fingerprints sensitive values and never removes them.** A header or query
  parameter value outside the safe lists is replaced by a keyed HMAC fingerprint
  (`fingerprint_key`). The name and the fact that a value was there stay in the record.
  There is no option to remove values, because removal changes the recorded exchange without
  leaving a trace. To keep values out of the journal entirely, lower the route's journal level:
  `METADATA` records no headers, and `NONE` records no request line (`docs/config.md`,
  "Journal Redaction").

## Upstream client

- **The connect timeout is fixed at 5 s.** Only the read timeout is configurable per upstream
  (`UpstreamOptions`).

## Journal

- **It costs throughput at saturation.** On one laptop measurement (two shards, `HEADERS` both
  ways), the gateway did 157k req/s without the journal, 77k with zstd and 88k uncompressed.
  Below saturation the cost is in the tail: request p99.9 of about 18 to 24 ms against 7 ms
  with the journal off ([`history/journal-write-contention.md`](history/journal-write-contention.md),
  "The lock on Níma").
- **Page cache fills a container's memory limit within seconds at full load.** It is
  reclaimable on disk, but journals on tmpfs (`emptyDir: {medium: Memory}`, `--tmpfs`) are not.
  The gateway does not delete segments itself, so on tmpfs, without a reaper or tailer
  deleting sealed segments, the container is OOM-killed. `pre_fault` loses 30 to 40% of
  throughput under a memory limit, which is why it is off by default
  (`docs/performance_tuning.md`).
- **Fault-ahead needs Linux 5.14 or later.** Elsewhere the writer takes its page faults while
  holding the shard's monitor, as before fault-ahead.
- **Damage costs up to a block.** After a hole or a bad checksum, a reader resumes at the next
  block boundary, so up to 32 KB of intact entries per damaged region are lost
  (`r7-journal-mmap/FORMAT.md` §6).
- **CRC32C is not a MAC.** The format resists forged entries in payloads, but not a party that
  can write segment files (`FORMAT.md` §6.1 and §9).
- **Live tailing needs a shared page cache.** A tailer on another host, or reading a copy of
  the files, falls back to polling. An idle tailer wakes within about 1 ms of a commit, because
  that is where its park is capped ([`history/live-tailing.md`](history/live-tailing.md)).

## On Níma

- **Tail stalls at saturation depend on the JDK.** On JDK 27 and later, r7 sets the internal,
  undocumented `jdk.pollerMode=3`, which a future JDK may change or drop. An embedder that does
  not start through `R7Helidon.main` must set it itself. JDK 25 has no such mode: at saturation
  a few requests wait up to about 0.5 s ([`history/upstream-client.md`](history/upstream-client.md),
  "The stalls at saturation").
- **Static files contend on a global lock.** Every file served opens a `FileChannel`, which
  registers with the JDK's `Cleaner` under one lock. At saturation the worst case measured was
  255 ms with `jdk.pollerMode=3`, and 4 s without it.

## Experimental: `r7-servlet`

- The servlet host is not a supported deployment. Its listener counters read 0, because a
  servlet container's connections are its own.
