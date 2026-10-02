# Sidecar plugins: one reader, N handlers

> **Plan, not built and not scheduled.** On 2026-10-02 the product focus was set to the audit journal, clean configuration and fixed, predictable routing, with no scripting. That rules out the field-path and pattern language below (the Pebble-style `|first`, `|nth` and `|count` filters, and the projection language). Whatever is built from this should use fixed output formats. See [`README.md`](README.md).

Design under discussion, not yet built. Follows on from `warc.md`; this is the piece that
generalizes what that document decided for WARC+ClickHouse specifically.

---

## Why

`r7-tailer-jsonld` and `r7-tailer-warc` each run their own `R7Tailer`: own checkpoint
directory, own `gracePeriod`/`ttl`, own quarantine and delivery-stall handling. That is two
independent implementations of everything `design/journal-invariants.md` spent eleven review
rounds hardening, where the stated goal after that hardening was the opposite —

> A consumer that reads finished records needs none of that machinery... it moves into the
> sidecar, which is the one place they have to exist, written once instead of re-implemented
> by every consumer. (`warc.md`)

That sentence was written about the r7f → WARC boundary. It applies just as much to the
WARC-sidecar → (ClickHouse, stdout-JSON, archive, whatever comes next) boundary, and for the
same reason: every new output format is currently a new process with its own `R7Tailer`,
which means a new checkpoint file, a new TTL to reason about, and a new place the four
invariants have to be re-verified rather than inherited.

The multi-tailer TTL/`gracePeriod` mechanism already documented on `R7Tailer`'s constructor —
"set the same generous `ttl`... on every tailer sharing the directory" — exists *because* the
tailers can't see each other. It is a time-based approximation of a fact that, inside one
process, is exactly knowable: whether every consumer that needed a segment has finished with
it. That's the concrete win here, not just code reuse.

---

## Shape

```
gateway ──.flux/.r7f──▶ R7Tailer ──fan-out──▶ ├─ WARC writer
                                              ├─ JSON logger  (carries the WARC locator)
                                              └─ basic access log

                              JSON logger's output file ──tailed by──▶ ClickHouse loader,
                                                                        Promtail/Fluent Bit, ...
```

One process. One `R7Tailer`. One `ExchangeCompletionListener` that the tailer talks to, which
fans out to a fixed set of handlers, each independently enabled and configured by its own
`ValidatableConfig` block in the sidecar's YAML — `warc: {enabled: true, ...}`,
`json_logger: {enabled: true, ...}`, `access_log: {enabled: false}`, in the same shape
`routes.yaml` already uses for filters, minus the pluggability. No `ServiceLoader`, no
third-party JAR on the classpath, no discovery. See "SPI: deferred, not designed away" below
for why that's deliberate rather than an oversight.

### Principle: the fan-out is for local, disk-bound writers only

ClickHouse dropped out of the in-process handler list. It doesn't need a seat in the fan-out —
it can tail the JSON logger's output file the same way Promtail or Fluent Bit already do, and
since the JSON logger carries the WARC locator (filename, offset, length) once bodies moved
out of it, that's enough for a ClickHouse loader to batch-insert metadata rows with a valid
pointer back to the archive, entirely outside this process.

That generalizes: **anything that talks to a network service belongs downstream of a file this
process wrote, not inside the fan-out.** A network sink has graded liveness — up, slow,
degraded, down for a while, recoverable — which is exactly the case the per-handler
checkpoint machinery in the previous draft of this document existed to handle. Keeping such
sinks out of the fan-out entirely means the sidecar itself never has to reason about partial
liveness at all; it gets pushed to where it already lives today, as an independent process
reading finished files and self-healing by re-reading them.

### The three handlers, for now

Three cover everything that exists today:

| Handler | Source today | Failure mode |
| --- | --- | --- |
| Access log | new — pattern-based, logback-`PatternLayout`-style, configured on the sidecar | local disk full/unwritable |
| JSON logger | `r7-tailer-jsonld`'s `DebugJsonWriter`, metadata-only per the discussion above, fields projectable (see below) | local disk full/unwritable |
| WARC | `r7-tailer-warc`'s `WarcExchangeWriter` | local disk full/unwritable, or fsync-before-seal failure |

