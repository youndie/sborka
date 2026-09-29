---
id: B-28
title: "Find out whether the same commit links to the same bytes, on one host and across two"
status: done
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

## Done, 2026-09-29 — deterministic but for two strings, and both have an owner

[results/2026-09-29-reproducibility.txt](../research/image-probe/results/2026-09-29-reproducibility.txt).
keel at `f79f22d`: four clean links (Linux twice in one directory, once in another, macOS once), four
sha256. Each difference was traced to its bytes and then removed under a control:

- **On one host, the only difference is `builtAt`.** Two links differ in 26 bytes: 20 of the build-id
  note, which hashes the output, and 6 of a UTF-16 string — `2026-09-29T21:31:52Z` against
  `…21:33:13Z`. It is kore-build's `KoreBuildIdentity.builtAt`: the wall clock at the first build of
  an identity in that `build/`, so every clean build — CI's, an image's — takes `now()`. With the same
  identity file in two directories, both links are byte-identical: the checkout path is not embedded.
  Filed upstream as [youndie/kore#102](https://github.com/youndie/kore/issues/102), as a new input
  (`SOURCE_DATE_EPOCH` or the commit time) with today's behaviour as the default.
- **macOS cross-links it** — 5 min 05 s from a fresh clone, no Docker. The brief's premise holds.
- **Across hosts, the only other difference is the linker's name for itself.** `.comment` reads
  `Linker: LLD 21.1.6` on Linux and carries the llvm-project URL and commit on macOS: the two
  distributions ship differently built linkers. With `.comment` and the build-id removed from both,
  the macOS and Linux binaries are identical; the same removal leaves a `builtAt` difference in
  place (control). NEEDED is the same on both.

**The verdict B-32 quotes:** *the same binary gives the same digest* is the claim an image tool can
keep on its own. *The same commit gives the same digest* needs two more things, neither of them an
image tool's: `builtAt` taken from the commit (kore#102), and either one build host OS or the
`.comment` section dropped — a choice for B-32, since removing it also means regenerating or dropping
the build-id.
