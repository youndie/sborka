---
id: kapkan
title: kapkan — the ktlint rule set
type: service
module: build-logic/kapkan
tech_stack: [Kotlin, ktlint rule API, JUnit 5]
owner: unassigned
depends_on: []
publishes: [io.github.youndie.sborka:kapkan]
---

# kapkan

## 1. Responsibility

Five source-level rules, each encoding one class of defect this stack paid a stand run to find:
`foreign-import-in-common`, `swallowed-failure`, `wall-clock`, `cancellation-swallowed`, and
`suppression-needs-a-reason`. `sborka.lint` puts the coordinate on the `ktlintRuleset`
configuration at its own version; there is no switch to turn it off.

**What it deliberately does not do.** No rule here needs a type, and the one that did — "a `@Test`
that returns a value" — is not a rule at all: it is `DeclaredTests` in `sborka.test`, which
compares declared tests with what JUnit ran and so catches every reason a test did not execute
rather than one syntactic shape of it. The research behind those choices, with the counts that
killed three of six proposed rules, is `docs/kapkan.md`.

**It is not where the perf rules go.** They read compiled output; see
[feature-perf-lint](../features/feature-perf-lint.md) §2 and
[research-perf-lint](../research/research-perf-lint.md) D2. What kapkan contributes to that set is
the suppression form: an id, and a reason beside it that `suppression-needs-a-reason` enforces.

## 2. Code anchors

| What | Code |
|---|---|
| the rules | `build-logic/kapkan/src/main/kotlin/io/github/youndie/sborka/kapkan/` |
| the rule set provider | `build-logic/kapkan/src/main/kotlin/io/github/youndie/sborka/kapkan/Kapkan.kt` |
| the suppression rule | `build-logic/kapkan/src/main/kotlin/io/github/youndie/sborka/kapkan/SuppressionNeedsAReasonRule.kt` |
| how the rules are tested | `build-logic/kapkan/src/test/kotlin/io/github/youndie/sborka/kapkan/LintHarness.kt` |
| where consumers get it | `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/lint.gradle.kts` |

## 3. How it is built

`embedded-kotlin`, and both ktlint dependencies are `compileOnly`: ktlint's worker already has the
rule API on its classpath, and a rule set jar carrying its own copy brings a second `RuleId` class —
the engine matches ids by the class it loaded, not by the name. The jar is loaded through
`META-INF/services/…RuleSetProviderV3`, which is why it must carry neither `kotlin-dsl` nor KGP.

## 4. Quirks

* **`kapkan` is excluded from the release oracle (proba).** Nothing compiles against this jar, its
  ktlint dependencies are `compileOnly`, and proba honestly reports its whole public API as
  `api-unreachable` and fails the release. What is checked instead: the jar downloads and carries
  its service file.
* **The consumer stand must include `../build-logic` outside `pluginManagement`.** That block
  resolves plugins by marker and does not substitute ordinary dependencies — and kapkan is asked for
  as an ordinary dependency, on `ktlintRuleset`.
* **A suppression id ktlint did not load is refused**, which is a check for free: a file suppressing
  a kapkan rule only lints while the jar actually arrived. The bytecode rules are the other side of
  the same coin — ktlint never loads them, so their ids carry no `ktlint:` prefix.
