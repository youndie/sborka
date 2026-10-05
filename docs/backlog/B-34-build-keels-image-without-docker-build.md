---
id: B-34
title: "Build keel's image without docker build: a jib-core task, and the Dockerfile's prose moved where it still means something"
status: done
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

## Done, 2026-09-30 — keel has no Dockerfile, and its image passes the feature scenario

[youndie/keel#53](https://github.com/youndie/keel/pull/53): `nativeImage { base = cc-debian13@sha256:…;
ports = [8080] }` in `server/build.gradle.kts`, sborka `0.4.0.89` → `0.4.0.106` (the first snapshot
with `nativeImageTar`), `Dockerfile` and `.dockerignore` deleted, and what they said that no ELF entry
can — certificates, gconv, the `MALLOC_ARENA_MAX` measurement, exec form — moved into keel's service
document.

Run on a Linux host against keel's own feature scenario ("the image starts with the binary and
nothing beside it"):

- `./gradlew :server:nativeImageTar`: `VERDICT loads`, 8 objects, 44 symbol-version requirements.
- `/health/ready` 200; `POST /items` 201; `GET /items` returns `ключ — naïve ✓` intact; `docker stop`
  → exit 0 with kore's transcript ending `EXIT COMPLETED`.
- `/version` says `version: 0.1.0`, `commit: ecc01dcce7b4`; the labels say `0.1.0` and the full commit.
- 13 994 233 bytes by `docker image inspect` — the Dockerfile image B-04 weighed was 13 972 497, with
  a different binary; the 25 MB budget holds.
- `./gradlew build` green on sborka 0.4.0.106 (24 tests each on `jvm` and `linuxX64`); a
  `rename.sh` clone passes `make gate`.

Two things the keel side paid for:

- **rename.sh failed CI on this very item's number.** keel's build-file comment cited "sborka's
  B-33/B-34"; rename.sh reads any `B-NN` outside the docs as a keel item, which is what it is written to
  do. The comment now links the research instead.
- **The image is named `server:0.1.0`**, the module's name — the convention's default. A person
  expects the binary's name; that is [B-38](B-38-name-the-image-after-the-binary.md), sborka's to fix.
