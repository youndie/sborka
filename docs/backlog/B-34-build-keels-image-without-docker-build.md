---
id: B-34
title: "Build keel's image without docker build: a jib-core task, and the Dockerfile's prose moved where it still means something"
status: open
priority: P2
size: M
stage: stage-9-image-ship
blocked_by: [B-33]
---

# B-34 — Build keel's image without docker build: a jib-core task, and the Dockerfile's prose moved where it still means something

**Decided by the owner, 2026-09-30: yes.** `writeNativeDockerfile`'s "the Dockerfile is the
repository's" gives way for keel: the image is described in its build and built by B-33's task.

[B-29](B-29-put-keel-into-an-image-with-jib.md) answered how: `jib-core`, not the Jib Gradle plugin.
[research-native-image](../research/research-native-image.md) D2 says where each part of keel's
Dockerfile goes:

- exec entrypoint and `MALLOC_ARENA_MAX` become the task's configuration; `libgcc_s` and `libcrypt`
  become the gate; `ca-certificates` and gconv, which no ELF entry names, stay a sentence a person
  reads — in keel's service document rather than a Dockerfile comment;
- keel's `Dockerfile` and `.dockerignore` go, and whatever in keel's CI or `rename.sh` names them
  follows;
- the OCI labels carry the version and the commit that `/version` reports — the same commit twice
  only once [kore#102](https://github.com/youndie/kore/issues/102) lands.
- Does not cover: `writeNativeDockerfile` in sborka for other consumers — it stays until none is left.

- AC: keel's image built with no `docker build`, serving `/health/ready` and exiting 0 on SIGTERM;
  `/version` and the labels agree; a `scripts/rename.sh` clone still passes `make gate`.
- Anchors: `keel/Dockerfile`, `keel/server/build.gradle.kts`, `keel/.github/workflows/check.yaml`
