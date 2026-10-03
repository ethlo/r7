# Tailers: what they share

**Status:** current.

A tailer is a sidecar that reads the gateway's journals and writes them somewhere else. r7 ships
one, `r7-tailer`, with two outputs: WARC files and JSON lines (see "One tailer, two outputs"
below). The shared code is split into two modules so a new tailer starts from what it actually
needs.

## Two layers

| Module | Who uses it | What it holds |
|---|---|---|
| `r7-tailer-api` | every tailer | `TailerRunner` and `TailerConfig` |
| `r7-tailer-files` | tailers that write local files | `SealedFileWriter` and `RollingFilesConfig` |
| `r7-tailer-warc`, `r7-tailer-jsonld` | `r7-tailer` | the two output formats, as libraries |
| `r7-tailer` | the image | `TailerMain`, its config, and the fan-out to the outputs |

**`r7-tailer-api`** is the reading side. `TailerRunner` loads and validates the YAML config,
runs the `R7Tailer` loop, logs journal damage, and saves the checkpoint on SIGTERM before the
output is closed. `TailerConfig` is the reader's settings and nothing else: `journal_dir`,
`checkpoint_dir`, `poll_interval`. A tailer supplies an `ExchangeCompletionListener` (what it
writes) and a `TailerOutput` (what runs after each read, and on shutdown).

**`r7-tailer-files`** is local file output. `SealedFileWriter` writes records under an `.open`
name, rolls on size or age, fsyncs and renames on seal, and recovers files a crash left open.
`RollingFilesConfig` is its settings: `output_dir`, `file_prefix`, `max_file_size`,
`max_file_age`. A format plugs in what a complete record is, an optional file header, and an
optional companion file written before the seal (the CDXJ index).

Output settings are not common, by decision. A tailer that ships straight to a network endpoint
(an HTTP collector, a queue) has no files to roll, and must not inherit settings it ignores.
It depends on `r7-tailer-api` only.

The reader's YAML keys stay at the top level; each output has a block of its own (`warc:`,
`json:`), since both have file settings with the same names.

## Rules the shared code keeps

- **One failure policy.** A listener that throws, checked or not, is retried on the next tick
  from the same journal position (the reassembler rewinds, `FORMAT.md` §6). The loop logs the
  failure and carries on; it never ends the process over a failed write.
- **A failed append is cut back, never discarded.** Records before it were delivered and
  checkpointed past, so they stay. The file is truncated to where the failed append began, and
  the retry lands there. If even the truncation fails, the file is left `.open` for the next
  start to cut back to its last complete record.
- **Roll between appends, never inside one.** An append is one record or one exchange group, so
  nothing is split across files, and the size limit is a ceiling a file crosses.
- **A file opens on its first record.** A file holding only its header (a WARC `warcinfo`) is
  not sealed, so a quiet tailer leaves no empty files.
- **Age rolls between appends and from the loop.** Each append first seals a file past its
  age, since one read of a backlog can run longer than `max_file_age`; `TailerOutput.afterTick`
  runs after every read, including an empty one, so a quiet journal still seals on time. The
  loop wakes at least every `poll_interval`. No timer thread.
- **Output files get journal permissions** (`JournalFiles`): they hold the same request and
  response data.

## One tailer, two outputs

**Decided 2026-10-03.** One process reads the journals once and writes each exchange to the
enabled outputs: WARC files (the archive) and JSON lines (the log). Earlier there were two
tailers, each with its own reader and checkpoint; the reaper had to wait for both, and
everything the reader does was done twice.

- **A body is stored once.** With WARC on, a JSON line carries a `warc` pointer (sealed file
  name, offset of the exchange's first Zstandard frame, compressed length of its records)
  instead of the bodies. An exchange the WARC output does not hold keeps its bodies in the line.
- **Which exchanges get WARC records** is one setting, `exchanges`: `all`, or `with_body` (a
  captured request or response body). Under `with_body`, a line without a pointer is explained
  by the line itself: no captured body, which its `level` and `body_bytes` show.
- **Headers are written in full on every line**, not as a delta like the binary journal: the
  lines are for jq, ClickHouse and log shippers, and a delta would make each of them rebuild
  state.
- **One exchange is one unit across both outputs.** WARC first, since the line needs its
  location; then the line. If the line fails, the WARC records are cut back
  (`WarcFileWriter.discard`) before the exception reaches the reader, which offers the exchange
  again; otherwise every retry would archive it once more. The dedup index learns an exchange's
  payloads only once both are written, so a `revisit` never points at records taken back.
- **A crash between the two** leaves the WARC records without their line. The exchange was not
  checkpointed, so it is written again in full on the next start: the archive can hold it
  twice, which is the at-least-once delivery every output already has across a crash.
- **Fixed outputs, no plugins.** No per-route selection, field projection or access-log
  pattern: the gateway's per-route journal level already decides what is captured, and shaping
  a line is a downstream job (`jq`). Header obfuscation stays in the gateway. A sink that
  talks to the network belongs downstream of a file this process wrote, not inside it.

The earlier proposal is in [`history/tailer-plugins.md`](history/tailer-plugins.md); its
pattern and projection languages were ruled out by the 2026-10-02 product focus (no scripting).
