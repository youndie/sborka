---
id: B-13
title: "parityCheck: a dozen tests that make the platform layer answer on every target"
status: open
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
- **Does not cover** razves, which is a CLI and gets the process-level gate — B-14.

- AC: on tracy, `./gradlew parityCheck` is green on `jvm`, `linuxX64` and `macosArm64`; pointing the
  DNS assertion at a name that does not exist fails on the native targets with a message naming the
  target and the capability, and the whole task adds seconds to a build that already links every
  native binary.
- Anchors: `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts`,
  `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/test.gradle.kts`,
  `tracy/server/src/commonTest/kotlin/io/github/youndie/tracy/server`,
  `metrik/docs/research/research-architecture.md`

Settles hypothesis H3 of the research the first time it finds something §1.5 does not list.
