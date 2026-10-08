# Tamper-evident journal

**Status:** plan, awaiting agreement. Nothing here describes how r7 behaves today.

The journal records what a client sent. This plan makes it prove that the record is complete
and unchanged: every sealed segment gets a signed statement, the statements form a chain per
shard, and the chain carries through into the tailer's output files. A changed byte, a missing
segment or a reordered pair is then visible to anyone holding the public key.

It serves the first pillar, the audit journal. It is core rather than an extension, because the
statement has to be written by the process that sealed the segment.

## What it proves, and what it does not

| Change | Detected |
|---|---|
| A byte of a sealed segment edited, up to its Data End | yes: the digest no longer matches |
| A sealed segment deleted, or replaced by another one | yes: a gap or a break in the chain |
| A sealed segment's statement edited or forged | yes: the signature fails |
| A tailer output file edited or deleted after it was sealed | yes, when the tailer signs (decision 4) |
| The chain restarted to hide a deletion | the restart is visible, and it is signed: only the gateway's key can start a chain |
| An active (`.flux`) segment edited before it is sealed | **no** |
| Anything done by whoever holds the gateway's key or controls its process | **no** |

The second "no" can't be fixed by any design. The first is bounded by `shard_size`: what is
still open when the attacker arrives is all they can change. The docs will state both outright.
Overstating this would be a reputation risk of its own.

## The statement

A sealed segment `X.r7f` gets a sidecar `X.r7f.seal`, a small text file:

```
r7-seal: 1
chain: 3f2a9c4e-61b0-4c8e-9f57-0d1e2b3c4a5d
shard: 0
index: 1834
segment: shard-0-000000000731-1696760000000-1696760042113.r7f
data_end: 209715200
sha256: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
recovered: false
prev: 5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8
key: <the Ed25519 public key, base64>
sig: <the Ed25519 signature, base64>
```

- `sha256` covers the segment from offset 0 to `data_end`, its Data End. That includes the preamble and seal
  record, and excludes the unused pre-allocation.
- `prev` is the SHA-256 of the previous statement's text (every line up to and including the
  `key` line). The first statement of a chain has `prev: start` and a `start_reason` line
  (`new_volume`, `head_lost`, `key_changed`).
- `sig` is Ed25519 over the same text `prev` hashes. Both algorithms are in the JDK, so this adds
  no dependency.
- `key` is the public key, so a reader can tell which key signed. A verifier never trusts it on
  its own: it compares it with the key it was given.

The chain links statements, not segments. A statement is a few hundred bytes, so it can outlive
its segment: the tailer copies it into its output (below), and the chain stays checkable after
the reaper has deleted every segment it covers.

The `.r7f` format doesn't change. The sidecar is a non-segment file, which `FORMAT.md` §3.1
already tells readers to ignore. Its spec goes in `FORMAT.md` as a new section.

## Where the work runs

Off the request path. `rotateSegment` runs inside the shard's lock on a request thread, and it
already only stamps the seal record and hands the rest to a virtual finalizer thread
(`finalizeSegmentAsync`). That finalizer, before it unmaps the segment:

1. waits for the previous finalizer of the same shard, so statements are written in order;
2. hashes the segment up to its Data End and signs the statement;
3. writes `X.r7f.seal` (write to a temporary name, fsync, rename);
4. updates `shard-<id>.chain`, the chain head (chain id, index, hash of the last statement),
   the same way;
5. renames the segment, as it does today.

The cost is one SHA-256 pass over each segment and one signature, which is about 50 µs. With the
SHA-NI intrinsic, SHA-256 runs at roughly 1.5 to 2 GB/s, so a 200 MB segment is about 100 ms of
CPU on a virtual thread. That thread shares carriers with request handling, so it is measured
with `benchmark/bench.sh` on the controlled machine before merge. The bar is no visible change
in throughput or tail.

**Recovery** seals the segments a crash left open and signs them the same way, with
`recovered: true`, continuing from the chain head. A segment that was sealed but never got its
statement (a crash between steps 1 and 3) is still `.flux`, so recovery already handles it.

**Clean close** (`finalizeActiveSegment`) does the same steps synchronously.

**A missing or unreadable chain head** starts a new chain with `start_reason: head_lost`, and
logs a warning. A fresh volume starts one with `new_volume`.

## The tailer

The tailer verifies each segment before it finishes it. It already reads every byte, so it
hashes as it goes and then checks the hash, the signature against its configured key, and the
link to the last statement it saw (kept in its checkpoint). If the `.seal` file isn't there yet,
the tailer waits for it. The finalizer writes it within moments of the seal, and recovery writes
it on the next start.

A failed check is never silent, and it never drops data (the same rule as fingerprinting: the
record isn't changed without a trace). See decision 3.

The reaper deletes `X.r7f.seal` together with `X.r7f`, and `shard-<id>.chain` is never deleted.

## The archive

Segments are transient. The WARC and JSON files are what is kept, so the proof has to reach
them. `SealedFileWriter` already has a hook for a companion file written before the seal (the
CDXJ index uses it). A signing tailer writes `F.seal` next to each output file `F`, in the same
shape as the gateway's statement:

- `sha256` of `F`, and of its CDXJ index when there is one;
- `prev` linking to the tailer's previous output statement (its own chain, kept in its
  checkpoint directory);
- `sources`: the gateway statements, verbatim, of the segments whose records `F` holds.

An auditor holding only the archive and the two public keys can then check three things: each
file is unchanged, no file is missing, and the gateway's chain behind them is unbroken.

## Verifying without a tool to maintain

r7 ships no `verify` command. The statement format is the contract, and the docs give a short
recipe with standard tools:

```bash
sed '/^sig: /,$d' X.r7f.seal > statement
sed -n 's/^sig: //p' X.r7f.seal | base64 -d > sig.bin
openssl pkeyutl -verify -pubin -inkey r7-journal.pub.pem -rawin -in statement -sigfile sig.bin
head -c "$(sed -n 's/^data_end: //p' X.r7f.seal)" X.r7f | sha256sum   # compare with the sha256 line
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
  `fingerprint_key` changes the signing key too, and the chain shows that as
  `start_reason: key_changed`.
- (b) A separate `signing_key`, opt-in. That's more to configure, and gateways without it have
  no proof.

**2. On by default.** This follows from 1(a). With 1(b) it would be opt-in.

**3. What the tailer does when a check fails.**
- **(a) Archive and mark** (recommended): it writes the records anyway, adds
  `WARC-R7-Unverified: <reason>` to every record from that segment (`unverified` in the JSON
  line), logs an error and keeps going. That keeps the data and the evidence of tampering.
- (b) Stop. That's safer in principle, but one damaged file then halts the archive for
  everything after it.

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
2. Tailer verification and decision 3's marking.
3. Tailer output statements.
4. Measurement on the benchmark machine, and the docs: `docs/journaling.md`, a "What it proves"
   section, `design/limitations.md` (the CRC32C line changes) and `FORMAT.md` §9.

Each step is its own PR.
