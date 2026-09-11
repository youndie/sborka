---
id: B-20
title: "Strip the release binary: a third of the scratch prize, for one flag, today"
status: open
priority: P2
size: XS
stage: stage-6-static-binary
---

# B-20 — Strip the release binary: a third of the scratch prize, for one flag, today

tracy's server binary is 13 863 696 bytes as it ships and 10 241 264 stripped — **3.6 MB, 26 %**
([research-static-binary §1.2](../research/research-static-binary.md)). Moving that image from
`distroless/cc` to `scratch` would save 10.6 MB, and `scratch` does not work (§1.5, §1.6). Two
thirds of the size argument is available without any of the linker work.

- **The decision and its reason.** Strip in the release link, in `sborka.native-service`, where the
  release binary is already named and staged. Debug binaries are untouched: the symbols are what
  make a native stack trace readable, and nothing about a debug build is size-sensitive.
- **This item is out of its own brief and says so.** Binary size is razves' subject and Brief A
  listed it as a non-goal — "this brief is about what the binary needs at runtime". It is here
  because the measurement fell out of `nm -D` on the way to the runtime question, and a 3.6 MB
  finding that goes unwritten because it belongs to a neighbouring document is a finding lost.
  Whoever owns razves' size work may well move it.
- **The thing to check before doing it** is what a stripped Kotlin/Native binary does to a crash
  report. katcher exists to receive those, and a native stack trace with no symbols is worth less
  than 3.6 MB. That check is the item, not the flag.
- **Rejected: stripping in the Dockerfile.** Then it applies to the two repositories that remember,
  and the binary a developer runs locally differs from the one in the image.

- AC: the release binary of a service is stripped, its size recorded beside the unstripped one, and
  a deliberately crashed native build still produces a report katcher can symbolicate — or, if it
  cannot, this item is closed as *rejected* with that reason written down.
- Anchors: `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts`,
  `razves/docs/research`, `katcher/server/src/commonMain`
