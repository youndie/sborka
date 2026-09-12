---
id: B-23
title: "Move the other eighteen native-only server tests to commonTest"
status: wip
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

## katcher done, metrik blocked — 2026-09-12

[katcher#53](https://github.com/youndie/katcher/pull/53), `452aae6`. All eleven moved to
`commonTest` and **compiled unchanged**; `jvmTest` runs 21 classes where it ran 11.

**The yield this item predicted, and it is a real divergence.** sqlx4k is two drivers — the Rust one
on Kotlin/Native, Xerial's `sqlite-jdbc` on the JVM — and on an in-memory database they disagree
twice:

- the JVM driver **refuses a pool larger than one**: *"SQLite in-memory databases cannot be used
  with connection pools larger than 1. Each connection creates a separate in-memory database
  instance."* It is right to. A second connection to `:memory:` is a second, empty database, so the
  default pool made every test here depend on which connection it happened to get. The native driver
  said nothing, and the tests never noticed, because none of them writes and reads through different
  connections;
- pinning the pool to one then **deadlocks**: every test hung and failed after a minute with
  `UncompletedCoroutinesError`, because the transaction holds the only connection while the code
  inside it asks for another.

So in-memory is not available on the JVM half with this driver at all. The harness takes a temporary
file, which also takes the pool the production code takes and is what metrik's route tests already
do. Neither half of this was visible while the tests ran on one target.

**metrik's seven are blocked**, and not technically: its branch `parity/metrik-findings` carries a
commit that is not mine — `7d898c7`, `Refs: #29` — and moving or pushing it inside someone else's
pull request is not a decision to take unattended.
