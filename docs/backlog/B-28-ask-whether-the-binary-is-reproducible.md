---
id: B-28
title: "Find out whether the same commit links to the same bytes, on one host and across two"
status: open
priority: P1
size: S
stage: stage-8-image-spike
---

# B-28 — Find out whether the same commit links to the same bytes, on one host and across two

"The same commit gives the same digest" rests on something nobody here has measured: that the
Kotlin/Native release binary is byte-for-byte reproducible. If it is not, no layer timestamp fixes
it, and the claim the strand can make is "the same binary gives the same digest".

- **Three links of keel at one commit**: two clean ones on Linux, one cross-link on macOS. sha256 of
  each, and where they differ, which section (`readelf -S` diff, then `cmp -l` into the section).
- **The macOS link is also a question of its own**: does keel, with its cinterop dependencies,
  cross-link `linuxX64` on a Mac at all? The brief's case for a daemonless image assumes it does.
- Does not cover: making the binary reproducible if it is not — that is an upstream question with
  its own item, if it comes to that.

- AC: three sha256 recorded with the commit and the Kotlin version; a one-line verdict (same / differs
  in <section> / does not link on macOS) that B-32 quotes.
- Anchors: `keel/server/build.gradle.kts`, `docs/research/image-probe/`
