---
id: B-23
title: "Move the other eighteen native-only server tests to commonTest"
status: open
priority: P1
size: S
stage: stage-4-parity-evidence
blocked_by: [B-10]
---

# B-23 — Move the other eighteen native-only server tests to commonTest

B-10 moved one file and it compiled and passed on both targets unchanged. Seven more sit in
metrik's `server/src/nativeTest` and eleven in katcher's; while they are there, those two servers'
`jvm()` targets compile the production code and run nothing against it, and
[research-parity §1.6](../research/research-parity.md) has no second half to compare.

- **The decision and its reason.** `git mv` them in batches small enough to tell which file caused
  a failure, run `jvmTest` and `linuxX64Test` after each batch, and leave in `nativeTest` only what
  genuinely cannot move — with the reason written beside it, in the file.
- **Expect some not to move, and treat that as the yield.** B-10's one file needed nothing
  platform-specific; eighteen files will not all be like that. A test that turns out to need a
  native-only API has found a platform divergence, and that belongs in research §1.5 rather than in
  a commit message.
- **The JVM half is not the same storage engine**, and the batch that moves has to say so somewhere
  a reader will find it: sqlx4k on the JVM is Xerial's `sqlite-jdbc`, on native the Rust driver
  (B-10). These tests passing on the JVM is coverage of the routes, not of the database that ships.
- **Rejected: moving all eighteen in one commit.** A red build with several failures says nothing
  about which cause is which, and the causes are the point.
- **Does not cover** katcher's `commonTest` files, which already run on both.

- AC: metrik's and katcher's `:server:jvmTest` each run a suite rather than nothing; whatever stays
  in `nativeTest` carries a comment naming what stops it; research §1.6's table is updated with the
  new counts.
- Anchors: `metrik/server/src/nativeTest`, `katcher/server/src/nativeTest`,
  `docs/research/research-parity.md`
