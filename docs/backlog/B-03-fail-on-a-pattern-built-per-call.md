---
id: B-03
title: "Fail on a pattern built per call inside a hot module"
status: open
priority: P1
size: S
stage: stage-2-gate
epic: feature-perf-lint
blocked_by: [B-02]
---

# B-03 — Fail on a pattern built per call inside a hot module

The detector exists and prints. It is the cheapest rule in the set by two orders of magnitude — 8
findings in 53 423 methods across eleven repositories — and it has the largest measured share
behind it: 6.45 % of all allocated bytes on the stand's `/business`, two thirds of everything user
code allocated there.

- **The decision and its reason.** Inside a module named by `sborka.perflint.hot`, a pattern built
  outside `<clinit>` fails the build. Eight findings portfolio-wide is a cost a person can pay by
  reading; the rule can therefore afford to be wrong sometimes, which is exactly what a gate needs.
- **The suppression is the normal outcome, not the exception.** Three of the eight findings are
  constructors (a pattern per instance, free for a singleton) and one is interpolated and cannot be
  hoisted at all. The form is `@Suppress("kapkan:pattern-built-per-call", "…")` — the `kapkan:`
  prefix without ktlint's, because ktlint never loads this rule, and the reason enforced by the
  rule that already exists.
- **Does not cover** whether any finding is hot. The rule claims a shape with a measured price on
  one profiled instance and a cost of eight readings here; it does not claim the eight are slow.

- AC: a `Regex` built in a method body of a hot module fails `check` with a message naming the
  method, the 6.45 % figure with its profile, and the suppression form.
- AC: the same code in a non-hot module prints and passes; a `<clinit>` pattern does neither.
- AC: a suppression with no reason fails on `suppression-needs-a-reason`.
- Anchors: `sborka/build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/MethodSizes.kt`,
  `sborka/build-logic/kapkan/src/main/kotlin/io/github/youndie/sborka/kapkan/SuppressionNeedsAReasonRule.kt`
