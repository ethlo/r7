#!/usr/bin/env bash
#
# Commits the r7-journal hardening work as a readable series.
#
# Run from the repository root:   bash commit-series.sh
# Delete this script afterwards:  rm commit-series.sh
#
# Groups are file-disjoint, so this is a plain `git add <paths>` per commit with no
# hunk surgery. See the note at the bottom about what that costs.
#
# Nothing is pushed. Review with `git log --stat` before you push.

set -euo pipefail

if [[ ! -d .git ]]; then
    echo "Run this from the repository root." >&2
    exit 1
fi

BRANCH="${1:-journal-hardening}"

if git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
    echo "Branch ${BRANCH} already exists. Pass a different name: bash commit-series.sh my-branch" >&2
    exit 1
fi

git switch -c "${BRANCH}"

TRAILER=$'\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>\nClaude-Session: https://claude.ai/code/session_01KrT2Sn7UmiZovv8PKNgic3'

commit() {
    local message="$1"
    shift
    git add -- "$@"
    if git diff --cached --quiet; then
        echo "  (nothing staged, skipping)"
        return
    fi
    git commit -q -F - <<<"${message}${TRAILER}"
    echo "  committed: $(head -1 <<<"${message}")"
}

# ---------------------------------------------------------------------------

commit "api: validate header and attribute text at the point it is set

HTTP header values are latin-1 on the wire and the journal stores them as the
same bytes, so a value from a client round-trips unchanged. A value set
programmatically by a filter is a Java string and can contain anything, and
anything outside latin-1 cannot be represented: the writer truncates each
character to its low byte, so U+2013 was stored as 0x13 and the original was
unrecoverable.

TextValues rejects out-of-range characters, and null, on set and add for both
headers and attributes. Rejecting at the mutation point rather than at
journal-write time names the filter that produced the value instead of
surfacing later as mojibake in an audit record, and keeps the check off the
journal write path.

Multi-value set is implemented, validating into a local list before mutating
so a rejected value leaves the container unchanged and a single-pass source is
iterated once." \
    r7-api/src/main/java/com/ethlo/r7/api/TextValues.java \
    r7-api/src/main/java/com/ethlo/r7/api/InvalidTextValueException.java \
    r7-utils/src/main/java/com/ethlo/r7/util/MutableFastGatewayHeaders.java \
    r7-utils/src/main/java/com/ethlo/r7/util/MutableBaseGatewayAttributes.java

# ---------------------------------------------------------------------------

commit "journal-api: report incomplete and damaged records as typed events

Everything except onComplete died in a log line. A consumer could not tell how
many exchanges were dropped, or why, or that a segment had lost entries.

ExchangeCompletionListener gains onIncompleteEnd, onAbandoned, onOrphanedEnd,
onOrphanedBody and onChecksumMismatch, all defaulted so existing consumers
still compile. The first two are split because their invariants differ: an
exchange that reached its end has status, timing and counters applied and is
loggable as a partial record, while one that timed out has none of them, and a
logger writing those as zeros would produce an audit trail that lies.

JournalIntegrityListener is separate, for damage to the log rather than to a
request. Keeping them apart means an exchange consumer is not forced to
implement file-layout callbacks.

JournalExchange now copies body fragments on arrival. They were kept as
zero-copy slices of whatever the reader was looking at, but an exchange
outlives that: for a compressed segment the slice pointed into the reusable
decompression buffer, which the next file overwrites, so any exchange spanning
a compressed segment boundary had its body silently change contents.

ReassemblyOptions makes the reader's retention and capacity limits
configurable, with the side-effects documented." \
    r7-journal-api/src/main/java/com/ethlo/r7/journal/api/ExchangeCompletionListener.java \
    r7-journal-api/src/main/java/com/ethlo/r7/journal/api/JournalIntegrityListener.java \
    r7-journal-api/src/main/java/com/ethlo/r7/journal/api/JournalExchange.java \
    r7-journal-api/src/main/java/com/ethlo/r7/journal/api/ReassemblyOptions.java

