# r7-journal: the invariants, and how they are checked

**Four invariants.** There were six, and they were six because the format asked the reader to
reconstruct the writer's state from raw bytes with no coordination. Every ambiguity that
created needed a rule to resolve it. Making the magic the commit record removed the ambiguity,
and two rules went with it.

That is the direction this document should keep moving: an invariant that can be designed away
is worth more than an invariant that is enforced well. The section at the end names how two of
the remaining four could go the same way.

---

## The deployment, because it decides what is possible

The tailer runs as a **separate process** — typically its own container. The journal directory
is a cross-process handoff. There is no shared lock and no shared memory beyond the mapped
segment files themselves, so every guarantee here holds through the filesystem and through the
mapping. Atomic renames and the two phase extensions are the protocol.

A segment's life is `.flux` → `.r7f`, and stops there. **Compression is not a phase.** It is
one thing a consumer may choose to do with a sealed segment, alongside writing to ClickHouse,
computing stats, or archiving raw — and its output belongs to that consumer, not to this
directory. The gateway's entire job for a segment is write, seal, rename.

---

## 1. The magic is the commit

A writer assembles an entry and stamps its magic last, behind a release fence
(`FORMAT.md` §5.1). The layout is unchanged — the magic is still the first field — but the
*store order* is now normative, and a reader pairs it with an acquire fence.

So a zero where a magic belongs means "no committed entry here", whether that is the end of
the segment or an entry the writer is still assembling. A reader never has to tell "not yet"
from "never", and a magic followed by an entry that will not parse is real damage rather than
a partial write.

This is the one invariant that *pays for itself*: the writer does it once and every reader
gets the guarantee for free. It let two older rules be deleted rather than enforced — the
reader no longer needs a retry protocol for entries caught mid-publish, and recovery no longer
guesses whether a trailing entry was torn or merely unfinished.

The seal record applies the same discipline at segment scale: Entry Count, Last Sequence, Data
End and Seal Flags are written first, the Seal Magic last, behind the same fence.

It rests on two things the implementation must keep true: segments are pre-allocated
zero-filled, and a segment is never reused.

*Checked by* `partialTrailingEntryInAnActiveSegmentIsRetriedNotConsumed` and
`recoverySealsAtAnUnpublishedEntryWithoutReportingDamage`.

## 2. A checkpoint means exactly "everything up to here reached the consumer"

Two halves of one statement, and both were broken at different times by code that looked
reasonable in isolation.

**Never stop without either advancing or proving end of data.** A reader that stops without
consuming the bytes it examined, and without having proved nothing readable remains, stalls
silently: the tailer checkpoints `buffer.position()`, so an unconsumed region is re-read
identically every tick while the damage is never reported.

**Every** way out counts, the loop condition included. `while (remaining >= MIN_ENTRY_SIZE)` is
an exit like any other — a sealed segment ending a few bytes short of an entry header was
examined by nothing and reported by nothing. Reading only the explicit `break`s is how that
survived a round devoted to this very invariant.

The converse holds for an active segment: the remainder is not the reader's to write off,
because the writer may still fill it.

A branch that gives up must still *account* for what it gave up. The sequence-regression path
consumed the rest of a sealed segment without counting it, so the stats said the read was
clean, and the tailer deleted a segment whose surviving entries it had declined to read.

**Never advance without delivering.** The other half, and the one round 7 found. Catching
everything around `dispatch` swallowed consumer failures too, and a swallowed entry is a
consumed entry — checkpointed past, the segment reads as fully processed, and the next tick
deletes it. A sink unavailable for one tick cost an exchange permanently. `DeliveryGuard`
separates the two: an undecodable payload is skipped, a refusal rewinds to the start of that
entry and stops, advancing no sequence expectation. The entry is offered again; nothing after
it is read until it is accepted.

The sequence expectation and the offset are one checkpoint and must move together. Advancing
the sequence past an entry the offset still points at makes the retry read it as a regression
and abandon the rest of the segment.

