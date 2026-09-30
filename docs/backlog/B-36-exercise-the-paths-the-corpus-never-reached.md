---
id: B-36
title: "Add the four corpus rows that exercise what the check implements and the corpus never reached"
status: done
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

## Iteration 1, 2026-09-30 — rows written, not yet run

The four pairs are in `corpus.sh` (r8a/b ld.so.cache, r9a/b whiteout, r10a/b RUNPATH, r11a/b
LD_LIBRARY_PATH), with a `runpath` link mode for the probe and `debian:13` added to the bases, and
their expected verdicts in `score.sh`. The run did not happen: the Linux host with Docker stopped
answering mid-iteration. Nothing has been measured; the next iteration runs `corpus.sh`, then fills
`score.sh`'s Docker column from its results and scores.

## Done, 2026-09-30 — eight rows as Docker has them, 23 of 23, and each path killed by its own mutant

The host came back after a reboot; the run is [results/2026-09-30-corpus-b36.txt](../research/image-probe/results/2026-09-30-corpus-b36.txt),
the score [results/2026-09-30-score-b36.txt](../research/image-probe/results/2026-09-30-score-b36.txt).

| Pair | Loads | Does not |
|---|---|---|
| `ld.so.cache` (Ubuntu 24.04) | r8a: libcrypt only in `/opt/crypt`, `ldconfig` run | r8b: moved, cache still names the old path — `libcrypt.so.1` |
| whiteout (Ubuntu 24.04) | r9b: untouched | r9a: `RUN rm` of `libgcc_s.so.1` — `libgcc_s.so.1` |
| `RUNPATH` `$ORIGIN/lib` (cc-debian13) | r10a: probe linked with it, libcrypt in `/app/lib` | r10b: the same image, the probe without it — `libcrypt.so.1` |
| `LD_LIBRARY_PATH` (cc-debian13) | r11a: `ENV LD_LIBRARY_PATH=/opt/crypt` | r11b: no `ENV` — `libcrypt.so.1` |

- **The check agrees with all eight**, and with the fifteen verdicts before them: 23 of 23.
- **Each path is what decides its row**, shown by mutation through the same scorer: not reading the
  cache flips r8a, ignoring `RUNPATH` flips r10a, ignoring `LD_LIBRARY_PATH` flips r11a, not applying
  whiteouts flips r9a — and r8b, whose `mv` leaves a whiteout too.
- **The rows measure what they say**, checked beside the run: `probe-runpath` carries
  `RUNPATH [$ORIGIN/lib]` and `probe-default` none; `ldconfig -p` in r8a maps libcrypt to `/opt/crypt`,
  in r8b to the old path.
- **Nothing in the prototype had to change** — the four paths were right as written. Two defects of
  the harness did: `score.sh` read `$ORIGIN` in a label under `set -u`, and `debian:13`'s digest had to
  be taken back into `bases.lock` from the run's output after the host's copy was reverted.
- Still outside the corpus: bases outside the Debian family, zstd layers, authenticated registries
  (research §4).