Both the access log and the JSON logger are configurable; WARC isn't — its four-record shape
is the IIPC-spec envelope, not something this repo gets to reshape per deployment.

### Per-route shaping is a downstream concern, not a sidecar feature

The access log stays one pattern, sidecar-wide — no per-route pattern selection. nginx can do
that cheaply because the location block handling the request *is* the log call site; here the
sidecar is a separate process reading a flattened stream, and "which route" is a string pulled
out of the generic `attributes` bag by convention (`gateway.route.id`, set in
`GatewayPipeline.tagExchangeAttributes` before every `journal.endExchange` call — confirmed
present for every exchange, not gated by `JournalLevel`), not a first-class dispatch key a
pattern compiler can switch on cleanly.

The JSON logger already carries everything a per-route line would need — full headers, all the
metadata fields, and `attributes["gateway.route.id"]` — so per-route (or per-app,
per-header-of-interest) shaping is a downstream filter over the NDJSON stream, the same
"customization lives downstream of the file this process wrote" principle already applied to
ClickHouse. In practice that's a one-line `jq`, no new mechanism needed:

```sh
jq -r '"\(.remote_address) \(.client.method) \(.client.path) -> \(.status) \(.response_total_bytes)B (\(.duration)s) route=\(.attributes["gateway.route.id"])"'
```

That's a real, working example against today's actual `DebugJsonWriter` output shape, not a
hypothetical — every field named above already exists in the JSON logger's current schema.

### Access log: a compiled pattern, not a hardcoded format

"Access log" doesn't mean a single fixed CLF line — it means a pattern definition in the same
spirit as logback's `PatternLayout`: a conversion-pattern string (`%h %t "%r" %s %b`-style)
parsed **once, at config load**, into a fixed sequence of converters, each bound to one field
(remote address, timestamp, request line, status, byte count, ...). Per-exchange work is then
just walking that pre-built converter array and appending — no string parsing, no reflection,
no per-request pattern interpretation, which is what makes logback's approach fast enough to
use as a default in the first place and is the only way this stays true to "no allocation in
the hot path." A deployment that wants literal CLF just configures the CLF pattern; one that
wants something leaner or richer configures a different one — one pattern for the whole
sidecar, since (per the correction above) there's no per-route signal to vary it by. Fixed
built-in presets (`clf`, `combined`) are worth shipping as named shortcuts for the pattern
string, but the mechanism
underneath is the compiled pattern, not a hardcoded formatter.

### One field-path registry, two consumers

The access-log pattern's per-field converters and the JSON projection's dotted paths shouldn't
be two independent vocabularies to define, document, and keep in sync as the schema grows.
Both compile against the same underlying **field-path registry** — the one place that knows
every field the exchange record exposes, its dotted path (`client.request_headers`,
`status`, `duration_ms`, `upstream.response_headers`, ...), and a direct accessor for it. That
registry is exactly what the JSON projection's allow/deny gates are already built from; the
access-log pattern compiler is just a second consumer of it:

* A handful of conventional named tokens (`%h`, `%t`, `%s`, `%b`, `%r`, ...) stay, as sugar —
  some of them are genuinely composite (`%t` applies a timestamp format, `%r` assembles method
  + path + protocol from three underlying fields), not a bare field lookup, so they earn their
  own converter rather than being pure aliases.
* Anything else reachable via a path gets a generic token, e.g. `%f{status}` or
  `%f{upstream.response_headers['api-signature']}`, resolved against the same registry the
  JSON projection uses — no separate enumeration of "what fields exist" to maintain twice.

That example is also the answer to the escaping question: the path grammar has two distinct
segment kinds, not one. A bare `.identifier` segment (`upstream`, `response_headers`,
`status`) walks a **fixed record field** — known at compile time, one direct accessor per
segment. A `['quoted-key']` segment does a **map lookup** where the key is arbitrary data the
schema doesn't know ahead of time — a header name, an attribute name — and it's only ever
valid as the *last* segment, since headers/attributes are `Map<String, String>`-shaped, not
further nestable. This also settles the earlier "must resolve to a scalar" rule cleanly:
`upstream.response_headers` (bare, no bracket) names the whole map and is a config error in
the access log; `upstream.response_headers['api-signature']` names one value and is a scalar
*in the access log specifically* — headers aren't actually single-valued (below).
Header-key lookup follows the same case-insensitive matching every other header comparison in
this codebase already uses — no new comparison rule to introduce. A missing key at runtime
(header not present on that exchange) resolves to empty, the same as any absent CLF field.

