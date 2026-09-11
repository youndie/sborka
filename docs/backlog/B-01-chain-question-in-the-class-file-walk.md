---
id: B-01
title: "Ask the class-file walk how many containers a body materialises"
status: done
priority: P1
size: M
stage: stage-1-evidence
epic: feature-perf-lint
---

# B-01 — Ask the class-file walk how many containers a body materialises

`MethodSizes` already walks every instruction of every body to answer two questions (patterns built
per call, `Intrinsics.check*` sites). The third question — the one with the largest measured share
behind it — is not asked: how many eager containers does this body materialise. On konekt the
methods that answer "two or more" own 30–32 % of everything user code allocates
([research-perf-lint](../research/research-perf-lint.md) §1.4).

- **The decision and its reason.** Count materialisations, not operator calls. `filter`, `map` and
  `groupBy` are `inline`, so no `CollectionsKt.filter` survives compilation — what survives is a
  fresh `ArrayList`/`LinkedHashMap`/`LinkedHashSet` plus a loop, and calls to the non-inline eager
  operators (`sortedWith`, `chunked`, `reversed`, `joinToString`, `split`, `take`, `distinct`, …).
- **`kotlin.text` counts, not only `kotlin.collections`.** This is the correction the profile made:
  a definition watching collections alone named **none** of the methods konekt's profile charges,
  because the largest one is `MoneyFormat.group` —
  `reversed().chunked(3).joinToString(sep).reversed()`. Widening cost 124 extra findings across the
  portfolio (201 → 325 distinct methods) and bought the top two owners.
- **Rejected: a ktlint rule over PSI.** The chain is visible in source, but telling a `List` chain
  from a `Sequence` or `Flow` chain — which is the fix — needs type resolution. In bytecode they are
  different call targets and no types are needed. Same reasoning as `docs/kapkan.md` §2.1.
- **Does not cover** dataflow: two unrelated containers in one body count as two. The number is an
  upper bound and the document says so rather than the rule pretending otherwise.

- AC: `kapkanMethodSizes` prints a third section naming bodies with two or more materialisations and
  how many, and `docs/research/probe/Control.kt`'s four shapes — eager chain, single operator,
  `asSequence()` chain, string chain — come out as finding / no finding / no finding / finding when
  compiled and scanned.
- AC: the counts on this portfolio are within a finding or two of the probe's 325, or the difference
  is explained in the research rather than averaged away.
- Anchors: `sborka/build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/MethodSizes.kt`,
  `sborka/build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/Bytecode.kt`,
  `sborka/docs/research/probe/perfprobe.py`

## Done, 2026-09-11

The walk now records `new` as well as the invokes (`Bytecode.NEW`), the pool can name a class
(`ConstantPool.Pool.className`), and `MethodSizes` counts materialisations per body:
`CHAIN_FROM = 2`, containers from an inlined operator plus calls into the five eager facades —
`CollectionsKt`, `ArraysKt`, `MapsKt`, `SetsKt` and **`StringsKt`**. `kapkanMethodSizes` prints a
fourth section and names the count in its summary line.

- **Verified against the probe where both read the same files.** On the three single-output
  repositories — bochka, proba, boulab — the task and the probe give identical chain counts
  (90, 35, 11) and identical pattern counts (3, 2, 0). bochka's size count differs by one: the
  probe takes the last instruction's offset as the body size, which understates a body by the
  length of that instruction.
- **Elsewhere the task reports more, and it is not the rule.** A multiplatform build writes one
  class into several output directories; the task counts every copy, the probe counted distinct
  methods. That is `B-07`.
- **The controls are compiled by the build**: `MethodSizesChainFixture` holds an eager chain (a
  finding), one operator (not), an `asSequence()` chain (not), a string chain (a finding) and
  arithmetic (not). A third test counts the same materialisations out of `javap` — the second
  reader that would catch a pool index read from the wrong place.
- Not covered: dataflow. Two unrelated containers in one body count as two, and the report says so
  rather than pretending the number is exact.
