# Design

This folder explains why r7 is built the way it is. It is split by how long a document stays
true:

- **This folder** holds design that is kept current: how a part works, the decisions in force,
  and the [known limitations](limitations.md). If the code and one of these documents disagree,
  that is a bug in one of them, and a PR that changes the behaviour updates the document.
- **[`plans/`](plans/)** holds work that is designed or proposed but not built. Nothing there
  describes how r7 behaves.
- **[`history/`](history/)** holds plans that were carried out or abandoned, with the
  investigations and measurements behind them. These documents are not maintained. They are
  kept because they record why, and the code cites them for that. Each one opens with a note
  saying what has changed since.

User-facing contracts live in `docs/` (configuration, journaling, extensibility). The journal's
normative file format is [`r7-journal-mmap/FORMAT.md`](../r7-journal-mmap/FORMAT.md).

## Current

| Document | What it covers |
|---|---|
| [`limitations.md`](limitations.md) | What r7 does not do, or does at a known cost |
| [`upstream.md`](upstream.md) | The upstream client every server uses, and the rules it keeps |
| [`journal-invariants.md`](journal-invariants.md) | The journal's four invariants and the tests that enforce each |
| [`warc.md`](warc.md) | The WARC sidecar: why WARC, the record shape, and what it must carry over |
| [`tailers.md`](tailers.md) | The tailer's two outputs, and what every tailer and every file-writing tailer shares |
| [`asvs-l2.md`](asvs-l2.md) | OWASP ASVS 5.0 Level 2 self-assessment |
| [`native-image.md`](native-image.md) | Decision: no native image |

## Plans

See [`plans/README.md`](plans/README.md).

## History

| Document | What it records |
|---|---|
| [`history/server-spi.md`](history/server-spi.md) | Moving the request pipeline out of `r7-undertow` behind a server SPI, and the per-request cost profile |
| [`history/upstream-client.md`](history/upstream-client.md) | Building `r7-upstream`, the Undertow comparison, and bringing Níma to parity |
| [`history/journal-format-v2.md`](history/journal-format-v2.md) | Why r7f moved to block framing, and what it gave up |
| [`history/journal-write-contention.md`](history/journal-write-contention.md) | The journal lock investigation: fault-ahead, `shard_count`, `pre_fault` |
| [`history/live-tailing.md`](history/live-tailing.md) | Microsecond tailing: the control file and the tailer's wait |
| [`history/tailer-plugins.md`](history/tailer-plugins.md) | The one-reader, several-outputs proposal behind `r7-tailer` |

## Writing a design document

Start with a title and a **Status** line: *current*, *plan* or *history*. A current document
says what is true now, not how it got there. Narrative, measurements that will age and
"step N" plans belong in a plan, which moves to `history/` once it is done. When a plan ships,
copy what stays true into a current document (or `limitations.md`) in the same PR.