`GatewayHeaders` (`r7-api`) is a genuine multi-value container — `getFirst`/`getAll`, not a
plain map — because HTTP headers can legitimately repeat (`Set-Cookie` is the sharp case: RFC
7230 explicitly forbids comma-joining it, unlike most other repeatable headers). `DebugJsonWriter`
already reflects that: `request_headers`/`response_headers` go through `GatewayUtils.toMap`,
which is `Map<String, List<String>>`, all values, in order — not first-only. So the two
consumers have to resolve `['key']` differently, and that has to be stated, not left implicit:

* **JSON projection** — `['key']` yields the full ordered list, same as the unprojected field
  does today. Nothing changes here; a bracket lookup is just a narrower gate on the same map.
* **Access log** — a text line has no room for a list, so a bare `['key']` needs one value
  chosen. Pebble (and Twig/Jinja before it) already has the right convention for this: a
  pipe-separated filter after the expression — `{{ value | first }}` — rather than reusing
  `[...]` for a second, numeric purpose. That's the fix worth taking, and it resolves last
  turn's ambiguity more directly than an index-in-brackets would: reserve `[...]` for exactly
  one thing, a quoted string map key, and put value-selection in a named filter instead —

  * `['key']` — sugar for `['key']|first`, documented as such, not implicit magic.
  * `['key']|first`, `['key']|last` — first/last of `getAll`'s order.
  * `['key']|nth(1)` — the *n*-th value (0-based), for the rare case neither first nor last is
    what's wanted.
  * `['key']|count` — the number of values, itself a legitimate scalar (e.g. "how many
    `Set-Cookie` headers did upstream send"), not a value from the list at all.

  With `[...]` reserved solely for string-keyed map lookup, there is no bracket-with-an-integer
  form at all, so the "is `[0]` a list index or a character index" question from last turn
  can't arise structurally — not because an invariant is upheld carefully, but because the
  grammar has no second meaning for `[...]` to collide with in the first place. (Pebble's own
  `foo[0]` *does* overload the bracket this way, safely, because it resolves types dynamically
  at render time — exactly the per-request reflection cost this design set out to avoid, so
  that part of Pebble's grammar isn't worth copying.) Every filter above is still
  compile-time-literal — resolved once, at config load, into one direct accessor (`list.get(0)`,
  `list.get(size-1)`, `list.size()`) — same performance shape as everything else in this
  registry; only the spelling is borrowed from Pebble, not its runtime model.

  What's deliberately *not* offered is a `|join`/`|all` filter that concatenates every value
  onto the line: that has no safe default separator (comma silently breaks `Set-Cookie`-shaped
  headers per RFC 7230), and unlike `first`/`last`/`nth`/`count`, it reopens exactly the "which
  value(s) am I even looking at" ambiguity this section exists to close. Same reasoning as the
  SPI deferral above: a filter nobody has asked for yet, with no safe default, is worth leaving
  out until a real need names its own separator explicitly — not designing in now. An operator
  who needs every value, joined or not, still has the JSON logger's full list.

The same bracket syntax is worth extending to the JSON projection too, not just the pattern
language, since it's the same registry: today the projection can only gate a whole subtree
(`client.request_headers` in or out); `client.request_headers['user-agent']` lets an operator
keep exactly one header (as a list, still) without paying for the rest of the map. Same
accessor, same gate mechanism, just a narrower one.

Stepping back: index and count cover the legitimate, boundedly-scalar cases without opening
the join/separator can of worms. The line stays where the earlier framing put it, just drawn
more precisely — an operator who needs *one* value (first, last, nth) or *how many* there were
can stay in the access log; the moment they need *all of them, together*, that's still the
signal they've outgrown a single text line, not a case to design a join syntax around. The
JSON logger already handles that correctly (full list, structured) with no separator to argue
about.

This has to keep the same performance shape both mechanisms already committed to: the pattern
compiler resolves each `%f{...}` token to a direct accessor **at config load**, exactly like
the JSON projection's gates, not via a per-request map/reflection lookup. A dotted path is
config, resolved once; per-exchange cost is calling the same small set of accessor functions
either mechanism would have called anyway.

*(The start of the next paragraph was lost when this document was first committed. What
survives is below.)*

…between "working" and "the disk this process writes to is out of space" — there's no slow,
no partial, no "up but behind." That's the point of keeping the fan-out local-only, and it's
what lets the checkpoint section stay simple.

### WARC mode: `off` / `auto` / `on`

**Correction:** this was previously written as a per-route setting; it isn't, and the reason
is more precise than "the journal has no route field." `journal.fbs` doesn't carry a dedicated
route column on any table — but `GatewayPipeline.tagExchangeAttributes` does set
`gateway.route.id` into `attributes()` unconditionally, before every `journal.endExchange`
call, so the route *is* recoverable, just as a generic attribute key by convention, not a
typed schema field. `warc.mode` stays a sidecar-wide setting anyway, by choice rather than
impossibility: see "Per-route shaping is a downstream concern" above for why building a
route-keyed runtime dispatch table into the sidecar isn't worth it even though the data exists
to do it.

Rather than one global completeness rule, `warc.mode` is a tri-state, replacing a plain
`enabled: boolean`:

* **`off`** — no WARC records, ever. No locator in JSON either (see below).
* **`auto`** — a WARC record is written for an exchange *iff* `has_body` is true for it, i.e.
  either leg carried a payload. Bodyless exchanges get no WARC record at all, by design.
* **`on`** — every exchange gets a full four-record WARC set regardless of body, the original
  "always complete" behavior. Bodyless exchanges still get all four records; records 1 and 4
  just carry `WARC-Truncated: unspecified`-or-similar instead of payload bytes, the same way
  legs 2 and 3 already do for every exchange.

`has_body` itself is computed once, per exchange, regardless of mode, and is always present in
the JSON logger's output (see the projection section below) — it's what turns `auto`'s
selective absence from being ambiguous. Invariant #4 in `journal-invariants.md`
("deletion needs proof, not absence of evidence") is satisfied per-mode, not just under `on`:
under `auto`, "no WARC record for this exchange" is always explained by a `has_body: false`
sitting right next to it in the JSON row, never silently indistinguishable from a lost record.
A `has_body: true` row with no resolvable locator under `auto` is exactly the bug class
invariant #4 exists to catch — worth an assertion/integrity-listener callback (open question 5
below), not a silent gap.

