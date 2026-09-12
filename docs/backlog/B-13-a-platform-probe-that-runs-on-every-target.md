---
id: B-13
title: "parityCheck: a dozen tests that make the platform layer answer on every target"
status: wip
priority: P0
size: M
stage: stage-5-parity-gate
blocked_by: [B-10]
---

# B-13 — parityCheck: a dozen tests that make the platform layer answer on every target

The gate. Not a diff of standard-library behaviour — that is `parity-probe/` and it belongs on a
version bump (B-17) — but ordinary `commonTest` tests that assert the **platform** does what the
code assumes, on whichever target is being built.

The case for this shape is the whole of [research-parity §1.5](../research/research-parity.md).
Three divergences have cost this portfolio real outages, and all three are platform APIs: a socket
that does not resolve a hostname on Kotlin/Native, a client engine with no TLS on native, a Ktor
plugin published for the JVM only. A fourth entry on that list turned out not to be a divergence at
all — a compiler message read as a fact, carried in a build file as the reason for a workaround.
**A transcript diff would have been green through every one of them; a test that merely compiles
and runs would have caught all four.**

- **The decision and its reason.** Roughly a dozen assertions, run on every declared target:
  - resolve a hostname through the real socket API and connect — metrik's §1.6 failure, verbatim;
  - one HTTPS request through the client engine this repository pins, so a missing TLS stack fails
    here and not in production;
  - reference every pinned Ktor server and client plugin, so one published for the JVM only fails
    to resolve on the target that lacks it — this one fails at *compile* time, which is the
    cheapest failure in the set;
  - open, write and re-read a file through the file-system API in use, and read an environment
    variable, because H3 predicts the next divergence is there;
  - touch the coroutine dispatchers the code uses, which is what would have settled §1.5's
    correction in one line.
- **Wired by the convention, not by each repository.** `sborka.kmp` already wires the test gate;
  a gate a repository has to remember to add is a gate two repositories do not have.
- **The task says what it covered, in the result line.** "6 platform assertions on jvm, linuxX64,
  linuxArm64; stdlib behaviour is `parity-probe/`, last run 2026-09-11." A green that does not name
  its scope accumulates trust it has not earned, and this gate's scope is deliberately narrow.
- **No allowlist.** A platform assertion fails with a sentence — "linuxX64 could not resolve
  `postgres`" — and there is nothing to allow: either the capability is there or the code that
  assumes it is wrong. The allowlist belonged to the transcript-diff design and went with it
  (B-12).
- **Rejected: starting a JVM service and a native one and diffing HTTP responses**, which is what
  the brief asked for. No subject has a runnable JVM service: no server module applies the
  `application` plugin or declares a `mainClass`, and shildik's only distribution module declares
  `linuxX64` alone. Building one per repository, to be tested and never shipped, is not a
  three-day box (research D1).
- **Rejected: asserting against a mock or a loopback address.** metrik's §1.6 bug survived for
  months precisely because the test substituted a fake sender and `127.0.0.1` needs no resolver.
  The probe uses a hostname and a real connection or it is not this item.
- **Does not cover** exit codes and argument parsing. Nothing in the portfolio checks them on any
  target, and razves found its own refusals leaving the process with status 0 by hand — a CLI
  conformance question rather than a parity one, and razves' to make (B-14, dropped).

- AC: on tracy, `./gradlew parityCheck` is green on `jvm`, `linuxX64` and `macosArm64`; pointing the
  DNS assertion at a name that does not exist fails on the native targets with a message naming the
  target and the capability, and the whole task adds seconds to a build that already links every
  native binary.
- Anchors: `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts`,
  `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/test.gradle.kts`,
  `tracy/server/src/commonTest/kotlin/io/github/youndie/tracy/server`,
  `metrik/docs/research/research-architecture.md`

Settles hypothesis H3 of the research the first time it finds something §1.5 does not list.

## Answered and half-built, 2026-09-12 — the coordinate exists

The question went to the owner and the answer was **a published coordinate**. Built:
`io.github.youndie.sborka:platform-probe`, a multiplatform module in the root build, documented as
[platform-probe](../services/platform-probe.md).

| | |
|---|---|
| Assertions | resolve a hostname and connect (through ktor's own `InetSocketAddress`), read the environment, round-trip a file, dispatch on `Dispatchers.IO` |
| Targets | `jvm`, `linuxX64`, `linuxArm64`, `macosArm64` |
| Self-test | 4/4 on `jvm (25.0.2+10-69)` and on `native MACOSX ARM64`; a refused connection comes back as a finding rather than an exception |
| Publication | five coordinates — the root plus one per target — wired into `publishToWip` |
| Completeness | all five added to `verifyBuildLogicPublications`, **and the check was confirmed to fail** on a variant that is not there before it was trusted |

**Where "publish" actually lands, which was worth reading rather than assuming.** sborka does not
publish itself to Central: `gradle.properties` has no `sborka.central=true`, and `central.yaml` is a
reusable workflow sborka provides *for other repositories*, checking the target tree's properties.
sborka's own artefacts go to the `wip` Reposilite on **push to `main`**, through
`publish-snapshot.yaml` running `./gradlew publishToWip`. So merging this branch publishes the
coordinate; nothing else has to be done, and nothing irreversible happened here.

**Three things the build fought back about, all documented where they bit:**

- applying Kotlin and ktlint in the same module fails with
  `NoClassDefFoundError: KotlinMultiplatformExtension` — ktlint looks Kotlin up at apply time in the
  loader it was itself loaded by, which is the root's. Fixed by declaring the Kotlin plugin at the
  root with `apply false`, which is the mirror image of the note already in that file about
  `:catalog`;
- a top-level `val` used inside a task action is a script object reference and the configuration
  cache refuses it — the task ran, printed its verdict, and failed the build on the way out;
- `= runBlocking { … }` in a test returns the block's value, and Kotlin/Native refuses a `@Test` that
  returns anything. The same class of defect `sborka.test`'s `DeclaredTests` exists for, caught here
  by the stricter compiler.

**What is left, and it is the half this item is named after.** The coordinate is the delivery
mechanism; `parityCheck` itself is not wired. Still to do: `sborka.kmp` (or a convention of its own)
putting the dependency on `commonTest` and registering the task, one consumer proving it end to end,
and the TLS assertion, which needs an API that takes the repository's own engine rather than a
dependency here. Those are B-25.
