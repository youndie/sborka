---
id: B-30
title: "Resolve the binary's loader, libraries and symbol versions against the base's layers, and score it on the corpus"
status: open
priority: P1
size: M
stage: stage-8-image-spike
blocked_by: [B-27]
---

# B-30 — Resolve the binary's loader, libraries and symbol versions against the base's layers, and score it on the corpus

The part of the brief nobody else seems to do, and the only part worth publishing if it works:
before push, answer "can this base load this binary" from files alone. [B-21](B-21-print-what-the-binary-declares.md)
prints `NEEDED` and deliberately stops there — it compares with nothing, and on a Mac it has no
`readelf`. This compares with the base's actual files.

- **Four questions, in order**: `PT_INTERP` exists in the image; every `DT_NEEDED` resolves,
  transitively, through `DT_RUNPATH`, the image's `/etc/ld.so.cache` and the default directories;
  every `VERNEED` is defined by the providing library's `VERDEF`; whiteouts delete and symlinks are
  followed inside the image root.
- **ELF read in the JVM, not by `readelf`.** It has to answer on macOS, where the brief's user builds.
- **Base pulled by digest over the registry API**, no daemon; layers read as tar streams.
- Rejected: a list of expected libraries. That is the rubber stamp B-21 refused to become.
- Does not cover: `dlopen` targets and data files. The output says so on every run.

- AC: run over B-27's corpus, the five failing rows fail naming the right file, the starting row
  passes, the gconv row passes; the verdict matrix equals `docker run`'s everywhere except that row.
  A prototype under `docs/research/image-probe/`, not yet a convention.
- Anchors: `docs/research/image-probe/`, `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts`
