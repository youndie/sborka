---
id: B-10
title: "Move one native-only server test to commonTest and find out what stops it"
status: open
priority: P0
size: S
stage: stage-4-parity-evidence
---

# B-10 — Move one native-only server test to commonTest and find out what stops it

metrik's server has **no** tests in `commonTest` and eight in `nativeTest`; katcher's has ten and
eleven ([research-parity §1.5](../research/research-parity.md)). The JVM target of those modules
compiles the production code and runs nothing against it. So for two of the five subjects the
premise of the whole brief — "both binaries pass their unit tests" — is not true today: only one
binary has any.

Nothing in the type system explains it. `openDatabase`, the one call that looked platform-specific,
is in `commonMain` in both repositories, and neither records why the tests sit where they do.

- **The decision and its reason.** Move a single file — `metrik/server/src/nativeTest/.../
  QueryRoutesTest.kt` is the smallest — to `commonTest`, run `jvmTest`, and write down what
  happens. This is a probe, not a migration: the answer is wanted before anyone plans the rest.
- **The interesting outcome is the failure.** If it compiles and passes, the parity gate for metrik
  and katcher is mostly a `git mv` and B-13 gets much cheaper. If it does not, *what* stops it —
  sqlx4k without a JVM SQLite driver, a `/tmp` path, a missing Ktor artefact — is the most valuable
  fact this strand can produce, because it is a platform-layer divergence found by trying rather
  than by reading.
- **Rejected: moving all nineteen at once.** A red build with nineteen failures says nothing about
  which cause is which, and this is a question, not a cleanup.
- **Does not cover** deciding where those tests should finally live. That is B-13's problem and it
  depends on this answer.

- AC: a paragraph in [research-parity](../research/research-parity.md) §1.5 replacing "It is H2, not
  a finding" with what actually happened, and the moved file either committed or reverted with the
  reason beside it.
- Anchors: `metrik/server/src/nativeTest/kotlin/io/github/youndie/metrik/server/query/QueryRoutesTest.kt`,
  `metrik/server/src/commonMain/kotlin/io/github/youndie/metrik/server/Application.kt`,
  `katcher/server/src/nativeTest`

Settles hypothesis H2 of the research.
