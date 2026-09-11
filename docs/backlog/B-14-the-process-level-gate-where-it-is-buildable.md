---
id: B-14
title: "Run razves as two processes and diff the output — the gate the brief asked for, where it fits"
status: open
priority: P2
size: S
stage: stage-5-parity-gate
---

# B-14 — Run razves as two processes and diff the output — the gate the brief asked for, where it fits

B-13 asserts platform capabilities from inside the test process, because none of the four services
has a JVM binary to start. razves does: it is a CLI with `jvm()` and native executables on both
`linuxX64` and `macosArm64`, and "run it on a fixture, capture stdout, diff" needs no stand, no
database and no service lifecycle.

That makes it the portfolio's one instance of the brief's original design, and worth having for a
reason beyond razves: where the two gates disagree about the same commit, the difference is exactly
what an in-process check cannot see.

- **The decision and its reason.** A script that runs the JVM build and the native build of the
  same commit over `razves/fixture`, normalises the paths and the timings, and diffs. The fixture
  module exists for this kind of thing already.
- **Rejected: making this the design for everything.** Four services would each need an entry
  point, a runtime classpath and a start script built for the sole purpose of being tested. See
  research D1.
- **Does not cover** the JVM-vs-native question for razves' *library* surface, which its 15
  `commonTest` files already run on both targets.

- AC: a `parityCheck` on razves that runs both binaries over the fixture and fails on an unlisted
  difference in their output; whatever it finds that B-13 does not, written into
  [research-parity](../research/research-parity.md) §5 beside Risk 1.
- Anchors: `razves/cli/build.gradle.kts`, `razves/fixture`, `razves/cli/src/commonMain`
