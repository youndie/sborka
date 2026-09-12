---
id: B-14
title: "Run razves as two processes and diff the output — the gate the brief asked for, where it fits"
status: dropped
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

## Dropped, 2026-09-12 — razves has no JVM process either, and this item said it did

The premise was mine and it was wrong. `razves/cli/build.gradle.kts` says it in its own comment:

> `jvm` is here for the tests only: nothing is published from it, and the CLI's own logic is common
> code either way.

No `application` plugin, no `mainClass`, no distribution. The native executables are real; the JVM
side is a compile-and-test target. So "run the JVM build and the native build of the same commit and
diff" needs an artefact razves deliberately does not make — and the module's comment says why it
does not: *"a native executable rather than a jar, because the tool exists to serve people who ship
a single binary and telling them to install a JVM to measure one would be absurd."*

**Which makes research D1 stronger, not weaker.** It reads as a compromise — "the gate compares
transcripts because no service has a runnable JVM artefact" — with razves as the one exception. There
is no exception. **Not one subject in the portfolio ships a JVM process.** The brief's design is not
buildable anywhere here, and D1 is the only available shape rather than the cheaper of two.

**What razves found by hand is worth carrying over, because it is what this item was hoping to
catch.** From `cli/src/commonMain/.../Main.kt`:

> every refusal this tool has — a missing file, a file that is not a report, two reports measured
> differently, and clikt's own "missing argument" — left the process with status 0. A tool that
> cannot say it refused is a tool a script reads as having agreed.

Measured on the built binary, fixed with `parse` plus an explicit `exitWith`. And `AnalyseTest`'s own
header says why nothing automated covers it: *"The command class is argument parsing and an exit
code; neither is worth checking twice, and both are awkward to check on a native target."*

So the gap is real and it is **not a parity gap** — it is a CLI conformance one: run the built
binary, feed it each refusal, assert the exit code. One target is enough, there is nothing to
compare against, and it belongs in razves rather than in sborka's parity strand. Handed over as a
recommendation, not opened as an item here.
