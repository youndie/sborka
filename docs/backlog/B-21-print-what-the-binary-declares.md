---
id: B-21
title: "Print the binary's NEEDED list into the build log, so a new dependency shows up in a diff"
status: open
priority: P2
size: XS
stage: stage-6-static-binary
---

# B-21 — Print the binary's NEEDED list into the build log, so a new dependency shows up in a diff

After B-18 the runtime image carries nothing but the binary, and that is only correct while the
binary needs nothing but what `distroless/cc` has. A dependency arriving through a new library —
`libz` for a compression engine, `libcurl` for a client — changes the `NEEDED` list, and today
nobody would learn that until a container failed to start with `cannot open shared object file`.

- **The decision and its reason.** `stageNativeImage` already copies the release binary to a stable
  path; have it write `readelf -d`'s `NEEDED` list beside it and echo it at `lifecycle` level. It
  costs nothing, it runs where the binary already is, and the list is short enough to read.
- **Not a gate, and deliberately.** A check that failed on a new entry would fail the pull request
  that legitimately adds one, and the fix would be to update an expected list — which is a
  rubber stamp within two sprints. What is wanted is *visibility at the moment it changes*, and a
  line in a build log that someone reads while adding the dependency is that.
- **Rejected: checking the list against the base image's contents.** It would need the base image
  at build time, which is the one thing a Gradle build does not have.
- **Does not cover** `ca-certificates`, and that gap is worth naming in the same place: it is not a
  library, `readelf` will never mention it, and its absence fails every outbound TLS call with a
  message about a certificate path. The reference Dockerfile already carries that comment.

- AC: `./gradlew :server:stageNativeImage` on tracy prints the seven `NEEDED` entries; adding a
  dependency that pulls a new shared library makes the list change in the log of the build that
  introduced it.
- Anchors: `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts`,
  `docs/research/static-probe/experiments.sh`
