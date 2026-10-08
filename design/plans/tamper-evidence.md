# Tamper-evident journal

**Status:** plan, awaiting agreement. Nothing here describes how r7 behaves today.

The journal records what a client sent. This plan makes it prove that the record is complete
and unchanged: every sealed segment gets a signed statement, the statements form a chain per
shard, and the chain carries through into the tailer's output files. A changed byte, a segment
missing from the middle or a reordered pair is then visible to anyone holding the public key.
Missing segments at the newest end are visible against a chain head kept somewhere else (below).

It serves the first pillar, the audit journal. It is core rather than an extension, because the
statement has to be written by the process that sealed the segment.

## What it proves, and what it does not

| Change | Detected |
|---|---|
| A byte of a sealed segment edited, up to its Data End | yes: the digest no longer matches |
| A sealed segment deleted from the middle, or replaced by another one | yes: a gap or a break in the chain |
| The newest segments deleted, with their statements | only against a head kept elsewhere: the gateway logs every statement's hash and index, and the tailer's checkpoint and output statements carry the last one it saw. A chain alone can't show that its own end is missing |
| A sealed segment's statement edited or forged | yes: the signature fails |
| A tailer output file edited, or deleted from the middle, after it was sealed | yes, when the tailer signs (decision 4) |
| The newest tailer output files deleted, with their statements | only against a head kept elsewhere, as for segments: the tailer logs every output statement's hash and index |
| The chain restarted to hide a deletion | the restart is visible, and it is signed: only the gateway's key can start a chain |
| An active (`.flux`) segment edited before it is sealed | **no** |
| Anything done by whoever holds the gateway's key or controls its process | **no** |

The last "no" can't be fixed by any design. The one before it is bounded by `shard_size`: what is
still open when the attacker arrives is all they can change. The docs will state both outright.
Overstating this would be a reputation risk of its own.

## The statement

Each segment gets a sidecar named after its stem, the part of its name that sealing doesn't
change (`shard-<id>-<created>-<sequence>`): `<stem>.seal`, a small text file:

```
r7-seal: 1
chain: 3f2a9c4e-61b0-4c8e-9f57-0d1e2b3c4a5d
shard: 0
index: 1834
segment: shard-0-1696760000000-731
data_end: 209715200
sha256: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
recovered: false
prev: 5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8
key: <the Ed25519 public key, Base64>
sig: <the Ed25519 signature, Base64>
```

- `sha256` covers the segment from offset 0 to `data_end`, its Data End. That includes the preamble and seal
  record, and excludes the unused pre-allocation.
- `prev` is the SHA-256 of the previous statement's text (every line up to and including the
  `key` line). The first statement of a chain has `prev: start` and a `start_reason` line
  (`new_volume`, `head_lost`, `key_changed`).
- `sig` is Ed25519 over this statement's own text, the bytes the next statement's `prev` will
  hash. Both algorithms are in the JDK, so this adds
  no dependency.
- `key` is the raw 32-byte Ed25519 public key (RFC 8032), and `sig` the raw 64-byte signature,
  both in standard Base64 with padding (RFC 4648 §4). Hashes are lowercase hex. The gateway also
  logs the key as PEM, for `openssl`.
- `key` is there so a reader can tell which key signed. A verifier never trusts it on
  its own: it compares it with the key it was given.
- **The signed bytes are exact.** UTF-8, one `name: value` per line, a single space after the
  colon, no trailing whitespace, every line ending in LF (no CR), fields in exactly this order, with
  the optional ones (marked `?`) left out entirely when they don't apply:
  `r7-seal`, `chain`, `shard`, `index`, `segment`, `data_end`, `sha256`, `recovered`,
  `quarantined?`, `empty?`, `start_reason?`, `first_signed?`, `prev`, `key`, `sig`.
  `empty` is a comma-separated list of segment sequences, in ascending order. The statement text is every byte up to
  and including the LF that ends the `key` line. A statement's `sig` covers its own statement
  text, and the next statement's `prev` is the SHA-256 of those same bytes. The `sig` line comes
  last. A writer that produces anything else is wrong, and a
  verifier checks the bytes as they are, without normalising them.
