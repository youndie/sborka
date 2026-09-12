---
id: B-25
title: "Wire platform-probe into a build: the task, one consumer, and the TLS assertion"
status: open
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
