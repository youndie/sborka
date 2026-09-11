---
id: B-15
title: "The page people will search for: what the JVM and Kotlin/Native actually do differently"
status: open
priority: P1
size: S
stage: stage-5-parity-gate
blocked_by: [B-09]
---

# B-15 — The page people will search for: what the JVM and Kotlin/Native actually do differently

The brief's last deliverable, and the one with an audience outside this portfolio: a page saying,
with a test behind every row, where the two runtimes agree and where they do not.

The findings invert what such a page is usually written to say. Number formatting, string case
mapping including `ß` and the Turkish dotted `İ`, hashing including non-BMP text, and seeded
randomness are **identical**, row for row — 49 probes, zero differences. Almost every regex
construct anyone would reach for agrees, including the look-arounds and Unicode character classes
that get named as risks. What differs is hash-map iteration order, exception text, one regex
construct and the size of the timezone table. The negative result is the useful half: a portfolio
that half-believes `Double.toString` might differ writes defensive code forever, and 129 measured
rows retire that belief.

- **The decision and its reason.** Every row cites its probe id and links the transcript, so the
  page is re-checkable rather than merely believable — and re-runnable at the next Kotlin bump,
  which is when anyone will want to know whether it still holds. The numbers name their date and
  their versions: the 17 are true of Kotlin 2.4.10, kotlinx-serialization 1.11.0 and
  kotlinx-datetime 0.8.0, and of nothing else.
- **The page says what the probe cannot see, in the same breath.** The four divergences this
  portfolio has actually paid for are platform APIs, not stdlib
  ([research-parity §1.4](../research/research-parity.md)), and a reader who takes "the stdlib
  agrees" as "the platforms agree" has been given the wrong lesson by a page that was technically
  correct.
- **Rejected: publishing before B-09.** Every number is currently `macosArm64`, and a page about
  Kotlin/Native whose measurements are about macOS is worse than no page.
- **Does not cover** the gate itself. How this portfolio keeps the list true is B-13, and it is a
  sentence on the page, not its subject.

- AC: a page on kotlin.website whose every claim names a probe id present in
  `docs/research/parity-probe/results/`, with both the agreements and the differences, and the
  versions they were measured at.
- Anchors: `docs/research/parity-probe/results/`, `docs/research/research-parity.md`,
  `kotlin-website/site/src/jsMain/resources/markdown/blog`
