---
id: B-20
title: "Strip the release binary: a third of the scratch prize, for one flag, today"
status: dropped
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

## Rejected, 2026-09-12 — the 3.6 MB is paid for with katcher's native crash reports

The item said the check was the item, not the flag. The check says no, and it was cheaper to read
than to suspect.

**katcher has no native symbolicator.** `MappingType` in
`katcher/core/.../feature/symbolication/Symbolication.kt` holds `ANDROID_PROGUARD` and nothing else;
`IOS_DSYM` and `JS_SOURCEMAP` are commented out. There is no mapping file for a Kotlin/Native binary
and nothing that would consume one.

**And katcher reads native frames by name.** `StackFrames.kt` carries a pattern for
`at 3  binary  0xADDR  kfun:pkg.Class#method(...) + 99`, `CrashTrust.kt` reasons about that shape,
and the assessment tests are written against real `kfun:` frames. Grouping, fingerprinting and
everything downstream rest on the name being in the trace, because there is no mapping to recover it
from.

**Measured on a real binary** — sborka's own `stand-service`, linuxX64, on the Linux box:

| | size | `kfun:` in the symbol table | `kfun:` as strings anywhere in the file |
|---|---|---|---|
| as linked | 481 152 | 123 | 123 |
| `strip` | 347 272 | **0** | **0** |

The two counts being equal is the point: those names exist **only** in the symbol table. Strip, and
they are not somewhere else in the file — they are gone. A stripped native service cannot emit a
`kfun:` frame, so every crash it reports arrives as addresses nothing in this portfolio can turn
back into names.

**So: 28 % of the binary, in exchange for the readability of every native crash report, with no
symbolication path to buy it back.** Not worth it — and it would have been invisible until the first
production crash, which is when the report is what you have instead of the machine.

**What this does not close.** If katcher grows a native mapping type — upload the unstripped binary,
symbolicate against it, the way it already does for R8 — stripping becomes free and this item comes
back. That is the order: symbolicator first, flag second. The 3.6 MB in
[research-static-binary §1.7](../research/research-static-binary.md) stays recorded as available,
with this price beside it.