`JournalLevel` still governs a separate axis — how much of the exchange is captured at all —
and stays a genuinely per-route, config-time setting on the gateway side; `warc.mode` only
governs whether the captured exchange gets an archival copy, and does so sidecar-wide, not
per route, for the same reason the access log's pattern does.

### Requirement: the JSON logger's locator must always resolve when it's present

The JSON logger only carries a locator (filename, offset, length) — the body, and the full
request/response record, lives in WARC. What "resolves" means depends on `warc.mode`:

* `warc.mode: off` — the JSON schema must not include a locator field at all. Nothing to
  resolve, nothing to fail.
* `warc.mode: on` — every row's locator resolves, unconditionally.
* `warc.mode: auto` — a row's locator resolves whenever `has_body` is true for it; when
  `has_body` is false, the locator field is legitimately absent/null on that row, not an error.

The foot-gun this still needs to guard against is a JSON *field projection* (below) that drops
`has_body` or the locator field while `warc.mode` is anything but `off` — that reintroduces the
exact ambiguity the mode was designed to avoid. This is validated at startup the way
`AGENTS.md` prescribes for config constraints a lower layer enforces — named field, in the
style of `ServerConfig.StorageConfig`'s power-of-two check — rather than discovered later as a
`null`/missing-file error downstream. Concretely: a JSON projection that excludes `has_body`,
or excludes the locator field while `warc.mode != off`, fails validation outright.

