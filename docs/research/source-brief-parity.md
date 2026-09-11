---
id: source-brief-parity
title: Brief B as it arrived — one contract, two binaries
type: research
status: active
date: 2026-09-11
---

# Source brief: the JVM/native parity gate

Kept verbatim, as the input rather than a finding. [research-parity](research-parity.md) §3 lists
where it turned out to be asking for something the code does not support — and that comparison is
only readable while the original text is still here to compare against.

It arrived as one of a pair; Brief A, a Kotlin/Native binary in a `scratch` image, was deferred and
is not in this repository yet. The closing paragraph on order is the brief's own and is kept
because it explains why this half was taken first.

---

## Brief B — One contract, two binaries: the JVM/native parity gate

### Question

When the same Kotlin code is developed on the JVM and shipped as a native binary, where does
behaviour diverge, and can a build stay red until it does not?

### Why it matters

The fast iteration loop on Kotlin/Native is "develop on the JVM, ship native": seconds instead of
minutes per compile. It rests on the two targets behaving the same, and today that is a belief,
not a check. The failure mode is quiet: both binaries pass their unit tests, and production —
the native one — returns JSON with keys in a different order, a regex that matches differently,
or a date in the wrong zone.

### Non-goals

- Performance parity. The K/N-under-load post covers it; this brief is about behaviour.
- Making every difference disappear. Some are documented platform properties; the goal is to
  know them, test them, and gate the rest.
- Replacing the contract tests. The contract stays the oracle for correctness; parity is a second,
  stricter check.

### Research questions, with pre-declared outcomes

**RQ1 — Catalogue of known divergence classes.** From the Kotlin docs, KT issues and the
stdlib sources: regex engine (`kotlin.text.regex` on native vs `java.util.regex`), `HashMap` /
`HashSet` iteration order, `hashCode` of strings and data classes, float/double formatting and
parsing, `String.uppercase()` and locale, `kotlinx-datetime` timezone database on native,
`kotlinx.serialization` edge cases (NaN, ±Inf, large integers, key order), `Random` defaults,
exception messages and stack traces, `Dispatchers.IO` limits, file and DNS behaviour.
Deliverable: a table — class, expected difference, a one-line test that exposes it.
No colour; this is the checklist.

**RQ2 — What diverges today.** Run each subject's existing black-box suite (contract tests, k6
scenario, conformance kit where one exists) against the JVM and the native build of the same
commit, and diff every response after a declared normaliser (timestamps, generated ids, trace
ids).
- Green: zero differing responses → the gate is cheap and RQ3 is a small task.
- Red: N differing → each becomes either a fix or an entry in the platform-differences page,
  with the test that pins it. Both outcomes are fine; "N" is the number to publish.
- Prediction: 2–5 differences, the first two being key order in JSON produced from a map and a
  regex with a character class or a look-around.

**RQ3 — The gate.** A sborka task `parityCheck`: builds both targets, starts each against the
same stand, runs the same suite, applies the normaliser, and fails on any diff not listed in
`parity-allowlist.yaml` (each entry: the class from RQ1 and a reason). The allowlist is the
platform-differences page in machine form.
- Green: red build on an injected divergence (a test that deliberately serialises a `HashMap`)
  and green on the current commit with its allowlist.
- Design decision to record: the JVM is *not* the reference. Both are compared to the contract;
  parity reports where they differ from each other even when both satisfy it.

**RQ4 — What it costs in CI.** Wall time of `parityCheck` on the hosted runner, with and without
the Gradle build cache for the native link.
- Green: under 10 minutes with cache → runs on every PR.
- Red: over → nightly, and the PR build runs only the JVM suite plus the injected-divergence
  smoke test. Publish the minutes either way; the native compile time is the real cost of the
  pattern and it should be on the page.

### Kill criteria

- RQ2 cannot produce a stable diff because the suites themselves are non-deterministic beyond what
  a normaliser can express → fix the suites first, this brief waits.
- RQ4 over 30 minutes even with cache → the gate is nightly-only and the brief says so; no
  further engineering to make it faster inside this box.

### Deliverables

- `docs/research/research-parity.md`: the RQ1 table, the RQ2 diff list, the CI minutes.
- The sborka task and the allowlist format.
- A page on kotlin.website: "Kotlin/Native vs JVM: the behaviour differences we test for" —
  every row with its test. This is the one page in the set that people will search for.

### Order

B before A. A is a linker question that may end in a ticket; B changes how every dual-target
service is built and is the foundation the starter stands on. Three days each, B first.
