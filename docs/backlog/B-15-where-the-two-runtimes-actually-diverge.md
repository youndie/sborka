---
id: B-15
title: "The page: where the JVM and Kotlin/Native actually diverge, and it is not in the stdlib"
status: open
priority: P1
size: S
stage: stage-5-parity-gate
---

# B-15 — The page: where the JVM and Kotlin/Native actually diverge, and it is not in the stdlib

The brief asked for "Kotlin/Native vs JVM: the behaviour differences we test for". The research
found a better headline, and the difference between the two is the point of the page.

Everyone writing this page writes the stdlib table. This one has it — 129 probes, 17 rows apart,
every claim citing a committed transcript — and then says the thing the table cannot: **none of
those seventeen has ever cost this portfolio anything.** The three that did are platform APIs: a
socket that does not resolve a hostname on Kotlin/Native, a client engine with no TLS, a Ktor
plugin published for the JVM alone. Each has a story with a price attached, and those stories have
no equivalent anywhere else — [research-parity §1.5](../research/research-parity.md) is the text.

- **The decision and its reason.** Structure the page around the four cases, not the table. Each
  one: the code that looked correct, what the platform did instead, how long it was wrong, and how
  it was found. The stdlib table goes in as the *negative* result — strings, hashing and seeded
  randomness identical, number formatting identical on any JDK from 19 — because a reader who
  believes `Double.toString` might differ writes defensive code forever, and 129 rows retire that
  belief.
- **The fourth case is the best one and the least flattering.** `Dispatchers.IO` was recorded as
  `internal` on native, in a build file, as the reason for a workaround. It is not: it is an
  extension property that needs its own import, and without the import the compiler names the
  internal member of the same name. A true-sounding sentence backed by "I checked by compiling"
  survived unchallenged. That is a more useful thing to publish than another divergence.
- **Every row names a probe id, a transcript and its versions** — Kotlin 2.4.10, kotlinx-
  serialization 1.11.0, kotlinx-datetime 0.8.0, and **the JDK**, because five rows move between
  JDK 17 and JDK 25 and `\bé` is not a JVM/native difference at all on JDK 17. A page that names
  Kotlin and not the JDK is wrong for those rows in a way no reader can detect.
- **Rejected: the title the brief proposed.** "The behaviour differences we test for" promises a
  checklist and delivers one; the finding is that the checklist is the wrong artefact.
- **Does not cover** the gate. How this portfolio keeps the list true is B-13, and it is a sentence
  on the page rather than its subject.

- AC: a page on kotlin.website whose every claim names a probe id present in
  `docs/research/parity-probe/results/` or a case in research §1.5, with the versions and the JDK
  each was measured at, and whose lead is the platform layer rather than the table.
- Anchors: `docs/research/parity-probe/results/`, `docs/research/research-parity.md`,
  `metrik/docs/research/research-architecture.md`,
  `kotlin-website/site/src/jsMain/resources/markdown/blog`