This also resolves open question 1 from the previous draft of this section: "JSON standalone,
no locator at all" isn't a separate mode to design — it's just `warc.mode: off` plus a schema
that (correctly) has no locator field to project.

### Field projection: an effective filter, not build-then-cut

Projection has to mean "skip the work," not "write everything, then drop fields from the
output." `DebugJsonWriter` already streams straight to a `JsonGenerator` — there's no
intermediate tree being built and pruned today — so the right shape is: the allow/deny list
compiles, once at config load, into a fixed set of boolean gates (one per field/section), and
each write site in the handler is guarded by its gate, skipping not just the
`generator.writeXxx` call but whatever produced the value — reading `upstream.request_headers`
off the exchange, computing a digest, iterating `attributes()` — when that gate is off. This
is the same "no allocation, no object-graph construction in the hot path" constraint `AGENTS.md`
already states for the request path; a projection that's fast to *serialize* but still pays to
*compute* every deselected field defeats the point of projecting at all. A flat list of dotted
field paths (`client.request_headers`, `attributes`, `upstream`, ...) is enough to build these
gates — not full JSONPath, whose wildcards/filters/predicates solve a problem this fixed,
known schema doesn't have, for the cost of a query-language dependency and a much larger
footgun surface than a short YAML allow/deny list needs.

Dropping a field is the operator's call and this repo carries no liability for it — the same
disclaimer that already applies to `JournalLevel` on the gateway side (drop to `METADATA` and
headers are gone from that point on, no undo) applies one layer downstream here. If a field
was configured out, it cannot be recreated later; that's true of every logging system's
verbosity/retention knob, not a new risk r7 is introducing.

The one exception is the two fields the `warc.mode` invariant above depends on: `has_body` and
the locator (when `warc.mode != off`). Those are validated, not projectable — excluding either
one is a config error, not a lossy-but-valid projection, because it would silently reintroduce
the exact absence-is-ambiguous problem `warc.mode: auto` exists to avoid. Every other field is
fair game to drop, and to skip computing.

### SPI: deferred, not designed away

`r7-core` already has a real `ServiceLoader`/`@AutoService` extension point
(`docs/extensibility.md`) for filters and predicates, because third-party filters are a real,
recurring need. Nothing here demonstrates that same need yet — three fixed handlers cover the
whole current use case, and anything network-facing already has a better home as a downstream
file tailer (previous section), which needs no extension point in this process at all.

Building the `ServiceLoader` surface now would mean designing a public interface, a config
schema for arbitrary third-party handlers, and a versioning story for all of it, against zero
real implementors. `journal-invariants.md`'s closing section put it as "an invariant that can
be designed away is worth more than an invariant that is enforced well" — the same logic
applies one level up: an extension mechanism nobody has asked for yet is dead weight to
maintain and a wider surface to keep backward-compatible, for a flexibility bet that may never
pay off.

The `ExchangeCompletionListener`-based fan-out internal to the three built-ins is still the
shape a real SPI would eventually wrap — promoting it to `@AutoService` later is a boundary
change, not a rewrite, if and when an actual second implementor shows up wanting something
none of the three built-ins or a downstream file tailer can do.

---

## What a handler failure means for progress

Today, "did the consumer accept the record" is binary — `ExchangeCompletionListener.onComplete`
either returns or throws, and `DeliveryGuard`'s rule is absolute: a throw means *offer this
entry again*, nothing after it advances. That rule was designed for exactly one consumer, and
it turns out to still be the right one here.

The earlier draft of this section proposed independent per-handler checkpoints, a bounded
in-memory backlog, and a required/best-effort split — machinery to let one handler's progress
lag another's without either blocking the fast one or losing the slow one's data. All of that
solves graded liveness. Once every in-process handler is a synchronous local writer with only
one failure mode (disk is full, for every handler on this host, at the same time), there is no
graded liveness left to solve: a single shared checkpoint, one high-water mark per segment,
advanced only once every handler has written the record — the same fail-closed rule
`R7Tailer` already applies to a single consumer — is the correct answer, not the simplest
approximation of one.

That single checkpoint is also strictly simpler to build and to reason about than anything
in the earlier draft, and it costs nothing in practice: because all three handlers are doing
comparable local-disk work, they finish within the same tick as each other under normal
operation, so "wait for the slowest" is never actually waiting.

