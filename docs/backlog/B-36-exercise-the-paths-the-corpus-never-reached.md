---
id: B-36
title: "Add the four corpus rows that exercise what the check implements and the corpus never reached"
status: wip
priority: P1
size: S
stage: stage-9-image-ship
---

# B-36 — Add the four corpus rows that exercise what the check implements and the corpus never reached

[research-native-image](../research/research-native-image.md) §4 lists four paths of the load check
that are written and have never run on real data: `/etc/ld.so.cache`, whiteouts, `DT_RUNPATH`/`$ORIGIN`
and `LD_LIBRARY_PATH` from the image config. The owner said yes to the gate (B-33); the research's
condition for that yes was rows before the gate, so these come first.

- **One row per path, each with a `docker run` verdict first**, the way B-27 did it: an Ubuntu-based
  image, whose loader reads a real `ld.so.cache`, with a library only the cache can place; a Dockerfile
  that `RUN rm`s a library a lower layer carried, so a whiteout is the only evidence it is gone; a
  binary linked with a `RUNPATH` of `$ORIGIN/lib` and its library beside it; a library found only
  through `ENV LD_LIBRARY_PATH`.
- **Each row with its negative twin where one is cheap** — the library absent from the cache, the
  whiteout not applied — so that a pass is not the only thing the row can say.
- Does not cover: moving the check out of `docs/research` (B-37).

- AC: `corpus.sh` builds and runs the new rows on a Linux host with Docker, their verdicts are in a
  dated results file, and `score.sh` agrees with every one of them — or the disagreement is written
  down as a defect of the prototype and fixed here.
- Anchors: `docs/research/image-probe/corpus.sh`, `docs/research/image-probe/score.sh`,
  `docs/research/image-probe/check/src/main/kotlin/check/LoadCheck.kt`
