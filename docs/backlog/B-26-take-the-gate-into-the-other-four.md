---
id: B-26
title: "Take the platform gate into the other four subjects, metrik first"
status: open
priority: P1
size: M
stage: stage-5-parity-gate
blocked_by: [B-25]
---

# B-26 — Take the platform gate into the other four subjects, metrik first

tracy runs `parityCheck` on every target it declares ([B-25](B-25-wire-the-probe-into-a-build.md)).
Four subjects do not: metrik, katcher, shildik and razves
([research-parity §1.1](../research/research-parity.md)). Each needs `sborka.parity`, a
`parityProbe { }` block and one test — and each has a shape that makes it a different question from
the last.

- **metrik first, and not because it is next alphabetically.** Its `:server` is the reason the TLS
  assertion exists: `ktor-client-curl` on native and `ktor-client-cio` on the JVM through
  `expect`/`actual`, because CIO's native half has no TLS and outbound notifications silently never
  left the process for months. It is the only subject here that can pass a real `tlsRequest`, so it
  is the only one that turns that assertion from written into exercised.
- **katcher resolves one native target from `os.name`.** Whatever host builds it decides which
  native half the gate probed, so a green run on a Mac says nothing about the Linux binary that
  ships. Either the gate runs where the image is built, or the item says plainly that it does not.
- **shildik ships `linuxX64` alone** (`:distribution`), while its `:server` declares `macosArm64`
  too. Probing a target nothing deploys is not wrong, but the report should not let anyone read it
  as coverage of the thing in production.
- **razves is a CLI whose `jvm` target publishes nothing.** The probe still applies — a CLI opens
  files and reads the environment — but the DNS half asserts something it does not do, and an
  assertion a program would never make is a test nobody will keep.
- **Rejected: one pull request for all four.** They share a mechanism and nothing else; a red run in
  the fourth would arrive with three unrelated changes attached, and the shapes above are exactly
  the kind of thing that gets skimmed past in a big diff.
- **Does not cover** moving the native-only suites, which is [B-23](B-23-move-the-rest-of-the-native-only-suites.md)
  — metrik and katcher have their server tests in `nativeTest`, so their probe runs on one target
  until that lands.

- AC: each of the four runs `parityCheck` on the targets it declares, and each names in its own
  report what that does not cover — metrik's including a real TLS request through the engine it
  ships.
- Anchors: `metrik/server/build.gradle.kts`, `katcher/server/build.gradle.kts`,
  `shildik/server/build.gradle.kts`, `razves/cli/build.gradle.kts`,
  `platform-probe/src/commonMain/kotlin/io/github/youndie/sborka/probe/PlatformProbe.kt`
