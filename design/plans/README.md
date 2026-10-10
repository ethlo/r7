# Plans

Work that has been designed or proposed but not built. Nothing here describes how r7 behaves.
When a plan ships, its durable parts move into a design doc one level up, and the plan itself
moves to [`../history/`](../history/).

Every plan is weighed against the product focus set on 2026-10-02: **the audit journal, clean
configuration, and fixed, predictable routing. No scripting.** A plan that does not serve one of
these stays parked.

| Plan | State | Notes |
|---|---|---|
| WARC profile (`WARC.md` beside `FORMAT.md`) | To do | The contract for WARC consumers. [`../warc.md`](../warc.md) calls for it. |
| Live-tailing doorbell (step 4 of [`../history/live-tailing.md`](../history/live-tailing.md)) | Parked | Only worth building if a real consumer needs sub-millisecond latency when idle. |
| [Journal compression per batch, off the request thread](journal-batch-compression.md) | Agreed, to build | A writer thread per shard compresses staged entries as one frame. Supersedes the parked "writer thread per shard, or per-entry compression" question in [`../history/journal-write-contention.md`](../history/journal-write-contention.md). |
| Static content cache for small, hot files | To do | Removes the `Cleaner` lock contention in [`../limitations.md`](../limitations.md). Validated by size and modification time. |
| Fewer journal invariants ("How to get to two" in [`../journal-invariants.md`](../journal-invariants.md)) | Target | No owner. |
| ASVS gaps and roadmap: bcrypt cost floor, Jazzer fuzzing, SBOM, base image scanning | To do | Tracked in [`../asvs-l2.md`](../asvs-l2.md), "Gaps". |
| ClickHouse index and MCP server over WARC files | Out of scope | [`../warc.md`](../warc.md) "Scope": glue for users to write, not part of r7. |
