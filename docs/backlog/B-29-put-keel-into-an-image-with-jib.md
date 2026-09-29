---
id: B-29
title: "Put keel's native binary into an image with Jib, with no Docker daemon, and see what else comes along"
status: done
priority: P1
size: M
stage: stage-8-image-spike
---

# B-29 — Put keel's native binary into an image with Jib, with no Docker daemon, and see what else comes along

If Jib already builds a native image without a daemon, there is no plugin to write — only a recipe
and the check. The brief's version of the question, "Jib without the `java` plugin", is already
answered by reading: `JibPlugin` calls `getByType(SourceSetContainer).getByName("main")` in
`afterEvaluate` and makes every Jib task depend on that source set's runtime classpath. So the real
question is which module shape satisfies it and what that costs.

- **Four arms, one binary**: Jib on keel's `:server` as it is (KMP with a `jvm()` target — is there
  a `main` source set?); Jib in an empty `:image` module with `java` applied only for Jib; Jib CLI
  with a build file; `crane append` onto the base. Each on macOS with no `docker` on `PATH`.
- **What each arm is asked**: does it build a tarball; do the layers carry anything JVM (`.class`,
  `.jar`, `resources/`); does the container, loaded on Linux, answer `/health` and exit 0 on
  `SIGTERM`; is the digest equal across two runs; does it survive the configuration cache
  (zavarnik found Jib 3.5.4 does not); can it carry `MALLOC_ARENA_MAX=2` and exec-form entrypoint.
- Rejected: judging by "the task was green". A Jib task that packs the wrong thing is green.
- Does not cover: whether the image is loadable by its base — that is B-30.

- AC: a table of four arms × the questions above, each cell measured or marked "not reached, because";
  the lines of Gradle each working arm needs.
- Anchors: `keel/server/build.gradle.kts`, `keel/settings.gradle.kts`, `docs/research/image-probe/`

## Done, 2026-09-29 — Jib builds it with no daemon; the plugin carries JVM baggage, the library does not

[results/2026-09-29-jib-arms.txt](../research/image-probe/results/2026-09-29-jib-arms.txt); the
arms are reproducible with `image-probe/jib/arm.sh` and `jib/core-image.gradle.kts`. Every tarball was
built on macOS with no `docker` resolvable on `PATH`, and the two that were loaded served
`/health/ready` 200 on Linux and exited 0 on SIGTERM.

| | builds | JVM paths in the image | digest, two runs | configuration cache | `MALLOC_ARENA_MAX`, exec entrypoint |
|---|---|---|---|---|---|
| 1 — Jib on `:server` as is | **no**: `SourceSet with name 'main' not found` | — | — | — | — |
| 1b — plus `sourceSets.maybeCreate("main")` | yes | as arm 2 | — | no | yes |
| 2 — Jib on an empty `java` module | yes, served | no `.class`/`.jar`; **two JVM files**, see below | equal | **no** — fails at execution | yes |
| 3 — `jib-core` from one task | yes, served | none: one layer, `app/keel` | equal, and content-addressed (controls both ways) | **yes**, stored and reused | yes |

- **The brief's RQ1 as written is RED, and cheaply so.** Jib's Gradle plugin needs a `main` source set
  (read, then run: arm 1). One line gives it one (arm 1b), and an empty `java` module gives it one too
  (arm 2) — so "Jib without a daemon" is GREEN, "Jib without Java" is not.
- **What the plugin still puts in a native image**: a `jvm arg files` layer with
  `/app/jib-classpath-file` (`/app/resources:/app/classes`) and `/app/jib-main-class-file`
  (`could-not-infer-a-main-class`), and a build warning that no classes were found. Harmless to run,
  wrong to ship: an image that says it has a classpath is an image that will be read as a JVM one.
- **And what it costs the build**: Jib 3.5.4 fails under the configuration cache at execution time,
  which keel and sborka's conventions keep on. zavarnik met the same thing on its Jib path.
- **`jib-core` has neither problem** and is 37 lines: one layer with the binary, content-addressed
  (a new mtime and mode on disk leave the digest alone; a different binary moves it), and a task the
  configuration cache stores and reuses. Its one defect on the way was sborka's own #76 — a
  script-level value captured by the action.
- **Timestamps are fixed by Jib in both shapes**: `created` 1970-01-01, every entry of Jib's layers at
  mtime 1 with PAX `atime`/`ctime`/`LIBARCHIVE.creationtime` 1. Together with B-28: the same binary
  gives the same digest, from Jib, today.
- **Digests across arms differ, and it is not the layers**: editing a build file dirties the tree, and
  kore-build takes a new `builtAt` ([kore#102](https://github.com/youndie/kore/issues/102)).
- **Not reached, on purpose**: Jib CLI and crane. Both are binaries installed beside Gradle; the daemon
  question is answered twice already, and neither keeps the image in the build.
- A side finding for whoever wires this: pointing `extraDirectories` at `build/native-image/` ships
  `keel.needed.txt`, `stageNativeImage`'s report, beside the binary.

**For B-32:** there is no plugin to write *for the daemon* — Jib does that. What is left to decide is
whether the recipe is the Jib plugin (a module, two JVM files, `--no-configuration-cache`) or a task
on `jib-core` (37 lines, clean) — and the latter is also where the load check of B-30 would sit,
since it already holds the base reference and the layers.