- The first statement of a `new_volume` chain also carries `first_signed: <segment sequence>`:
  segments on that shard below it were sealed before signing began. A chain started for
  `head_lost` or `key_changed` carries none, because the boundary was set once and doesn't move.

The chain links statements, not segments. A statement is a few hundred bytes, so it can outlive
its segment. A signing tailer copies it into its output (below, decision 4), and then the chain
stays checkable after the reaper has deleted every segment it covers. Without tailer signing the
statements go with their segments, so the chain can be checked only over what is still on disk.

The `.r7f` format doesn't change. The sidecar is a non-segment file, which `FORMAT.md` §3.1
already tells readers to ignore. Its spec goes in `FORMAT.md` as a new section.

## Where the work runs

Off the request path. `rotateSegment` runs inside the shard's lock on a request thread, and it
already only stamps the seal record and hands the rest to a virtual finalizer thread
(`finalizeSegmentAsync`). That finalizer:

1. waits for the previous finalizer of the same shard, so statements are written in order. If
   that one failed (an I/O error while hashing, forcing or publishing), this finalizer retries
   it first, with a backoff, and logs an error on each failure. It never skips it: a later
   segment is signed only once every earlier one is, so the head can't move past an unaccounted
   segment. Requests aren't affected, because finalizers run off the request path. The writer
   keeps rotating, and the backlog of segments waiting for statements shows in the gateway's
   health as an ERROR. A restart hands the backlog to recovery;
