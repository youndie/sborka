---
id: B-01
title: "Ask the class-file walk how many containers a body materialises"
status: open
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
