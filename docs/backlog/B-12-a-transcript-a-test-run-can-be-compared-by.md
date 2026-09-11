---
id: B-12
title: "Give a test run something to compare: a normalised transcript, written by the suite itself"
status: open
priority: P1
size: M
stage: stage-5-parity-gate
blocked_by: [B-10]
---

# B-12 — Give a test run something to compare: a normalised transcript, written by the suite itself

`jvmTest` and `linuxX64Test` already run the same `commonTest` suite on every pull request in
tracy, metrik and katcher ([research-parity §1.6](../research/research-parity.md)). What they
produce is pass or fail, and pass or fail is precisely what a divergence does not change: metrik's
own `WindowAggregatorTest` reads its results with `.single()` and is green on both targets while
the payload underneath it is ordered differently. There is nothing for a gate to diff because
nothing writes down what either run *saw*.

- **The decision and its reason.** A small `commonTest` helper — `recordParity(key) { value }` —
  that appends one `key<tab>value` line per observation to a file named after the target, plus the
  declared normaliser. The suite stays the oracle for correctness and gains a second output; the
  transcript is the artefact the gate compares. The probe already proves the shape works on both
  targets: [`parity-probe/`](../research/parity-probe/) is the same idea with 129 rows.
- **The normaliser is a declared list of field names, never a regex over the payload.** Timestamps,
  generated ids and trace ids have to go, and the same mechanism can quietly erase a real
  difference. A named list is a diff a reviewer sees; a regex is not.
- **Rejected: diffing the JUnit XML reports.** They record which tests ran and how long they took,
  not what the responses were, and they carry timings that differ on every run.
- **Rejected: a golden file per target.** A golden is rewritten by whoever changed the code, so
  two goldens means a divergence can be introduced and accepted in one commit, by updating both.
  The two runs compare against *each other*, and the allowlist is the only place a difference is
  allowed to be written down — with a reason, in a file whose diff a reviewer reads.
- **Does not cover** what gets recorded. Which observations are worth a row is per repository, and
  tracy is where to find out (§1.5: 37 of its 39 tests already run on both targets).

- AC: running `jvmTest` and `linuxX64Test` on tracy leaves two transcripts on disk, and
  `compare.py` over them prints a row count and zero differences on the current commit.
- Anchors: `docs/research/parity-probe/compare.py`,
  `tracy/server/src/commonTest/kotlin/io/github/youndie/tracy/server`,
  `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts`
