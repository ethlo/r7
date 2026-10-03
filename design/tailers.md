# Tailers: what they share

**Status:** current.

A tailer is a sidecar that reads the gateway's journals and writes them somewhere else. Today
there are two, JSON lines (`r7-tailer-jsonld`) and WARC (`r7-tailer-warc`), and the shared
code is split into two modules so a new tailer starts from what it actually needs.

## Two layers

| Module | Who uses it | What it holds |
|---|---|---|
| `r7-tailer-api` | every tailer | `TailerRunner` and `TailerConfig` |
| `r7-tailer-files` | tailers that write local files | `SealedFileWriter` and `RollingFilesConfig` |

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

YAML keys stay flat: the interfaces group settings in code, not in the file.

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

## Not decided here

Whether JSON and WARC become one reader with two outputs, and what the JSON row carries (a
WARC locator, headers as a delta), are open; see [`plans/tailer-plugins.md`](plans/tailer-plugins.md).
These modules work either way.
