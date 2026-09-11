---
id: B-06
title: "Decide what the pattern rule tells a caller who interpolates the pattern"
status: done
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

## Done, 2026-09-11

The class file tells the two cases apart, as the item guessed it would: a constant pattern arrives
with `ldc`, a template comes through an `invokedynamic` on `StringConcatFactory`. `MethodSizes`
counts the interpolated ones per body by anchoring on the `new kotlin/text/Regex` and asking whether
anything was called between it and the constructor.

- **One of the portfolio's eight pattern findings is interpolated** — katcher's
  `extractHeaderValue`, the finding that raised the question. The other seven, the two constructors
  included, are constants and can be hoisted.
- **The rule now says two different things.** To a constant: move it into a `<clinit>`. To an
  interpolated one: parse without a pattern where that is possible; where the pattern must vary,
  build it where the varying part is decided — once per key rather than once per call. The item's
  hypothesis is kept as the sentence that matters: a cache keyed by the interpolated string is the
  answer that looks obvious and is not, because it trades a compile for a lookup and keeps an entry
  per distinct key for the life of the process.
- **Both shapes are controls.** `MethodSizesCallsFixture` carries one of each and the test asserts
  that the constant is NOT reported as interpolated — the direction that would otherwise tell every
  caller to do the thing they cannot do. The stand carries one of each too, so a real run prints
  both sentences, and `docs/research/probe/Control.kt` carries them for the probe.
- **What it misses, and the wording survives it**: a pattern assembled by a helper and passed in
  reads as constant. The rule fires either way; only the sentence changes, and "hoist it" is the
  right sentence for something that looks constant from here.

Not done, and not this item's to do: fixing katcher. The deliverable was the message.
