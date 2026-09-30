---
id: research-native-image
title: A native service's image without a daemon, and a build that fails when the base cannot load it
type: research
status: active
date: 2026-09-30
---

# Research: an image for a Kotlin/Native service, built by Gradle and checked before push

The brief ([source-brief-native-image](source-brief-native-image.md)) asked three questions and
proposed a plugin. The spike answers the questions and declines most of the plugin.

- **RQ0 — does one commit link to one binary?** Not today, and both reasons have an owner.
  kore-build's `builtAt` is a wall clock ([kore#102](https://github.com/youndie/kore/issues/102)), and
  the macOS and Linux distributions' linkers describe themselves differently in `.comment`. Hold
  those two equal and the bytes are identical, across directories and across hosts.
- **RQ1 — does Jib build the image with no daemon?** GREEN, and "without the `java` plugin" was the
  wrong way to ask it. The Gradle plugin needs a `main` source set; given one, it builds and ships a
  working image from a Mac with no Docker — plus two JVM files and a build that fails under the
  configuration cache. `jib-core`, the library under the plugin, does the same job in one task with
  neither.
- **RQ2 — is "the base cannot load this binary" caught statically, before push?** GREEN on the
  corpus: 15 of 15 verdicts agree with `docker run`, from a saved image and from a base pulled by
  digest, on a Mac with no `readelf`. Its one blind spot — anything opened with `dlopen` — is a row of
  the corpus rather than a footnote.

So the daemon needs no plugin, and the publishable part is what the brief said it was: the check.

This document records verified facts, each with the run that produced it, and marks what is still a
hypothesis. The probes are in [image-probe/](image-probe/); the items are B-27…B-32 in
[backlog.md](../../backlog.md).

## 1. Verified facts

### 1.1 The corpus, and why it came first — B-27

Ten runs of nine images, each built from a base pinned by digest (`image-probe/bases.lock`) and run, before any
checking code existed ([results](image-probe/results/2026-09-29-corpus.txt)). The rows are the
failures this portfolio has paid for, not a taxonomy:

| Row | Image | `docker run` |
|---|---|---|
| r1 | keel on `cc-debian13` | serves; SIGTERM → exit 0 |
| r2 | keel on `base-debian13` | `libgcc_s.so.1: cannot open shared object file` |
| r3 | a `ktor-client-curl` binary on `cc-debian12` | `libz.so.1: cannot open shared object file` |
| r4 | a binary linked without `--as-needed` on `cc-debian13` | `libcrypt.so.1: cannot open shared object file` |
| r5a/b | r4's binary on `cc-debian12` with `libcrypt.so.1` copied from Ubuntu 26.04 / 24.04 | ``version `GLIBC_2.38' not found (required by …/libcrypt.so.1)`` |
| r6 | keel on `scratch` | `exec /app/keel: no such file or directory` |
| r7a/b/c | `scratch` + the loader + every NEEDED entry; the same image asked for `iconv`; the call on `cc-debian13` | starts; fails at run time with `errno=22`; works |

Two things the table taught before the check existed. **r6 names the wrong file** — the binary is
there, the loader is not. **r5b**: the builder keel uses today would reproduce the copied-library
failure as well as the one the incident came from.

### 1.2 RQ0: one commit, four binaries — B-28

keel at `f79f22d`, four clean links: Linux twice in one directory, once in another, macOS once
([results](image-probe/results/2026-09-29-reproducibility.txt)). Four sha256. Traced to bytes:

- On one host, 26 bytes differ: 6 of a UTF-16 string (`builtAt`, `2026-09-29T21:31:52Z` against
  `…21:33:13Z`) and 20 of the build-id that hashes the output. With the same `KoreBuildIdentity.kt` in
  two directories the links are byte-identical — the checkout path is not embedded.
- Across hosts, `.comment` differs: `Linker: LLD 21.1.6` on Linux, the same plus the llvm-project URL
  and commit on macOS. With `.comment` and the build-id removed, macOS and Linux are identical; the
  same removal leaves a `builtAt` difference in place (control).
- macOS cross-links `linuxX64` keel in 5 min 05 s with no Docker.