**The retry needs its state pinned, and it was not.** A stall is unbounded by design; the
assembled exchange behind it was put back into `inFlight`, where `maxAge` is five minutes. An
outage longer than the age limit evicted the very state the retry depends on, so the next
attempt found nothing to attach the end event to, reported an orphan, and let the segment be
deleted. The guarantee expired on a timer. Such exchanges now live in `awaitingRedelivery`,
outside both the age sweep and the capacity ceiling: they are not in flight, nothing more is
coming for them, and the bound is the number of stalled segments — each of which is being held
on disk anyway.

**Three collisions of the same shape** turned up across rounds 7–11, and the shape is the
lesson:

| preserves | disposes | met at |
| --- | --- | --- |
| the stall's rewind | the sealed-tail block | `decode`'s post-loop clause |
| an exchange being assembled | the age sweep | `getOrCreate`'s lookup order |
| an exchange awaiting redelivery | `maxAge` | `runTick`'s end-of-tick sweep |

**A rule that preserves and a rule that disposes will meet.** When one is added, find the other
and make their domains provably disjoint rather than merely different in intent. Corollary:
every retention or eviction policy needs an explicit answer to *"what is pinned against me"*,
and that answer belongs next to the policy, not at each site that might be holding something.

*Checked by* `sealedSegmentEndingMidHeaderIsReportedAndConsumed`,
`sequenceRegressionInASealedSegmentIsAccountedFor`, `holeAtTheStartOfASealedSegmentIsCrossed`,
`anEntryTheConsumerRefusesIsOfferedAgainRatherThanSkipped`,
`theSweepNeverReturnsAnExchangeItJustEvicted` and
`anExchangeAwaitingRedeliverySurvivesTheSweep`.

## 3. Identity is unique and monotonic, and losses are detectable from it

**Segment sequence**, per shard: the tailer deduplicates files on `(shard, sequence)` and files
its checkpoints under it, so a collision means a segment is never read, or a new segment is
resumed at a dead one's offset and deleted unread. Seeding from the segments on disk is not
enough — retention deletes them — so the writer persists a high-water mark in `shard-<id>.seq`
and takes the maximum.

That marker is **the one thing in this design that is fsynced** — contents forced before the
rename, parent directory forced after it. The no-fsync rule is about the write path, where a
sync per entry would cost everything; this is one small write per rotation, and what it buys is
the uniqueness of segment identity. Different trade, different answer.

**Entry sequence**, restarting at 1 in every segment: a reader opening a segment it has never
seen knows the first entry must be #1, which is the only chance to detect entries lost from the
*start* of a segment — there is no earlier entry to compare against.

**Order is correctness, not presentation.** The reassembler joins an exchange whose start is in
one segment to its end in the next, so segments must be replayed in `(shard, sequence)` order.
A name this parser cannot read therefore has no position in the stream at all, and is
quarantined rather than replayed at a guess — the writer cannot produce such a name, so it only
happens when someone puts a file there by hand.

*Checked by* `JournalInvariants.assertSegmentKeysAreUnique`,
`segmentSequencesDoNotCollideAcrossRestart`,
`segmentSequenceDoesNotRestartAfterEverySegmentIsDeleted`,
`entriesLostAtTheStartOfASegmentAreReported` and
`aSegmentWithAnUnreadableNameIsSetAsideRatherThanReplayedOutOfOrder`.

## 4. Destroying data requires proof, and every claim is about bytes that were really there

A sealed segment records **Data End** and a reader bounds itself by it, which is what lets
zeroes before Data End mean "pages that never reached the device" rather than "unused tail".
Nothing renames, deletes or truncates a file outside its own phase, and nothing shrinks a file
another process may have mapped.

Deletion needs **proof, not absence of evidence**. "The scan found no records" is not proof: a
stamped segment whose first entry is torn scans to zero records while holding the only
surviving copy of what was written. Anything not provably empty is quarantined — and
quarantining must never overwrite an earlier quarantined copy.

