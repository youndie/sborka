---
id: B-30
title: "Resolve the binary's loader, libraries and symbol versions against the base's layers, and score it on the corpus"
status: done
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

## Done, 2026-09-30 — 15 of 15 against docker run, and the one blind spot where it was declared

The prototype is `docs/research/image-probe/check/` (≈450 lines of Kotlin/JVM: ELF, layers, registry,
resolution); the scorer is `image-probe/score.sh`; the run is
[results/2026-09-30-score.txt](../research/image-probe/results/2026-09-30-score.txt).

| Row | Docker | The check |
|---|---|---|
| r1 keel on cc-debian13 | serves | `loads` — 8 objects, 44 symbol-version requirements |
| r2 keel on base-debian13 | `libgcc_s.so.1` missing | `missing-library libgcc_s.so.1 needed by /app/keel` |
| r3 curl probe on cc-debian12 | `libz.so.1` missing | `missing-library libz.so.1 needed by /app/curl` |
| r4 probe without `--as-needed` | `libcrypt.so.1` missing | `missing-library libcrypt.so.1 needed by /app/probe` |
| r5a/r5b copied libcrypt | `GLIBC_2.38` not found | `missing-version GLIBC_2.38 from libc.so.6 needed by /usr/lib/x86_64-linux-gnu/libcrypt.so.1` |
| r6 keel on scratch | `exec /app/keel: no such file or directory` | `missing-interpreter /lib64/ld-linux-x86-64.so.2` — the right file, where Docker names the wrong one |
| r7a/r7c | start | `loads` |
| r7b gconv missing | fails at run time | `loads` — **the declared limit** |

Every row with a base-plus-binary image was checked twice — as the saved image, and with the base
pulled from its registry by digest and the binary added — and both agreed. The registry path also
ran on macOS, with no `docker` and no `readelf` (what [B-21](B-21-print-what-the-binary-declares.md)'s
report says there: "readelf is not on PATH, so this is unchecked").

- **The first scoring run said 7 of 15**, kept as [results/2026-09-30-score-first-run.txt](../research/image-probe/results/2026-09-30-score-first-run.txt).
  Six were the scorer's own expected lines, which left out the requester the check names. The
  seventh was the check: on `scratch` it also listed every NEEDED entry as missing — true of the
  files, and nothing a loader that does not exist would ever look for. It now stops at a missing
  interpreter and says why.
- **Mutation, through the same scorer**: skipping the version check turns r5a/r5b to `loads` (13/15);
  not following symlinks fails every row on a Debian base, where `/lib64/ld-linux-x86-64.so.2` is a
  link (4/15). Both are killed by named rows.
- **Not exercised by this corpus, and therefore not verified**: `/etc/ld.so.cache` (no distroless base
  carries one — the reader is written, never run on a real cache), whiteouts (no row deletes a file
  from a lower layer), `DT_RUNPATH`/`$ORIGIN` and `LD_LIBRARY_PATH` from the image config (no binary
  here sets one). A row for each is cheap — an Ubuntu base has a cache; a Dockerfile `RUN rm` makes a
  whiteout — and they belong to whatever ships the check (B-33), not to this prototype.
- **An assumption written into the code**: the default search directories are Debian's and Ubuntu's
  x86_64 (`/lib/x86_64-linux-gnu`, `/usr/lib/x86_64-linux-gnu`, `/lib`, `/usr/lib`). r7a is the evidence
  for the first; a base from another family could search elsewhere.
- zstd layers are refused by name; every base here is gzip.

**For B-32:** RQ2 is GREEN on this corpus. The check is small, runs anywhere a JVM does, needs the
same two things a `jib-core` task already holds (the base reference and the layers), and its green
means exactly "the loader will start", which r7b makes impossible to over-read.
