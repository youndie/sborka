---
id: B-33
title: "Give sborka.native-service an image task on jib-core that fails before push when the base cannot load the binary"
status: done
priority: P1
size: M
stage: stage-9-image-ship
blocked_by: [B-37]
---

# B-33 — Give sborka.native-service an image task on jib-core that fails before push when the base cannot load the binary

**Decided by the owner, 2026-09-30: yes, and in sborka.** That answers the two questions this item
carried: the load check becomes a gate — B-21's "NEEDED is a log line, not a gate" gives way, because
this gate compares with the base's files rather than an expected list — and it lives in
`sborka.native-service`, beside `stageNativeImage`, not in a separate plugin (yet).

The shape is [research-native-image](../research/research-native-image.md) D1: one task on `jib-core`
that assembles the image from the staged binary and a base pinned by digest, runs the check (B-37)
against the layers it is about to write, and only then writes the tarball or pushes.

- **Configuration in the build, not a Dockerfile**: the base by digest (a tag fails configuration),
  entrypoint in exec form, environment (`MALLOC_ARENA_MAX`), exposed port, OCI labels from the project
  version and the commit. Only the binary goes into the image — not `stageNativeImage`'s NEEDED report.
- **Configuration-cache compatible**, which is the reason it is `jib-core` and not the Jib plugin
  (B-29). The base digest and the binary are declared inputs, so a verdict cannot be replayed
  `FROM-CACHE` for another artefact.
- **The check's output always ends with what it did not check** (`dlopen`, CA certificates, time
  zones) — a green gate must not read as "the image works".
- Does not cover: keel adopting it and losing its Dockerfile (B-34).

- AC: in sborka's `stand`, a native service applying the convention builds an image tarball with no
  Docker daemon; pointed at `distroless/base-debian13` by digest, the build fails before writing it,
  naming `libgcc_s.so.1` and the binary; pointed at `cc-debian13`, it passes and the image serves.
- Anchors: `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts`, `stand/`

## Done, 2026-09-30 — `nativeImageTar`, and the stand asks it both ways

`sborka.native-service` now has `nativeImage { base; imageName; environment; ports; labels }` and
`nativeImageTar`: base by digest → pulled with no daemon → the load check (`:image`) against it with
the staged binary on top → only then jib-core writes `build/native-image-oci/<baseName>.tar`.

Checked on a Linux host, stand at this branch's commit:

- **cc-debian13**: `VERDICT loads`, 7 objects and 34 symbol-version requirements; the tarball loads
  into Docker and runs (`stand-service` prints and exits 0 — the stand's service is not a server;
  serving is B-34's, on keel). Image config: `created` 1970-01-01, entrypoint `[/app/stand-service]`,
  `MALLOC_ARENA_MAX=2`, port 8080, labels `org.opencontainers.image.version` and `.revision` (the commit).
- **base-debian13**, the real task with its default `failOnLoadProblem`: the build fails —
  `the base cannot load /app/stand-service — missing-library libgcc_s.so.1 needed by
  /app/stand-service`, the base digest on the next line — and the tarball the previous good run had
  written is deleted.
- **A tag** (`…cc-debian13:latest`) fails the task before anything is pulled, saying why.
- **Configuration cache**: stored, then reused on the next run with the task `UP-TO-DATE`.
- `verifyNativeImage` in the stand runs the pair on every Linux `check` (CI): a second instance of the
  same task class against base-debian13 with `failOnLoadProblem = false` records the refusal and
  writes no tarball.

Decisions taken on the way:

- **An isolated worker, not a dependency.** jib-core brings Guava, an HTTP client and Jackson. The
  conventions compile against it and `:image` (`compileOnly`); the work runs in a worker whose
  classpath is the `sborkaNativeImageRuntime` configuration — `io.github.youndie.sborka:image` at
  this release's version, jib-core at the version generated into `SborkaVersion.JIB_CORE`, and the
  Kotlin standard library — resolved when the task runs. A consumer's buildscript classpath gains
  nothing.
- **Not on `assemble` or `check`**: the task reaches a registry.
- **The stand links with `--as-needed`** for this module: it does not apply `sborka.kmp`, and without
  the flag its binary declares `libcrypt.so.1`, which cc-debian13 is right to refuse.
- **The AC said "a tag fails configuration"; it fails the task instead**, before any pull. Failing
  configuration would fail every build of a module whose image nobody asked for.
- **"No Docker daemon"** is by construction (jib-core's `TarImage` never calls one) and was shown on a
  Mac with no `docker` on `PATH` in B-29 with the same library; this stand ran on Linux.
- The check pulls the base once and jib-core pulls it again for the image; jib-core caches its copy,
  the check does not. Worth a shared cache only if the second pull shows up in someone's build time.

Docs: `docs/conventions.md` (`nativeImageTar`, and the Dockerfile paragraph now describes two paths),
`docs/decisions.md`, README's convention table.
