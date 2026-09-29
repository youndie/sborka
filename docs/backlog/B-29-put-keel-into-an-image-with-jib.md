---
id: B-29
title: "Put keel's native binary into an image with Jib, with no Docker daemon, and see what else comes along"
status: open
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
