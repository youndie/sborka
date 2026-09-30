---
id: B-38
title: "Default the image's name to the binary's, not the module's"
status: open
priority: P2
size: XS
stage: stage-9-image-ship
---

# B-38 — Default the image's name to the binary's, not the module's

`nativeImage.imageName` defaults to `"${project.name}:${project.version}"`. In keel the module is
`:server`, so its image loads into Docker as `server:0.1.0` — found by B-34 running keel's image. The
name a person reaches for is the binary's, `nativeService.baseName` (`keel`), which the convention
already knows and which the image's entrypoint is named after.

- **The default follows `baseName`**: `"<baseName>:<version>"`, lazily, so a module that sets
  `baseName` after applying the convention gets it. A repository that wants something else still sets
  `imageName`.
- Rejected: fixing it in keel with an `imageName` line. keel's rule sends every non-renaming line to
  sborka, and every other consumer of the convention would carry the same line.

- AC: keel's image, built on a sborka carrying this, loads as `keel:<version>` with no `imageName`
  line in keel; the stand's image is named after `stand-service`.
- Anchors: `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts`