**The escape hatch, if it's ever needed:** if a future in-process handler genuinely doesn't fit
this mold — does real work slower than a disk append, or has its own partial-failure states —
the answer is still "make it a downstream file tailer instead," per the principle above, not
"reopen the per-handler checkpoint machinery." That machinery isn't deleted from this document
by accident; it's deferred until something demonstrably needs it, on the theory from
`journal-invariants.md`'s closing section that an invariant designed away is worth more than
one enforced well — the same logic applies to a whole subsystem of bookkeeping.

---

## Deletion, revisited

`R7Tailer`'s current deletion rule is per-tailer: "have I read this segment fully" (plus
`gracePeriod`/`ttl` as a hedge against siblings it can't see). With every handler known to the
one process, and every handler in lockstep by construction (previous section), this tightens
to: a segment is eligible for deletion once every handler's shared checkpoint has passed it,
full stop — no time-based hedge needed, because there are no siblings this process can't see
anymore. `ttl` is still worth keeping as an operator's override for a wedged handler, but it
stops being the *primary* mechanism multi-consumer safety rests on; it becomes what it always
should have been, a ceiling.

This is a strictly stronger form of invariant #4 (`journal-invariants.md`, "destroying data
requires proof") — proof here becomes "every handler has acknowledged this byte range," rather
than "nobody's touched it in `ttl`."

---

## What doesn't change

* The gateway is untouched; this is entirely inside the sidecar process.
* `R7Tailer`'s read/reassembly loop, `DeliveryGuard`, and the four invariants are unchanged —
  this proposal is about what sits *behind* `ExchangeCompletionListener.onComplete`, not about
  how records get there.
* WARC stays the archive of record; nothing here changes `warc.md`'s record shape, rollover,
  or fsync-before-seal rule.

## ClickHouse ingestion, and anything like it, stays out of this repo

`warc.md` already said the ClickHouse loader "is probably ~100 lines of glue rather than
something to install, which is exactly why it is not r7's problem." That holds even more once
it's reading the JSON logger's plain NDJSON rather than binary journals: any language can
tail a file and batch-insert rows, so a Python/Go/Rust script — not a JVM module — is the right
shape, and it never needs to know anything about `.flux`/`.r7f`/the four invariants at all.
It only needs the JSON logger's schema to be documented well enough to reimplement against,
the same reasoning `warc.md` gave for why `WARC.md` has to exist if consumers are external:
"If consumers are external, the file is the contract." A `JSON.md` beside it, specifying the
locator fields and which columns are guaranteed at which `JournalLevel`, is the deliverable —
not a ClickHouse-specific writer living in this repo.

## What's still open, for the next round of iteration

1. Exact config shape for the three toggles: nested under one `handlers:` block, or as three
   independent top-level sections mirroring `server.yaml`'s existing structure?
2. Whether the shared checkpoint's unbounded stall-and-retry needs a `ttl`-style escape hatch
   now that an operator has one process to look at instead of guessing which of several is
   stuck — or whether that's exactly the case where an operator paging in is correct, same as
   today's single-consumer stall.
3. The JSON logger's exact schema (see above) — `warc.md`'s "index layer" section already
   lists most of the fields a ClickHouse row needs (status, method, path, route, upstream,
   the three durations, byte counts, request id) plus the locator; this needs to become a
   `JSON.md` companion to `FORMAT.md`/`WARC.md`, not stay implicit in `DebugJsonWriter`.
4. Whether a handler write failure needs its own integrity-listener-style callback (mirroring
   `JournalIntegrityListener`) so an operator sees *which* handler is refusing, not just that
   the segment is stalled.
5. Allow-list vs deny-list for the JSON field projection (or support both) — a deny-list is
   less typing for the common case (drop bodies, keep everything else) but an allow-list is
   safer by default (new fields added later are excluded until explicitly opted in).
6. Whether `['key']` map-lookup segments should also support a fallback/default syntax
   (e.g. `%f{...}:-}`-style) for missing keys, or whether "resolves to empty" is enough and a
   default belongs in the surrounding literal text of the pattern instead.

No implementation until these are settled.