### 1.3 RQ1: Jib, four shapes — B-29

On macOS with no `docker` resolvable on `PATH`; loaded and run on Linux
([results](image-probe/results/2026-09-29-jib-arms.txt)).

| | builds | ships | digest | configuration cache |
|---|---|---|---|---|
| Jib plugin on KMP `:server` | no — `SourceSet with name 'main' not found` | — | — | — |
| plus `sourceSets.maybeCreate("main")` | yes | as below | — | no |
| Jib plugin on an empty `java` module | yes; serves, SIGTERM → 0 | the binary, and `jib-classpath-file` (`/app/resources:/app/classes`), `jib-main-class-file` (`could-not-infer-a-main-class`) | equal across runs | no — fails at execution |
| `jib-core` from one task (37 lines) | yes; serves, SIGTERM → 0 | one layer: `app/keel` | equal across runs; unchanged by a new mtime and mode, moved by a different binary | yes, stored and reused |

Jib fixes every timestamp it writes: `created` 1970-01-01, entries at mtime 1, PAX
`atime`/`ctime`/`LIBARCHIVE.creationtime` 1.

### 1.4 RQ2: the load check — B-30

`image-probe/check/` at the time, Kotlin/JVM, 499 lines without comments — moved by B-37 into
`build-logic/image/`, where builds call it and the corpus runs against it in CI. Its own ELF reader (`PT_INTERP`,
`NEEDED`, `RUNPATH`, `VERNEED`/`VERDEF`); layers applied with whiteouts; symlinks resolved inside the
image root; the base pulled from its registry by digest with no daemon, or a `docker save` image.
Scored by `image-probe/score.sh` ([results](image-probe/results/2026-09-30-score.txt)):

- **15 of 15** verdicts as `docker run` has them, on every row that could be checked both ways, both
  ways. r6 is reported as `missing-interpreter /lib64/ld-linux-x86-64.so.2`; r5 as
  `missing-version GLIBC_2.38 from libc.so.6 needed by /usr/lib/x86_64-linux-gnu/libcrypt.so.1`.
- **r7b loads**, as declared: the image satisfies every entry the loader reads, and fails on a gconv
  module no ELF entry names. Every run of the check ends by saying what it did not check.
- The first scoring run said 7 of 15 and is kept: six were the scorer's expected lines, one was the
  check listing libraries on `scratch` that a loader which does not exist would never look for.
- Two mutants — version check skipped, symlinks not followed — are killed by named rows.

### 1.5 The prior art — B-31

Nothing fails this build from a digest without a hand-written list
([results](image-probe/results/2026-09-30-lddtree.txt), table in B-31). melange's SCA answers the
question for packages and a repository, not an image's files. container-structure-test checks the
paths someone wrote. `lddtree -R`, the nearest neighbour, exits 0 on every row, fails every library on
the base whose interpreter is an absolute symlink (resolved outside the root), and has no notion of
symbol versions.

## 2. Decisions

### D1. No image plugin for the daemon; the check is the product

Jib already builds a daemonless, reproducible image of a native binary. What nothing else does is
§1.4, and it is small. Recommended shape, **for the owner to confirm** (B-33, B-34):

- **one task on `jib-core`** that builds the image and runs the check against the layers it is about
  to push — it already holds the base reference and the layers, which is everything the check needs;
- **in sborka first**, beside `stageNativeImage`, where the binary is staged and keel is the consumer;
- **a published plugin later**, the pair of zavarnik, when a consumer outside this portfolio needs it
  — sborka does not publish to Central, so "in sborka" means "for this portfolio".

Rejected: a recipe on the Jib Gradle plugin. It works, and it costs a `java` module, two JVM files in
every native image, and the configuration cache, which sborka's conventions keep on.

### D2. The two sborka decisions the brief revisits

**Decided by the owner, 2026-09-30: yes to both, and the gate lives in sborka.** B-33 and B-34 carry
the answers; B-36 and B-37 come first, because the case below made rows before the gate its condition.
What follows is the case as it was put.

