# Suggested commit series

One PR, eleven commits. Each one compiles and passes tests on its own, so `git bisect`
works and a reviewer can check out any point. Ordered so nothing depends on a later commit.

The split is by *reason for change*, not by file — that is what lets each message be short.

---

## 1. journal: add per-entry sequence numbers to the r7f format

```
journal: add per-entry sequence numbers to the r7f format

Writeback under mmap is not ordered. The kernel flushes dirty pages in its
own order, so after a power loss a segment can contain valid entries after a
region that never reached the device. Both readers stopped at the first zero
byte, so a hole silently discarded every entry after it and nothing reported
that it had happened.

Every entry now carries a monotonic sequence number, covered by the CRC. A
reader can tell "end of data" from "data is missing" and report the count and
location of what is gone. The preamble carries a monotonic per-shard segment
counter alongside the wall-clock creation time.

This does not make the journal survive power loss — that remains an explicit
non-goal of the no-fsync design. It makes loss detectable instead of silent.

Entry header grows from 16 to 20 bytes. Format stays version 1: there are no
releases and no data to migrate, so existing journal directories should be
deleted rather than recovered.
```

Files: `R7fConstants`, `R7fJournal`, `JournalDecoder`, `R7fRecoveryManager`, `FORMAT.md`.

---

## 2. journal: truncate recovered segments at the last valid entry

```
journal: truncate recovered segments at the last valid entry

recoverFile computed lastValidPosition and logged "truncating file" in three
places, but never called truncate. Recovered segments were sealed at their
full pre-allocated size with the zero tail intact, so size accounting was
meaningless and the compressor spent its time on megabytes of zeroes.

Also validates the preamble before scanning. A file shorter than the preamble
threw inside recovery, was caught by the broad handler, and was left as .flux
to be rescanned on every boot. Unreadable files are now quarantined as
.corrupt: not deleted, not left to accumulate.

The mapping is held in a confined Arena so it is released deterministically
before the file is resized.
```

---

## 3. journal: do not quarantine unused pre-allocated segments

```
journal: do not quarantine unused pre-allocated segments

The warmer keeps the next segment mapped and pre-allocated, so every unclean
stop leaves a spare the writer never took. Its preamble is all zeroes, so
recovery's magic check rejected it and quarantined it as corrupt — an ERROR
and a .corrupt file on every single restart.

An integrity signal that cries wolf on every boot is worse than none. An
all-zero preamble means untouched and is now deleted quietly; a non-zero
preamble with the wrong magic is still quarantined. Damage starts with
something, only a spare starts with nothing.
```

---

## 4. journal: verify compressed segments before deleting the original

```
journal: verify compressed segments before deleting the original

compressAndDelete wrote with a single FileChannel.write, which is permitted to
write fewer bytes than requested, then ATOMIC_MOVEd the result into place and
deleted the source. A short write meant a permanently lost audit segment with
nothing logged.

Writes now loop until drained, force, and verify by decompressing and
comparing CRC32C against the source before the original is removed. An audit
record is not deleted on the strength of a write that returned without
throwing.

Also guards the int casts around Zstd.compressBound for large segments.
```

---

## 5. journal: fix exchange body lifetime and per-exchange checksums

```
journal: fix exchange body lifetime and per-exchange checksums

Two defects in the reader, both of which produced wrong data rather than
errors:

Body fragments were kept as zero-copy slices of whatever the reader was
looking at. An exchange outlives that — it is held until its EndExchange
arrives, which can be in a later segment. For a compressed segment the slice
pointed into the reusable decompression buffer, which the next file
overwrites, so any exchange spanning a compressed segment boundary had its
body silently change contents. Fragments are now copied on arrival.

requestCrc32 and responseCrc32 were single instance fields shared by every
in-flight exchange and never reset, so the value was a running checksum of
every body ever seen and the comparison against the gateway's recorded value
was meaningless. Checksums are now accumulated per exchange and compared on
completion.
```

---

## 6. journal: bound the reassembler's in-flight set

```
journal: bound the reassembler's in-flight set

An exchange whose EndExchange never arrived — lost segment, unclean stop, a
corrupt entry that ended a file — was retained forever, with its body
fragments buffered in memory. A tailer following a stream with a few percent
of incomplete exchanges grew without bound.

Adds age-based eviction with a hard ceiling, and counters for what was
dropped. Eviction is amortised over incoming events, so R7Tailer also sweeps
at each tick boundary: on a quiet stream the sweep would otherwise never run
and abandoned exchanges would be held, and go unreported, until traffic
resumed.
```

---

## 7. journal: report incomplete and damaged records as typed events

```
journal: report incomplete and damaged records as typed events

Everything except onComplete died in a log line. A consumer could not tell
how many exchanges were dropped, or why, or that a segment had lost entries.

ExchangeCompletionListener gains onIncompleteEnd, onAbandoned, onOrphanedEnd,
onOrphanedBody and onChecksumMismatch, all defaulted so existing consumers
still compile. The first two are split because their invariants differ: an
exchange that reached its end has status, timing and counters applied and is
loggable as a partial record, while one that timed out has none of them and a
logger writing them as zeros would produce an audit trail that lies.

JournalIntegrityListener is separate, for damage to the log rather than to a
request: missing entries, corrupt regions, sequence regressions, quarantined
and truncated segments. Keeping them apart means an exchange consumer is not
forced to implement file-layout callbacks.
```

---

## 8. journal: make reassembly tuning configurable

