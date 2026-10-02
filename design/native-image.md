# No native image

**Decided 2026-10-01.** r7 ships as a JVM application only. The GraalVM native image
(`Dockerfile.native`, the `native` Maven profile, `R7ReflectionFeature`, the
`r7-gateway-native` image) was removed.

## Why

r7 is a long-running gateway that journals what it proxies. What a native image buys —
millisecond startup and a smaller resident footprint — matters little for that, and what it
costs kept growing.

- **Throughput.** Without a JIT, the hot path runs far slower. On POST traffic journaled at
  `FULL` (5 s runs, one machine): about 41,000 req/s uncompressed against about 108,000 on the
  JVM, and about 8,400 req/s with zstd against about 66,000 — zstd-jni's FFM calls cost far more
  in the native image than in a warm JVM.
- **Startup** is covered on the JVM by the JDK's AOT cache, which the gateway image is built
  with (`Dockerfile.jvm`, `docs/performance_tuning.md`).
- **Memory.** The native image's smaller heap and RSS are dwarfed by the page cache the journal
  occupies under load, which the container's memory limit is charged for anyway.
- **It drifted, silently.** Three native-only bugs were found in one session, none caught by CI:
  fault-ahead's `madvise` downcall was never registered, so fault-ahead was off; logback's
  `LevelChangePropagator` was not registered for reflection, so startup logged an error; and
  with journal compression on, every journaled request failed with a 500. Each new dependency
  or FFM call needed reachability metadata kept in step by hand.
- **It blocks extensions.** r7 is extended by dropping filter and predicate jars on the
  classpath (`docs/extensibility.md`). A closed-world image cannot load them.
- **It is another invariant on every level:** a second build, an Oracle-licensed builder (G1 is
  Oracle GraalVM-only), a second image, a second integration-test mode, and a second answer to
  "does this work?" for every change.

## If the question comes back

Measure first: the JVM with an AOT cache, against the requirement that prompted it. A native
image would need, at least, reachability metadata for every FFM downcall (recorded with the
tracing agent), a story for extensions, and a CI job that runs traffic through the image rather
than only building it.
