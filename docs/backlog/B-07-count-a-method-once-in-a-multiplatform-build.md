---
id: B-07
title: "Count a method once when a multiplatform build compiles it into several directories"
status: open
priority: P2
size: XS
stage: stage-1-evidence
epic: feature-perf-lint
---

# B-07 — Count a method once when a multiplatform build compiles it into several directories

A KMP build writes the same class into more than one output directory — `classes/kotlin/jvmMain`
beside `classes/kotlin/main`, an Android variant beside both — and a reader that walks the
directories reports every finding once per copy. The probe hit exactly this: shashki's single
`socketUrl` appeared twice, and the first portfolio table counted 9 pattern findings where there
are 8 methods.

- **The decision and its reason.** Deduplicate by class name plus method name and descriptor, and
  report the count of distinct methods. Deduplicating by file path does not work: the paths differ
  by exactly the segment that varies.
- **Where it bites hardest** is a summary line: "1 058 methods over the threshold" and "1 142
  findings" are the same run, and a reader comparing two repositories cannot tell which number they
  are looking at.
- **Does not cover** the case where two targets genuinely compile *different* bodies from the same
  source — expect/actual, or a different `inline` expansion. Then one finding may hide another; the
  report should say which directories a deduplicated finding came from.

- AC: a fixture with the same class under two directories produces one finding naming both.
- AC: the summary counts distinct methods and says so.
- Anchors: `sborka/build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/MethodSizes.kt`