**A clean read is not proof either**, and this caught the code twice. Where a sealer
deliberately stopped short, everything it withheld lies past Data End, so a reader sees a
healthy short segment and would delete it: the seal flag `RETAIN` carries that decision across
the handover. And a sealed segment that *lost* its tail satisfies "the file is no longer than
where I stopped reading", which was read as "I read all of it" — so the shortcut skipped the
seal record in order to delete the evidence the seal record exists to hold. A sealed segment
now takes no shortcut at all.

The general rule: **a decision one component made about bytes another component cannot see has
to travel in the file.**

The same applies to what a record *claims*. A checksum cannot signal its own absence — every
32-bit value, zero included, is the legitimate CRC32C of some input — so `BodyChecksum` carries
the distinction as a type. `NOT_RECORDED` is the only way to say "none" and it is not a number,
so it cannot be typed by accident; the `-1` encoding lives in `R7fConstants.CHECKSUM_ABSENT`,
written and read in exactly two lines. `StatefulJournal` owns the claim because it is the only
layer that knows which fragments reached the journal, and creates each accumulator lazily. A
recorded checksum is then the *whole* condition for verifying: requiring the start event's
level and a positive byte count as well turned a sufficient condition into a conjunction that
fails open.

**An oracle that can call a lossy state clean is worse than no oracle.** Both `CollectingSink`
and `JournalAnalyzer` recorded `discardedBytes` without asking about it, so a recovery that
discarded journal content reported itself clean. Both now include it.

*Checked by* `JournalInvariants.assertSealedSegmentsEndWhereTheySay`,
`JournalInvariants.assertSealedSegmentsDescribeThemselves`,
`stampedSegmentWithUnreadableDataIsQuarantinedNotDeleted`,
`activeSegmentsAreNeverQuarantinedByTheTailer`,
`aSegmentRecoveryShortenedAroundARegressionIsNeverDeleted`,
`aSealedSegmentThatLostItsTailIsReportedBeforeItIsDeleted`,
`recoveryReportsOnlyTheContentItCouldNotRead`,
`checksumsRoundTripThroughTheJournal`,
`aChecksumOfAllOnesIsNotMistakenForAnAbsentOne` and
`aRecordedChecksumIsVerifiedEvenWithoutTheStartEvent`.

---

## Observer callbacks are not part of the work

Not a fifth invariant — a rule that kept being rediscovered one call site at a time, and is
worth stating once.

Recovery classifies a segment by what escapes `recoverFile`, so every unguarded integrity
callback inside it was a way for a **monitoring integration** to be mistaken for a damaged
file: a throw from `onCorruptRegion` quarantined a segment that had just been read correctly,
and a throw from `onSegmentQuarantined` aborted recovery of every remaining file in the
directory. `IsolatedIntegrityListener` wraps the caller's listener once, for every method
including ones added later.

The same holds for `onAbandoned`. Everywhere else a consumer's throw means "offer me this
entry again", and the decoder can oblige. An abandoned exchange has no entry to rewind to — the
events that built it were consumed ticks ago, and the sweep that surfaces it is amortised over
whatever event happens to be passing. Letting it out would rewind an unrelated entry and
still not retry this one.

Counters follow: every consumer callback increments **after** the call, never before, or a
refused-and-retried delivery is counted once per attempt.

---

## How to get to two

Of the four, only #1 is self-enforcing. The other three are rules the code has to remember at
every site — which is exactly the kind that kept coming back in review.

**#3 could disappear** by putting a writer-instance nonce in the segment name. Keys from two
runs then cannot collide *by construction*, removing the `shard-<id>.seq` marker, its fsync,
the high-water seeding and the refuse-to-start branch.

*Withdrawn.* Making failure states easy to reason about is a goal in its own right, and a
monotonic per-shard counter is what makes "is a segment missing?" answerable by looking at the
directory. A nonce removes the collision but also removes the question.

