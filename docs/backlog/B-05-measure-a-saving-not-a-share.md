---
id: B-05
title: "Measure a saving, not only a share: fix one named finding and A/B it"
status: open
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
