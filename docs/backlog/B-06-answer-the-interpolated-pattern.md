---
id: B-06
title: "Decide what the pattern rule tells a caller who interpolates the pattern"
status: question
priority: P2
size: S
stage: stage-2-gate
epic: feature-perf-lint
blocked_by: [B-03]
---

# B-06 — Decide what the pattern rule tells a caller who interpolates the pattern

One of the eight findings has no one-line fix: `katcher`'s
`Regex("$key=\"([^\"]*)\"")` builds its pattern out of an argument, so `<clinit>` is not available.
A rule whose message says "hoist it to `<clinit>`" is wrong on this finding, and being wrong in the
message is how a rule teaches people to suppress without reading.

- **The decision this item owes.** What the rule says when the pattern string is not a constant.
  The hypothesis is that a cache keyed by the interpolated string is the wrong answer — it trades a
  compile for a map lookup and a retained entry per distinct key — and that parsing a header
  without a pattern is the right one.
- **Cheap to settle**, because the class file already knows: a pattern built from a constant pool
  string and one built from a `StringConcatFactory` call site are different instruction sequences.
  The rule can therefore say the right thing in each case rather than one thing in both.
- **Does not cover** fixing katcher. The deliverable is the message and, if the two cases are worth
  separating, the second finding kind.

- AC: the rule's message names the constant case and the interpolated case separately, and
  `docs/research/probe/Control.kt` carries one of each as a control.
- Anchors: `katcher/core/src/commonMain/kotlin/io/github/youndie/katcher/feature/symbolication/SymbolMapRouting.kt`,
  `sborka/build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/MethodSizes.kt`