```
journal: make reassembly tuning configurable

ReassemblyOptions carries maxAge, maxInFlight and sweepIntervalEvents, with
validation and documented side-effects in README §11.

The documentation matters more than the knobs. maxAge measures reader-side
retention, not request duration: the clock starts when the reader first sees
an event, so the bound is the request duration plus rotation period plus
compression delay plus tick interval. During replay of archived segments it
barely applies at all, and maxInFlight becomes the only real limit. Set too
low it abandons long-running requests and then reports their real end as an
orphan; set too high it delays the signal that data was genuinely lost.
```

---

## 9. journal: fix segment file name parsing and checkpoint continuity

```
journal: fix segment file name parsing and checkpoint continuity

parseMeta read parts[2] as the timestamp, but sealed names are
shard-<id>-<createdMs>-<segSeq>-<firstTs>-<lastTs>, so lastEventNanos was
picking up the rotation counter. Segments now sort by the monotonic segment
sequence rather than a wall-clock timestamp that can step backwards, and the
stable key is shard + sequence, both of which survive the rename from .flux
to .r7f to .zst.

Checkpoints carry the expected entry sequence alongside the offset. Resuming
mid-segment without it meant the reader adopted whatever sequence it landed
on and could not notice entries going missing across a tick boundary.

Existing .r7_checkpoints entries will not match the new keys; the first tick
after this change re-reads segments it had already consumed.
```

---

## 10. api: store and validate header text as latin-1

```
api: store and validate header text as latin-1

One round trip used three encodings: the writer stored latin-1 bytes, header
names were read back as ASCII, and header and attribute values as UTF-8.
UTF-8 decoding latin-1 bytes maps every byte above 127 to U+FFFD, so any
header carrying a non-ASCII byte was silently rewritten on replay. Attribute
values had the same bug, which matters more because attributes are written at
every journal level.

Readers now decode as ISO-8859-1 throughout, matching both the wire and the
writer.

Values set programmatically are validated where they are set: TextValues
rejects characters above 0xFF, and null, on set and add for both headers and
attributes. Rejecting at the mutation point rather than at journal-write time
names the filter that produced the value instead of surfacing later as
mojibake in an audit record, and keeps the check off the journal write path.
Multi-value set is implemented, validating into a local list first so a
rejected value leaves the container unchanged.
```

---

## 11. journal: harden the writer's bounds and lifecycle

```
journal: harden the writer's bounds and lifecycle

copyToScratch wrote str.length() bytes into a fixed 8KB array with no bounds
check, so a long cookie or Authorization header threw
ArrayIndexOutOfBoundsException from inside the request path; the header and
attribute offset arrays had the same problem at 1024 and 100 entries. All
three now grow on demand, with a hard ceiling per value.

close() did not latch, so a write after close silently allocated a fresh
segment and wrote to it. The provider did not drain its warmed segment on
shutdown, leaving a mapped, pre-allocated orphan behind on every clean stop.
Segment size is validated at construction, and an entry too large for any
segment is refused before it burns a freshly warmed one.
```

---

## 12. filters: fix two NPEs found by the fuzzer

```
filters: fix two NPEs found by the fuzzer

SetStatusFactory.Config.validate called status.validate(result) after
required("status", ...) had already recorded the error. required() records
and returns for chaining; it does not stop execution. A missing status turned
config validation into an NPE, giving the operator a stack trace instead of
"'status' is required". Guards the dependent call, matching the pattern
already used in ReturnResponseFactory.

ShortCircuitGatewayResponse set Content-Type to null when given a null
content type — the case StaticContentFactory uses deliberately, since the
type is decided later from the file being served. "Set the header to null"
and "do not set the header" are different things; a null value has no valid
representation on the wire and none in the journal. It now omits the header.

Audited all 32 filter factories: these were the only two instances.
```

---

## 13. journal: add life cycle and integrity tests

```
journal: add life cycle and integrity tests

The suite was three classes, two of them performance tests, and neither
correctness test compared a written value to the value read back — they
asserted an exchange count and that the files were larger than the bodies
written. Status codes, timestamps, byte counters, headers and body content
were written and never checked.

JournalLifecycleTest covers the full cycle: field-by-field round trip,
latin-1 values, 400 exchanges with distinct bodies across forced rotations
each compared individually, an exchange spanning segments, recovery sealing
an abandoned segment, and recovery idempotence.

JournalIntegrityTest covers damage: a flipped bit skipped and reported, an
overwritten entry detected as a sequence gap, truncation mid-entry, foreign
and sub-preamble files quarantined, and a body checksum mismatch reported. It
encodes the on-disk framing deliberately, so a format change has to be made
here too rather than silently invalidating the corpus.
```

---

# Suggested PR description

Lead with what a reviewer cannot infer from the diff:

> Hardening pass on r7-journal so it can be trusted as a production audit log.
>
> **Behaviour changes worth knowing about:**
> - The r7f entry header grows by 4 bytes (sequence number). Format stays version 1 — there
>   are no releases and no data to migrate. **Delete existing journal directories** rather
>   than let recovery interpret them; it will report them as corrupt.
> - Headers and attributes now reject values outside latin-1, and null, at `set`/`add`.
>   This already caught one real bug. Any filter or plugin setting a header from an
>   arbitrary Java string will start throwing.
> - Tailer checkpoint keys changed; the first tick re-reads segments it had already consumed.
>
> **Still true after this PR:** the no-fsync design means power loss can still lose entries.
> The sequence numbers make that *detectable*, not survivable. See FORMAT.md §8.
>
> Six of these are bugs that produced wrong data rather than errors — see commits 2, 4, 5,
> 9 and 12.

---

# If you really want one commit

Use the PR description above as the body, and:

```
journal: harden r7f for production use
```

It is worse for bisect and worse for review, but it is honest: the change set is one
coherent piece of work with one goal.

---

# Attribution

If you want the sessions marked, append to each message:

```
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01KrT2Sn7UmiZovv8PKNgic3
```
