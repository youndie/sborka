---
id: B-34
title: "Build keel's image without docker build: a jib-core task, and the Dockerfile's prose moved where it still means something"
status: question
priority: P2
size: M
stage: stage-9-image-ship
blocked_by: [B-32]
---

# B-34 — Build keel's image without docker build: a jib-core task, and the Dockerfile's prose moved where it still means something

[B-29](B-29-put-keel-into-an-image-with-jib.md) answered which: a task on `jib-core` (37 lines, one
layer, configuration cache intact), not the Jib Gradle plugin (a `java` module, two JVM files in every
image, no configuration cache). [research-native-image](../research/research-native-image.md) D2 says
what keel's Dockerfile carries today and where each part would go.

**`question`, because it revisits `writeNativeDockerfile`'s "the Dockerfile is the repository's"** —
the owner's decision, not this strand's.

If the answer is yes:

- keel builds its image with no `docker build`, from the binary it already stages;
- exec entrypoint and `MALLOC_ARENA_MAX` become the task's configuration; `libgcc_s` and `libcrypt`
  become B-33's check; `ca-certificates` and gconv, which no ELF entry names, stay a sentence a person
  reads — in the service document rather than a Dockerfile comment;
- the OCI labels carry the version and the commit that `/version` reports — which is only the same
  commit twice once [kore#102](https://github.com/youndie/kore/issues/102) lands.

- AC: keel's image built with no `docker build`; `/version` and the labels agree; a
  `scripts/rename.sh` clone still passes `make gate`.
- Anchors: `keel/Dockerfile`, `keel/server/build.gradle.kts`
