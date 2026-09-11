---
id: B-08
title: "Keep the gate off test output, and mark what it will not judge"
status: done
priority: P0
size: XS
stage: stage-2-gate
epic: feature-perf-lint
---

# B-08 — Keep the gate off test output, and mark what it will not judge

The gate shipped in `B-03` judging everything under `build/classes`, test compilations included. The
first real consumer showed what that costs: konekt took the published version and its report listed
**six** patterns built per call in `ScreensLookNothingUpTest` alone, with more in
`WorkersAreStartedTest` and `ClockUsageTest`. A repository setting `sborka.perflint.hot=:server`
would have had its build failed by test code — the fastest way to teach a team that a rule is noise.

- **The decision and its reason.** The gate judges shipping output only. A pattern rebuilt per call
  in a test costs a slow suite and nothing else; nothing in the profile that justifies the rule was
  ever measured in a test.
- **Printed, not hidden.** A test finding still appears, marked `[test]`. A chain in a test is worth
  seeing — it just is not worth anybody's afternoon, and a report that silently drops half of what
  it read is a report that cannot be checked.
- **One rule for both readers.** `Joins` has told test output apart since it was written
  (`isTestOutput`); `MethodSizes` did not. The rule moved to `Outputs` and both call it — a second
  copy would be a second answer to the same question.

- AC: with `sborka.perflint.hot` naming a module, an unsuppressed pattern built per call in that
  module's test sources prints and the build passes; the same in its main sources fails.
- AC: the report line for a test finding carries `[test]`, and the scope line says a finding marked
  `[test]` is never judged.
- Anchors: `sborka/build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/Outputs.kt`,
  `sborka/build-logic/settings/src/main/kotlin/io/github/youndie/sborka/settings.settings.gradle.kts`,
  `sborka/stand/jvm-lib/src/test/kotlin/stand/CodesTest.kt`

## Done, 2026-09-11

- `Outputs.isTest` is the shared rule: any path segment equal to `test` in any case, or ending in
  `Test` — `kotlin/test`, `java/test`, `jvmTest`, `desktopTest`, `commonTest`. `Joins` calls it now
  instead of carrying its own copy.
- `MethodSizes.Method.fromTestOutput` is carried per finding and combined across copies the way the
  question demands: **a class compiled into both outputs ships**, so one shipping copy takes it out
  of the test half.
- The stand's hot module now carries an unsuppressed pattern **in its test sources**
  (`CodesTest.matchesHere`) and `./gradlew -p stand check` passes with it — a permanent control
  rather than one run by hand. Take the rule out and that build goes red.
- **What found it**: running the published version on konekt. The stand had no test-code finding to
  fail on, so nothing in sborka's own build could have told me. A rule cannot be proved harmless by
  the repository that ships it.
