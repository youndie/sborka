---
id: B-33
title: "Give sborka.native-service an image task on jib-core that fails before push when the base cannot load the binary"
status: wip
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
