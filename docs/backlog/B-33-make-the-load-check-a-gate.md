---
id: B-33
title: "Make the load check a gate in the home B-32 names, with keel's corpus rows in CI"
status: question
priority: P1
size: M
stage: stage-9-image-ship
blocked_by: [B-32]
---

# B-33 — Make the load check a gate in the home B-32 names, with keel's corpus rows in CI

[research-native-image](../research/research-native-image.md) D1 names the home — one task on
`jib-core` in `sborka.native-service`, beside `stageNativeImage` — and D2 lays out why this revisits
[B-21](B-21-print-what-the-binary-declares.md)'s "NEEDED is a log line, not a gate". The check scored
15 of 15 against `docker run` on the corpus ([B-30](B-30-resolve-the-binary-against-the-base.md)).

**`question`, because two answers are the owner's:**

1. Does B-21's decision give way to a gate that compares with the base's files rather than a list?
2. In sborka for this portfolio first, or straight to a published plugin beside zavarnik?

What an answer of "yes, in sborka" makes this item, in order:

- **rows before the gate**: an Ubuntu-based base (a real `ld.so.cache`), a whiteout (`RUN rm` of a
  library), a binary with `RUNPATH`, `LD_LIBRARY_PATH` in the image config — the four paths §4 of the
  research lists as written and never exercised;
- the check moved from `docs/research/image-probe/check/` into the convention, with the corpus as its
  test suite;
- keel's image task depends on it, and its build fails before push on r2's base.

- AC: keel's image build fails before push when its base cannot load the binary, naming the file;
  the corpus rows, the four new ones included, run in CI.
- Anchors: `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts`,
  `docs/research/image-probe/check/src/main/kotlin/check/LoadCheck.kt`
