---
id: B-10
title: "Move one native-only server test to commonTest and find out what stops it"
status: done
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
  and katcher is mostly a `git mv` and B-13 has a second half to run on. If it does not, *what* stops it —
  sqlx4k without a JVM SQLite driver, a `/tmp` path, a missing Ktor artefact — is the most valuable
  fact this strand can produce, because it is a platform-layer divergence found by trying rather
  than by reading.
- **Rejected: moving all nineteen at once.** A red build with nineteen failures says nothing about
  which cause is which, and this is a question, not a cleanup.
- **Does not cover** deciding where those tests should finally live. That depends on this answer,
  and on whether B-13's platform assertions need them in `commonTest` at all.

- AC: a paragraph in [research-parity](../research/research-parity.md) §1.5 replacing "It is H2, not
  a finding" with what actually happened, and the moved file either committed or reverted with the
  reason beside it.
- Anchors: `metrik/server/src/nativeTest/kotlin/io/github/youndie/metrik/server/query/QueryRoutesTest.kt`,
  `metrik/server/src/commonMain/kotlin/io/github/youndie/metrik/server/Application.kt`,
  `katcher/server/src/nativeTest`

Settles hypothesis H2 of the research.

## Done, 2026-09-11 — it compiles and passes, and the interesting part is what it runs against

`QueryRoutesTest.kt` moved from `server/src/nativeTest` to `server/src/commonTest` in metrik,
unchanged, on branch `test/server-tests-on-both-targets` (`c5f068e`):

| Task | Result |
|---|---|
| `:server:jvmTest` | passes — "every @Test in 1 class(es) was executed" |
| `:server:linuxX64Test` | still passes |

So H2 is confirmed: the suite was in `nativeTest` by habit. Nothing required it — `openDatabase` is
in `commonMain` and sqlx4k publishes a JVM variant. The remaining seven in metrik and eleven in
katcher are a `git mv`, which is B-23.

**And the JVM run logs the finding this item did not go looking for.** It warns about
`org.sqlite.SQLiteJDBCLoader` loading `sqlite-jdbc-3.53.2.0.jar`: sqlx4k on the JVM is **Xerial's
sqlite-jdbc**, and on Kotlin/Native it is the Rust driver. The same test on the two targets
therefore runs against two different SQLite implementations. That cuts both ways and both are worth
having written down:

- a green `jvmTest` says **less** about the native binary than it looks — the storage layer under it
  is not the one that ships;
- and these tests, once they run on both, are the only thing in the portfolio that would notice the
  two drivers disagreeing. Nothing did before.

This is a platform-layer divergence of exactly the kind [research-parity §1.5](../research/research-parity.md)
is about, found by moving one file.
