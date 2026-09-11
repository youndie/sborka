---
id: B-04
title: "Read the chain findings inside Compose code and decide whether the rule applies there"
status: question
priority: P2
size: S
stage: stage-1-evidence
epic: feature-perf-lint
blocked_by: [B-01]
---

# B-04 — Read the chain findings inside Compose code and decide whether the rule applies there

45 of the 325 chain findings are in metrik and shashki, which are Compose repositories, and every
number that justifies the rule came from a server profile. A body that allocates two containers per
recomposition is either the most expensive kind of finding there is or completely normal, and
nobody here has looked.

- **The decision this item owes.** Either the rule applies to client code with the same threshold,
  or client source sets are excluded by default and the research says on what evidence. A rule that
  fires in a place its measurements never covered is the shape this whole set exists to avoid.
- **How to decide it cheaply.** Read the 45. They are listed by
  `docs/research/probe/results-2026-09-11.txt`; a finding in a `@Composable` that runs per frame and
  one in a screen built once are different answers.
- **Does not cover** profiling a Compose client. That is a separate measurement with its own
  harness, and if this item concludes it is needed, it says so instead of guessing.

- AC: a paragraph in [research-perf-lint](../research/research-perf-lint.md) §4 that answers open
  question 1 with the count of findings read and what they were, replacing the question.
- Anchors: `sborka/docs/research/probe/results-2026-09-11.txt`,
  `sborka/docs/research/research-perf-lint.md`