- **"NEEDED is a log line, not a gate"** ([B-21](../backlog/B-21-print-what-the-binary-declares.md)).
  Its reason was that a gate against an *expected list* becomes a rubber stamp. This check has no
  list: it fails only where the base cannot load the binary, and on the corpus that is exactly where
  Docker failed. What is not yet known is its false-positive rate outside Debian-family bases and on
  the paths the corpus does not exercise (§4) — which is why B-33 adds rows before it adds a gate.
- **"The Dockerfile is the repository's"** (`writeNativeDockerfile`). Its reason was that the runtime
  image is a decision a person reads. Of what keel's Dockerfile says in prose, the check now carries
  `libgcc_s` and `libcrypt`; exec form and `MALLOC_ARENA_MAX` are the task's configuration; the base
  digest moves into the build. What does **not** move: `ca-certificates` and gconv, neither of which
  an ELF entry names — they stay a sentence somewhere a person reads.

### D3. "Same commit, same digest" is claimed only as far as it is true

The image layer gives "same binary, same digest" today (§1.3). The rest is kore#102 plus one build-host
OS — or dropping `.comment` and the build-id, which is a choice about debuggability, not taken here.

## 3. Deviations from the brief

- **RQ1 asked the wrong question.** "Jib without the `java` plugin" fails for a reason one line fixes;
  the real costs were the JVM files and the configuration cache.
- **"A 2.19 sysroot against whatever distroless carries"** was not the failure mode: the binary needs
  at most `GLIBC_2.18`. Version failures came from copied libraries (r5) — the corrected premise was
  in the brief's own §8.
- **Jib CLI and crane were not run** — separate binaries outside Gradle, and the daemon question was
  answered twice without them. `jib-core` took the slot.
- **r4/r5 use a probe, not keel**, and the blind spot is a direct `iconv_open` rather than a Ktor page
  — the smallest reproduction in each case (B-27).
- **lddtree was added** to the prior art: the brief named two tools, and the closest one was neither.

## 4. Hypotheses, each with what settles it

- ~~The `ld.so.cache` reader has never read a real cache; whiteouts, `DT_RUNPATH`/`$ORIGIN` and
  `LD_LIBRARY_PATH` are unexercised.~~ **Settled by B-36 (2026-09-30):** a pair of rows each, 23 of 23
  against `docker run`, each path killed by its own mutant.
- The default search directories are Debian's and Ubuntu's x86_64. A base from another family may
  search elsewhere; settled by a row on one, or by declaring the check Debian-family only.
- zstd layers and authenticated registries are refused or unsupported; settled when a consumer needs
  them.

## 5. Risks

- **A green check read as "the image works".** r7b is in the corpus so that its size is known; the
  check's own output says what it did not check. A gate built on it must keep saying so.
- **The build reaches a registry.** Pulling the base by digest at build time adds a network dependency
  and, on Docker Hub, anonymous rate limits. A cache of base layers keyed by digest is the obvious
  answer and is not built.
- **`jib-core` is 0.x**, and the spike used one release of it (0.28.2). A pin and a test on upgrade
  are the price.

## 6. Code anchors

- `docs/research/image-probe/corpus.sh`, `bases.lock` — the corpus and its bases
- `build-logic/image/src/main/kotlin/io/github/youndie/sborka/image/LoadCheck.kt` — the resolution order and the verdicts (moved from `docs/research/image-probe/check/` by B-37)
- `build-logic/image/src/main/kotlin/io/github/youndie/sborka/image/Elf.kt`, `ImageFs.kt`, `Sources.kt`
- `docs/research/image-probe/score.sh`, `lddtree.sh`
- `docs/research/image-probe/jib/core-image.gradle.kts`, `jib/arm.sh`
- `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts` — `stageNativeImage`, where D1 would land

## 7. What happens next

D2 is answered (2026-09-30). The order is B-36 (the four unexercised paths as rows), B-37 (the check
into build-logic, the corpus in CI), B-33 (the image task and the gate in `sborka.native-service`),
B-34 (keel without its Dockerfile). kore#102 is the one upstream item that decides whether "same
commit, same digest" can be claimed at all.
