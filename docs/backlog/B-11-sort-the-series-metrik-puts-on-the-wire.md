---
id: B-11
title: "Sort the series metrik's agent puts on the wire, the way its histogram already does"
status: open
priority: P2
size: XS
stage: stage-4-parity-evidence
---

# B-11 — Sort the series metrik's agent puts on the wire, the way its histogram already does

`WindowAggregator.drain()` builds its `routes` list by iterating a `HashMap` with no ordering step,
and that module publishes for `jvm`, `linuxX64`, `linuxArm64` and `macosArm64`. The probe measured
the consequence: the two runtimes iterate a `HashMap` in different orders
([research-parity §1.2](../research/research-parity.md)), so a window payload from a JVM-hosted
agent lists the same series in a different order from a native one.

Nothing depends on that order today — the server merges by key — and this item is not reporting a
bug. It is removing the one place in the portfolio where a shipped payload's bytes depend on which
runtime produced them, before a golden file, a payload hash or an idempotency key starts depending
on it.

- **The decision and its reason.** One `.sortedWith` on the series keys in `drain()`. The fix is
  already written next door: `Histogram.toSparse()` sorts its bucket keys for exactly this reason,
  and the contrast between the two is what makes this a one-line change rather than an argument.
- **Rejected: waiting for the gate to find it.** B-13 would flag it, and then this fix would be a
  precondition for that build going green — which turns a one-line change into a blocker on a
  different repository at the worst moment.
- **Rejected: a `LinkedHashMap` in the aggregator.** It would make the order insertion order, which
  is *also* not stable between runs — first-seen route order depends on traffic. Sorting at the
  boundary states the intent; changing the map type hides it.
- **Does not cover** the general question of whether any other payload in the portfolio depends on
  hash order. §1.3 searched by hand, which is a hypothesis (H4), and the gate is what settles it.

- AC: two agents of the same commit, one JVM-hosted and one native, produce byte-identical `routes`
  ordering for the same traffic; the existing `WindowAggregatorTest` still passes unchanged.
- Anchors: `metrik/agent/src/commonMain/kotlin/io/github/youndie/metrik/agent/WindowAggregator.kt`,
  `metrik/shared/src/commonMain/kotlin/io/github/youndie/metrik/wire/Histogram.kt`