2. hashes the segment up to its Data End, signs the statement, forces the segment to disk (an
   msync, which the async path doesn't do today), and then unmaps it as it does today, so nothing
   holds a mapping when the file is renamed (a mapped file can't be renamed on Windows). The
   force comes before the statement is published, so a durable statement always describes bytes
   that are durable too. Without it, a power loss could leave a statement that no longer matches
   the segment, and recovery couldn't tell that apart from tampering;
3. writes `<stem>.seal`: to a temporary name, fsync, rename, then fsync the directory so the
   rename itself survives a power loss (as the `.seq` marker already does);
4. updates `shard-<id>.chain`, the chain head, the same way, directory fsync included. The head
   holds the chain id, the index, the stem and hash of the last statement, the public key that
   signed it, and the sequences of empty segments not yet listed in a statement. It is enough on
   its own for recovery and for noticing a key change after the reaper has deleted every
   segment;
5. renames the segment, as it does today.

The cost is one SHA-256 pass over each segment, one signature, and one forced write-back of
pages the kernel would write back anyway, now on the finalizer's schedule. The hash pass is the
larger part, and it runs on a virtual thread that shares carriers with request handling. It is
measured with `benchmark/bench.sh` on the controlled machine before merge, and this note quotes
no numbers until then. The bar is no visible change
in throughput or tail.

**Recovery** has to be idempotent, because a crash can land between any two steps. Two rules
make it so. A stem has at most one live statement, `<stem>.seal`, and the head records the stem
and chain index of the last statement it covers. The one case where a stem gets a second
statement is quarantine (the last row below): the replaced one is kept as `<stem>.seal.invalid`,
and the new one is told apart by its own chain index. Steps 3 to 5 only ever happen in that
order. At startup, recovery handles each shard's leftover `.flux` segments in sequence order:

| What it finds | Crash was | What recovery does |
|---|---|---|
| A sequence the head lists as pending-empty | after the head update, before the delete | Delete the file. No statement: the next one lists it under `empty:` |
| No `<stem>.seal` | before step 3 | Seal as today, force the segment to disk (recovery writes its seal record through a channel and doesn't force it today), sign with `recovered: true`, then steps 3 to 5 |
| A `.seal` that verifies, head behind it | between steps 3 and 4 | Adopt the statement, advance the head to it, rename |
| A `.seal` that verifies, head already on it | between steps 4 and 5 | Rename only |
| A `.seal` that doesn't verify against the segment | after tampering, never a crash: step 3 is an atomic rename, and it only happens once the segment is forced to disk | In this order, each made durable before the next: rename the bad statement to `<stem>.seal.invalid`, unchanged; quarantine the segment as recovery does with damage today; sign a new `<stem>.seal` with `quarantined:` giving the reason and the SHA-256 of both files as found; advance the head. Log an error. The event is then part of the chain, signed, and the evidence is kept |
| A `<stem>.seal.invalid` and no `<stem>.seal` | inside the quarantine steps | Carry on from where it stopped: quarantine the segment if it isn't yet, then sign and advance the head |
| A `<stem>.seal` with `quarantined:`, head behind it or on it | inside the quarantine steps | As the two "verifies" rows above |

Once signing is on, a sealed `.r7f` without a `.seal` can't exist, because step 5 comes after
step 3. Segments sealed by a gateway from before the upgrade have none. The first chain on such
a volume starts with `start_reason: new_volume`, and the tailer gives those older segments the
verdict `unsigned` rather than a failure. A segment the
writer deletes because it holds no entries gets no file of its own. Before the file is deleted,
its sequence is added to the head (written and fsync'd as in step 4), and the next statement moves
it from the head into its `empty:` line. A crash in between leaves the fact in the head, so a jump in segment sequence that no statement accounts for always
means a segment went missing.

**Clean close** (`finalizeActiveSegment`) does the same steps synchronously.

**A missing or unreadable chain head** starts a new chain with `start_reason: head_lost`, and
logs a warning. A fresh volume starts one with `new_volume`.

## The tailer

The tailer delivers entries live from the active `.flux` segment, and resumes from a byte
checkpoint after a restart. So it can't verify before it delivers, and it doesn't try: holding
output back until each segment seals would end live tailing.

Verification is a separate pass once a segment has its `.seal`. The tailer re-reads the sealed
segment up to `data_end` (from the page cache, because it has just read it), and checks the
hash, the signature against its pinned keys, and the link to the last statement it saw (kept
in its checkpoint). That costs one extra read pass per segment, in the tailer, nowhere near the
gateway. If the `.seal` isn't there yet, the check waits for it, and delivery carries on
meanwhile. The wait ends with a verdict either way:

- a segment below the shard's `first_signed` gets `unsigned`: it was sealed before signing
  began. The tailer keeps that boundary in its checkpoint from the first `new_volume` chain it
  sees, and a later chain restart never moves it, so a missing statement from an earlier chain
  can't pass as `unsigned`. A `new_volume` start on a shard the tailer already holds a chain for
  is itself a failed verdict, `chain_reset`;
- a sealed segment with no `.seal`, once a later statement for its shard exists, gets the failed
  verdict `missing_statement`. A statement is written for every segment in order, so a later one
  proves this one had its statement, and someone removed it. The later statement's `prev` also
  names the hash of the missing one.

Because records are already written when the check runs, the result can't be put on them.
It goes in the tailer's output statement instead (below), as a verdict per source segment. A
failed check is never silent and never drops data, the same rule as fingerprinting. See
decision 3.

A tailer counts a segment as done only once it has been verified, so a reaper that lists the
tailer waits for verification before deleting early. The reaper deletes `<stem>.seal` together with the segment, and `shard-<id>.chain` is never deleted.

## The archive

Segments are transient. The WARC and JSON files are what is kept, so the proof has to reach
them. `SealedFileWriter` already has a hook for a companion file written before the seal (the
CDXJ index uses it). A signing tailer writes `F.seal` next to each output file `F`, in the same
shape as the gateway's statement:

- `sha256` of `F`, and of its CDXJ index when there is one;
- `prev` linking to the tailer's previous output statement (its own chain, kept in its
  checkpoint directory);
- `sources`: the gateway statements, verbatim, of the segments whose records `F` holds, each
  with the tailer's verdict (`verified`, or the reason it failed). A segment still open when `F`
  seals is listed as `pending`, and its verdict goes in the first later statement after it seals,
  so every verdict lands in the tailer's chain. If verdicts are waiting and no output file seals
  within `max_file_age` (traffic stopped), the tailer writes a statement on its own,
  `<prefix>-<time>.seal`, with no file and only the verdicts. Its chain carries on from there.

The same crash rules apply as for the gateway. There is one statement per output file, keyed
by its name, and it is written before the file's rename (the hook runs there, and also when
`SealedFileWriter` recovers a file a crash left open). The tailer's chain head in its checkpoint
is advanced after that. On start, a `F.seal` that verifies and is ahead of the head is adopted.

An auditor holding only the archive and the pinned public keys can then check three things: each
file is unchanged, no file is missing from the middle of either chain, and every source
segment was verified. The newest end of the archive is checked against a head kept elsewhere,
the same way as the journal's: the tailer logs each output statement's hash too.

## Verifying without a tool to maintain

r7 ships no `verify` command. The statement format is the contract, and the docs give a short
recipe with standard tools:

```bash
sed '/^sig: /,$d' STEM.seal > statement
sed -n 's/^sig: //p' STEM.seal | base64 -d > sig.bin
openssl pkeyutl -verify -pubin -inkey r7-journal.pub.pem -rawin -in statement -sigfile sig.bin
seg=$(ls STEM.r7f STEM-*.r7f 2>/dev/null | head -n 1)   # recovery seals as STEM.r7f, rotation as STEM-<first>-<last>.r7f
head -c "$(sed -n 's/^data_end: //p' STEM.seal)" "$seg" | sha256sum   # compare with the sha256 line
```

The verifier the tailer uses is a public class in `r7-journal-mmap`, so anyone who wants to
check a whole chain in Java can call it.

## Decisions for Morten

**1. Where the gateway's key comes from.**
- **(a) Derived from `fingerprint_key`** (recommended): seed = HMAC-SHA256(fingerprint_key,
  `"r7 journal signing key v1"`), used as the Ed25519 private key. There is no new secret and no
  new config, so every gateway signs from day one. A separate key would add no real separation:
  it would live in the same environment as the fingerprint key, so whoever can read one can read
  the other. The gateway logs the public key at startup, for operators to pin. Rotating
  `fingerprint_key` changes the signing key too. The chain head records the key, so the gateway
  sees the change and starts a new chain with `start_reason: key_changed`. Nothing can vouch for
  the new key from inside the chain (the old key is gone by then), so trust passes outside it:
  the tailer takes a list of pinned keys, and a statement signed by a key not on the list gets
  the verdict `pending: unknown_key` and an error log. Once the operator pins the new key, the
  tailer checks those segments and records their real verdicts. A segment still pending counts
  as not done, so early reaping waits. `ttl` still applies: a segment deleted while pending gets
  the final verdict `expired_unverified`. An auditor checking an archive holds
  every key that was pinned in its time, and each output statement says which key signed each
  source.
- (b) A separate `signing_key`, opt-in. That's more to configure, and gateways without it have
  no proof.

**2. On by default.** This follows from 1(a). With 1(b) it would be opt-in.

**3. What the tailer does when a check fails.**
- **(a) Record it and carry on** (recommended): the failed verdict goes in the next output
  statement with its reason, the tailer logs an error, and it keeps
  going. The records are already archived, so the data and the evidence of tampering both stay.
- (b) Stop. That's safer in principle, but one damaged file then halts the archive for
  everything after it, and the records from the failed segment are written either way.

**4. The tailer signs its own output.**
- **(a) Yes, with its own key** (recommended): `R7_TAILER_SIGNING_KEY`, an Ed25519 seed. The
  tailer never holds the gateway's key, so the ownership table in `docs/journaling.md` holds.
  Without the key it still verifies the journal, but its output carries no statement.
- (b) Journal only in this round, with archive signing later. That's smaller, but the long-term
  record, which is the one that matters for audits, stays unprotected.

## Out of scope

- External anchoring (RFC 3161 timestamps, transparency logs). The gateway logs each chain head;
  anyone who wants anchoring takes it from there.
- Protecting active segments. That would mean a digest per block on the write path, which is
  exactly the cost this design keeps off the request path.
- A `verify` command, which is extension territory.

## Order of work

1. The statement and chain in `r7-journal-mmap` (finalizer, recovery, clean close), spec in
   `FORMAT.md`, and the reaper deleting sidecars.
2. Tailer verification and verdicts (decision 3).
3. Tailer output statements.
4. Measurement on the benchmark machine, and the docs: `docs/journaling.md`, a "What it proves"
   section, `design/limitations.md` (the CRC32C line changes) and `FORMAT.md` §9.

Each step is its own PR.