# ---------------------------------------------------------------------------

commit "journal: sequence-number entries and fix recovery

Writeback under mmap is not ordered. The kernel flushes dirty pages in its own
order, so after a power loss a segment can contain valid entries after a region
that never reached the device. Both readers stopped at the first zero byte, so
a hole silently discarded every entry after it and nothing reported it.

Every entry now carries a monotonic sequence number, covered by the CRC, so a
reader can tell end-of-data from missing-data and report what is gone. This
does not make the journal survive power loss, which remains an explicit
non-goal of the no-fsync design; it makes loss detectable instead of silent.

Recovery had three defects. It computed lastValidPosition and logged
'truncating file' but never called truncate, so recovered segments were sealed
at their full pre-allocated size. It did not validate the preamble, so a file
shorter than 1024 bytes threw and was left to be rescanned on every boot.
And it quarantined the warmer's untouched pre-allocated spare as corrupt,
producing an ERROR and a .corrupt file on every restart — an integrity signal
that cries wolf on every boot is worse than none.

Writer bounds: the ASCII scratch and the header and attribute offset arrays
were fixed-size with no bounds check, so a long cookie threw from inside the
request path. close() did not latch, so a write after close silently allocated
a new segment. The provider did not drain its warmed segment on shutdown.

Format stays version 1 — there are no releases and no data to migrate, so
existing journal directories should be deleted rather than recovered." \
    r7-journal-mmap/FORMAT.md \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/R7fConstants.java \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/R7fJournal.java \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/R7fJournalProvider.java \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/R7fRecoveryManager.java \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/JournalDecoder.java

# ---------------------------------------------------------------------------

commit "journal: decode header and attribute text as latin-1

One round trip used three encodings: the writer stored latin-1 bytes, header
names were read back as ASCII, and header and attribute values as UTF-8.
UTF-8 decoding latin-1 bytes maps every byte above 127 to U+FFFD, so any header
carrying a non-ASCII byte was silently rewritten on replay.

Attribute values had the same bug, which matters more because attributes are
written at every journal level while headers are not." \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/AbstractFbsGatewayHeaders.java \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/FbsGatewayHeaders.java \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/FbsGatewayAttributes.java

# ---------------------------------------------------------------------------

commit "journal: verify compressed segments before deleting the original

compressAndDelete wrote with a single FileChannel.write, which is permitted to
write fewer bytes than requested, then ATOMIC_MOVEd the result into place and
deleted the source. A short write meant a permanently lost audit segment with
nothing logged.

Writes now loop until drained, force, and verify by decompressing and comparing
CRC32C against the source before the original is removed. An audit record is
not deleted on the strength of a write that returned without throwing.

Also guards the int casts around Zstd.compressBound for large segments." \
    r7-journal-mmap/src/main/java/com/ethlo/r7/journal/compression/R7fCompressionEngine.java

# ---------------------------------------------------------------------------

commit "journal: bound the reader and fix its checksums and segment ordering

requestCrc32 and responseCrc32 were single instance fields shared by every
in-flight exchange and never reset, so the value was a running checksum of
every body ever seen and the comparison against the gateway's recorded value
was meaningless. Checksums are now per exchange and compared on completion.

An exchange whose EndExchange never arrived was retained forever with its body
fragments buffered. Adds age-based eviction with a hard ceiling and counters
for what was dropped. Eviction is amortised over incoming events, so the tailer
also sweeps at each tick boundary: on a quiet stream the sweep would otherwise
never run and abandoned exchanges would be held, and go unreported, until
traffic resumed.

parseMeta read parts[2] as the timestamp, but sealed names are
shard-<id>-<createdMs>-<segSeq>-<firstTs>-<lastTs>, so lastEventNanos was
picking up the rotation counter. Segments now sort by the monotonic segment
sequence rather than a wall-clock timestamp that can step backwards, and the
stable key is shard plus sequence, both of which survive the rename from .flux
to .r7f to .zst. Checkpoints carry the expected entry sequence alongside the
offset, so resuming mid-segment can still detect entries going missing.

