---
id: B-34
title: "Build keel's image without docker build: a Jib recipe, or a publishImage task if Jib cannot"
status: question
priority: P2
size: M
stage: stage-9-image-ship
blocked_by: [B-32]
---

# B-34 — Build keel's image without docker build: a Jib recipe, or a publishImage task if Jib cannot

Which of the two is B-29's answer; whether keel's Dockerfile goes at all is the owner's answer to
B-32 — hence `question`.

- AC: keel's image built with no `docker build`; `/version` and the OCI labels agree; a
  `scripts/rename.sh` clone still passes `make gate`.
- Anchors: `keel/Dockerfile`, `keel/server/build.gradle.kts`