**#4's deletion half shrinks to nothing** once retention has a single owner and is policy-based
(age and/or total size) rather than consensus-based. "Proof" stops being needed when deletion
is not trying to infer anything. The checksum half stays: it is a claim about content, not
about files.

**#2 is inherent** to a resumable reader and should stay — though checkpointing by entry
sequence rather than byte offset would make "didn't advance" self-correcting rather than fatal.

Target: two invariants — the magic is the commit, and readers make progress.

**The real answer is `warc-sidecar-plan.md`.** Rounds 7–11 found almost nothing about the
format and almost everything about the consumer contract. A consumer that reads finished
records needs none of that machinery; it moves into the sidecar, written once.

---

## Why it took eleven review rounds

Each round was fixed at the *site* the reviewer named, rather than by deriving the rule and
sweeping every place it applied. Round four fixed the resync early exit but not the loop
condition three lines below it; that came back as round six. Round three fixed a deletion made
on weak evidence but left the identical mistake in the next branch down; also round six. Round
nine found two counters incremented before their callback, three rounds after three others had
been fixed for exactly that reason.

A fix can also *create* the next finding. Round five gave the writer a way to say "I did not
compute a checksum", which immediately made the guard beside it redundant — and a redundant
guard ANDed onto a sufficient condition is not harmless, it is a way to fail open. Round seven
found two more: a catch-all that removed a spurious warning and quietly acquired the power to
discard an exchange, and a `requestBody` fix that stopped a false checksum mismatch by forcing
FULL — trading a cosmetic problem for a disclosure one. **Both traded a report for a loss.**
Round ten found the durability fix from round eight throwing into a warmer loop that logged and
retried for ever while callers blocked, turning a full disk into a silent hang.

And I twice explained away my own evidence. A test I wrote found that entries lost from the
start of a sealed segment were not reported; I decided the test was badly constructed and
replaced it with a weaker one. It was correct — the resync scan was reading the magic
byte-reversed.

Six habits follow.

1. When a fix lands, re-read every branch that ends the same way, not just the one named.
2. When a new fact becomes expressible, re-read the conditions that existed because it wasn't.
3. When a fix silences a report, ask what that report was for.
4. When a change adds a way to *preserve* something, find every clause that *disposes* of it
   and prove the two cannot meet.
5. When a rule is stated once, grep for every site it governs before calling it applied.
6. When a test disagrees with the code, suspect the code first.

---

## Still true, deliberately

The no-fsync design means **power loss can still lose entries**. Sequence numbers make that
detectable, not survivable. The sequence marker is the single exception (§3).

Checksum computation costs a CRC32C over bytes already in cache — only at `FULL` level, where
those bytes are being copied into the journal anyway.

Publishing the magic last costs a saved offset and one store-store fence per entry. It does not
make the writer any less append-only: the magic slot is part of the entry being appended, not a
field revisited elsewhere in the file.

A genuinely damaged entry in an active segment stalls the tailer at that offset until the
segment is sealed. Bounded by the rotation interval, and preferable to skipping data that may
still arrive.

An entry a consumer refuses stalls that segment until the consumer accepts it, with no bound at
all, and the assembled exchange is held in memory for as long as that lasts. Deliberate: the
alternative is discarding an audit record because a sink was briefly unavailable. It is loud —
segment, offset and sequence on every tick — and it destroys nothing, but a consumer that
refuses the same entry for ever needs a human.

Lazy FlatBuffers decoding means an undecodable payload can present as a refusal and stall a
segment that will never decode. Resolved toward refusal on purpose: a stall can be diagnosed,
and the opposite mistake is silent and irreversible.

**Warm-up failure is terminal.** After five consecutive failures the warmer stops and every
waiting caller is thrown the cause. A gateway that cannot journal needs an operator, and
silently resuming after dropping requests is worse than staying down with a reason.