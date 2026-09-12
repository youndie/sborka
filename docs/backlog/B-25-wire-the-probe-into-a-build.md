---
id: B-25
title: "Wire platform-probe into a build: the task, one consumer, and the TLS assertion"
status: wip
priority: P0
size: M
stage: stage-5-parity-gate
blocked_by: [B-13]
---

# B-25 — Wire platform-probe into a build: the task, one consumer, and the TLS assertion

[platform-probe](../services/platform-probe.md) exists and passes on every target it declares. What
does not exist is a repository that runs it. A library nobody applies is the same green as a gate
nobody wired.

- **The decision and its reason.** The convention puts the dependency on `commonTest` and registers
  `parityCheck`; the repository gets it by taking the plugin rather than by remembering. Same
  arrangement as the test gate, and the same reason: a gate a repository must remember to add is a
  gate two repositories do not have.
- **One consumer, end to end, before any of the others.** tracy: 37 of its 39 tests are already in
  `commonTest` and its CI already runs them on `jvm`, `linuxX64` and `macosArm64`, so the marginal
  cost is a dependency and a task. A red run there has to name the target and the capability, not a
  stack trace.
- **The task reports what it did not cover, in the result line.** The report object already carries
  that list; the task has to print it, or the gate accumulates trust it has not earned — which is
  Risk 1 of the research and the reason the list is in the API rather than in a comment.
- **The TLS assertion needs an API decision, not code.** The engine is the repository's — `curl` on
  native, `cio` on the JVM in metrik's case — so the probe cannot depend on one. Either the
  assertion takes an `HttpClient` the consumer passes, or it stays out and the report keeps saying
  so. Taking a client is the smaller surface; deciding it is this item.
- **Rejected: wiring it into `sborka.kmp` unconditionally.** `sborka.kmp` is applied by library
  modules that have no platform to probe, and a network assertion in their test run is a flaky test
  they did not ask for. It belongs where a service asks for it.
- **Does not cover** the Ktor-plugin assertion, which fails at compile time and therefore needs
  nothing at run time — a repository that names its plugins in `commonMain` already has it.

- AC: `./gradlew parityCheck` on tracy runs the probe on `jvm`, `linuxX64` and `macosArm64`, prints
  what it covered and what it did not, and goes red when pointed at a hostname that does not exist —
  with a message naming the target and the capability.
- Anchors: `platform-probe/src/commonMain/kotlin/io/github/youndie/sborka/probe/PlatformProbe.kt`,
  `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts`,
  `tracy/server/src/commonTest`

## Done for tracy, 2026-09-12 — with one target unverified and one ordering constraint

**sborka.** A convention of its own, `sborka.parity`, rather than a line in `sborka.kmp`: that one is
applied by library modules with no platform to probe, and a network assertion in their test run is a
flaky test they did not ask for. It creates a `parityProbe { host; port }` extension, puts
`platform-probe` on `commonTest` at `SborkaVersion.CURRENT` — the same arrangement `sborka.lint` uses
for the rule set — and registers `parityCheck` over the test tasks that already exist. `parity` is in
the root's plugin-marker list, so the completeness check sees it: 21 artefacts, 10 markers.

**tracy.** `:server` applies it, configures `localhost`, and carries `PlatformTest`. Result:

```
parityCheck: platform probed at localhost:0 on jvm, macosArm64; NOT covered — TLS through this
repository's own engine, the Ktor plugins it pins, and standard-library behaviour
```

**Negative control, because a gate nobody has seen fail is a gate nobody has seen.** Pointed at
`nowhere.invalid` it goes red with the capability named:
`resolve-and-connect(nowhere.invalid:54917): UnresolvedAddressException`.

**Two things the build got wrong first, and the second is the reason the first test exists.**

- `doFirst { environment(...) }` on a test task compiles, runs, and the variable does not arrive. Set
  when the task is realised instead. What said so was tracy's plumbing assertion — without it the
  probe would have fallen back to its default host and passed, which is a lookup test passing
  because it looked nowhere.
- The task's summary named `linuxX64` while running on a Mac, because it listed task names rather
  than tasks that can run. A gate reporting coverage it did not have is the failure it exists to
  prevent. It now filters on `enabled`.

**`linuxX64` verified too**, on the Linux box once it came back:

```
parityCheck: platform probed at localhost:0 on jvm, linuxX64; NOT covered — …
```

Which also checks the `enabled` filter from the other side: the Mac reported `jvm, macosArm64` and
the Linux box reports `jvm, linuxX64`, each naming only what it could actually run.

**Ordering.** tracy's catalog still points at a sborka that has neither `sborka.parity` nor
`platform-probe`, so its branch does not resolve until sborka publishes — which happens on a push to
`main` through `publish-snapshot.yaml`. Verified here against `publishToMavenLocal` at a throwaway
version; the scaffolding (`mavenLocal()`, the local version) was removed before committing, and the
branch carries only the real change.

**The TLS assertion, 2026-09-12.** Built, and the API question answered the other way round from
this item's own suggestion: not an `HttpClient` the consumer passes, but a **lambda**. `reachesOverTls(url) { client.get(it) }`.
Taking a client would have put `ktor-client-core` on `platform-probe` and coupled its version to
every consumer's; a lambda leaves the engine entirely where it belongs — which is the point, since
`ktor-client-cio` has no TLS on Kotlin/Native and a probe carrying its own engine would answer for
one nobody ships.

`probePlatform` takes `tlsUrl` and `tlsRequest` as an optional pair, and **the uncovered list is now
computed from the findings rather than fixed**: hand a TLS request in and the TLS line disappears
from it. The test that pins that is the one worth having — a fixed list would have kept claiming TLS
was uncovered on a run that covered it, which is wrong in the reassuring direction and is the half
nobody re-reads.

**Still open: the other four subjects.** The natural next one is metrik, whose `:server` is the
reason the assertion exists — `ktor-client-curl` on native and `ktor-client-cio` on the JVM through
`expect`/`actual`, because CIO's native half has no TLS. It cannot take this until sborka publishes
again: the API landing in this commit is newer than 0.4.0.57, which is what every consumer resolves
today.
