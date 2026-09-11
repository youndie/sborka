---
id: B-05
title: "Measure a saving, not only a share: fix one named finding and A/B it"
status: done
priority: P1
size: M
stage: stage-3-saving
epic: feature-perf-lint
---

# B-05 — Measure a saving, not only a share: fix one named finding and A/B it

Every number behind these rules says what a shape *owns* in a profile. Not one says what removing
it *saved*, because nobody has run a service with a finding fixed. That is the weakest joint in the
whole document and the one a reader is most likely to skip over.

- **The decision and its reason.** Take the largest named finding on a real service —
  `MoneyFormat.group` in konekt, 1.08 % of all allocated bytes, four intermediates — rewrite it
  without the chain, and measure alloc/req and rps on the same k6 scenario, both variants
  alternating, three runs each, medians. Same protocol as zavarnik's `ab.sh`, and for the same
  reason: run-to-run spread on that machine is ±15 %, so one run per variant is not a measurement.
- **The result is allowed to be "no measurable change".** 1.08 % of bytes on a service where user
  code owns 5 % is a small number by construction; if the A/B cannot see it, that is the honest
  ceiling of the rule and it goes into the research beside the share.
- **Rejected: measuring on the stand instead.** The stand was written to give user code the most to
  do, so a saving there is a saving on a benchmark. konekt was written before any of this.
- **Does not cover** the pattern rule: konekt has no pattern findings, and the stand's
  `Pricing.quote` is stand code. If a saving is wanted for that rule too, it needs its own item.

- AC: a table in the research with alloc/req and rps for both variants, medians of three, and a
  sentence saying whether the difference is outside the noise of the stand.
- Anchors: `konekt/shared/server-common/src/main/kotlin/io/konekt/money/MoneyFormat.kt`,
  `zavarnik/bench/profile/ab.sh`, `zavarnik/bench/profile/konekt.sh`

## Done, 2026-09-11 — and the answer is a negative result

Both named methods were rewritten as one pass (konekt `io.konekt.text.DigitGroups`) and measured on
konekt's own stand with `scripts/measure/ab-images.sh`: two images differing by that patch,
alternated, three repetitions each, 200 rps at the chart's limits, a 120 s allocation window, the
stand reset before every run. Numbers in
[research-perf-lint §1.7](../research/research-perf-lint.md); raw output in
`docs/research/probe/results-ab-2026-09-11.txt`.

- **The rule's target shrank by about seven tenths**: 1.30 % → 0.378 % of all allocated bytes on
  that path, and the share user code owns fell 4.48 % → 3.78 %. *(Corrected 2026-09-11. This line
  first read "1.30 % → 0.00 %" because the metric was keyed on the two method names the rule had
  listed and the fix renamed the method — the work moved into `io.konekt.text.DigitGroups.grouped`,
  which the same profiles charge 0.258–0.401 %. A metric keyed on the subject's name goes to zero
  when the subject is renamed.)*
- **The service-level A/B saw nothing**: bytes per request −0.75 %, against a 4.46 % spread between
  one variant's own three repetitions.
- **The first round, without a reset between runs, said −8.55 %** — the stand's own drift, eleven
  times the effect, in the flattering direction. That is why the harness now resets per run and why
  this item's result is stated as two numbers rather than one.

What it changed beyond itself: the acceptance of a rule in this set is "the profile stops charging
what it named", not "the service got faster". `B-03`'s message and the feature document were
written accordingly.

Not covered, and still not: the same question for the pattern rule. konekt has no pattern findings
and the stand's `Pricing.quote` is stand code, so a saving for that rule needs a service that has
one.
