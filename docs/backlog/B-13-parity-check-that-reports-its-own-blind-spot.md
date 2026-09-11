---
id: B-13
title: "parityCheck: fail on an unlisted difference, and say in the same line what it did not test"
status: open
priority: P1
size: L
stage: stage-5-parity-gate
blocked_by: [B-12]
---

# B-13 — parityCheck: fail on an unlisted difference, and say in the same line what it did not test

The gate itself: a task that runs the suite on both targets, diffs the two transcripts from B-12,
and fails on any row not listed in `parity-allowlist.yaml`. Wired the way `sborka.kmp` wires the
test gate — by the convention, so a repository gets it by taking the plugin rather than by
remembering to add it.

The cost is small and the research says why: the native release link for every declared target is
**already** inside the existing pull-request build, and so is running the suite on both targets
([research-parity §1.6](../research/research-parity.md)). The brief budgeted ten minutes for a
native compile that is already paid for; what this adds is a diff.

- **The allowlist is per row, not per class.** One entry per differing transcript row: the id, the
  class from §1.2, the reason, and the value each side produces. Sixteen of the seventeen known
  differences are message text, and a class-level entry saying "exception messages may differ"
  would silence the one row that changes an exception's *class* along with them. Because the entry
  carries both values, a row whose values later change still fails — the allowlist pins a known
  difference rather than muting the probe.
- **Neither target is the reference.** The report prints `jvm` and `native` and labels neither
  "expected". The JVM is the older runtime and is the wrong one at least once already: `\bé` finds
  nothing on the JVM and matches on native.
- **The task names its own blind spot, in the result line.** "42 rows compared on jvm and
  linuxX64; no socket, DNS or TLS path was exercised." Every divergence this portfolio has actually
  been bitten by was in the platform layer (§1.4) — a hostname that does not resolve on native, a
  TLS stack that is not there, a Ktor plugin published for one target — and an in-process gate
  catches none of them. A green gate that does not say what it skipped accumulates trust it has not
  earned.
- **Rejected: starting a JVM service and a native one and diffing HTTP responses**, which is what
  the brief asked for. No subject has a runnable JVM service: no server module applies the
  `application` plugin or declares a `mainClass`, and shildik's only distribution module declares
  `linuxX64` alone. Building one per repository, to be tested and never shipped, is not a
  three-day box (research D1).
- **Does not cover** razves, which is a CLI and gets the process-level gate the brief described —
  B-14.

- AC: on tracy, `./gradlew parityCheck` is green on the current commit with its allowlist, and red
  on a commit that serialises a `HashMap` into a response; the failure names the row, both values
  and the allowlist entry that would silence it.
- Anchors: `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts`,
  `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/test.gradle.kts`,
  `docs/research/research-parity.md`