Existing .r7_checkpoints entries will not match the new keys; the first tick
after this change re-reads segments it had already consumed." \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/ExchangeReassembler.java \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/R7Tailer.java \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/JournalAnalyzer.java \
    r7-journal-mmap/src/main/java/com/ethlo/r7/r7f/util/StringExchangeMap.java

# ---------------------------------------------------------------------------

commit "filters: fix two NPEs found by the fuzzer

SetStatusFactory.Config.validate called status.validate(result) after
required('status', ...) had already recorded the error. required() records and
returns for chaining; it does not stop execution. A missing status turned
config validation into an NPE, giving the operator a stack trace instead of
\"'status' is required\". Guards the dependent call, matching the pattern
already used in ReturnResponseFactory.

ShortCircuitGatewayResponse set Content-Type to null when given a null content
type — the case StaticContentFactory uses deliberately, since the type is
decided later from the file being served. 'Set the header to null' and 'do not
set the header' are different things; a null value has no valid representation
on the wire and none in the journal. It now omits the header.

Audited all 32 filter factories: these were the only two instances." \
    r7-core/src/main/java/com/ethlo/r7/filters/SetStatusFactory.java \
    r7-utils/src/main/java/com/ethlo/r7/util/ShortCircuitGatewayResponse.java

# ---------------------------------------------------------------------------

commit "docs: document reassembly tuning and the text encoding contract

README §11 covers the reader's retention settings and what they cost in both
directions. The headline is that maxAge measures reader-side retention, not
request duration: the clock starts when the reader first sees an event, so the
bound is the request duration plus rotation period plus compression delay plus
tick interval. During replay of archived segments it barely applies at all and
maxInFlight becomes the only real limit.

README §12 states the stored encoding, that readers must decode as ISO-8859-1,
and that characters outside latin-1 are refused where a filter sets them." \
    r7-journal-mmap/README.md

# ---------------------------------------------------------------------------

commit "test: cover the journal life cycle and its failure modes

The suite was three classes, two of them performance tests, and neither
correctness test compared a written value to the value read back — they
asserted an exchange count and that the files were larger than the bodies
written. Status codes, timestamps, byte counters, headers and body content were
written and never checked.

JournalLifecycleTest covers the full cycle: field-by-field round trip, latin-1
values, 400 exchanges with distinct bodies across forced rotations each
compared individually, an exchange spanning segments, recovery sealing an
abandoned segment, and recovery idempotence.

JournalIntegrityTest covers damage: a flipped bit skipped and reported, an
overwritten entry detected as a sequence gap, truncation mid-entry, foreign and
sub-preamble files quarantined, and a body checksum mismatch reported. It
encodes the on-disk framing deliberately, so a format change has to be made
here too rather than silently invalidating the corpus.

TextValuesTest asserts the latin-1 boundary from the writing side, the same
boundary JournalLifecycleTest asserts from the reading side." \
    r7-journal-mmap/src/test/java/com/ethlo/r7/CollectingSink.java \
    r7-journal-mmap/src/test/java/com/ethlo/r7/JournalLifecycleTest.java \
    r7-journal-mmap/src/test/java/com/ethlo/r7/JournalIntegrityTest.java \
    r7-journal-mmap/src/test/java/com/ethlo/r7/TextValuesTest.java

# ---------------------------------------------------------------------------

echo
echo "Done. Nine commits on ${BRANCH}."
echo
git log --oneline "$(git rev-parse --abbrev-ref '@{upstream}' 2>/dev/null || echo main)..HEAD" 2>/dev/null \
    || git log --oneline -9
echo
echo "Anything left unstaged (expected: this script, and any file you changed yourself):"
git status --short
echo
echo "NOTE: only the final commit builds. The groups are file-disjoint, which makes the"
echo "series readable, but the changes within a file belong to several concerns, so the"
echo "intermediate commits do not compile. Good for review, not for bisect."
