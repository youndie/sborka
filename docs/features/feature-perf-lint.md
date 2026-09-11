---
id: feature-perf-lint
title: Perf-lint — three rules with a measurement behind each
type: feature
status: active
owner: unassigned
involved_services:
  - core
  - settings
  - kapkan
client_entries: []
api: []
tags: [lint, bytecode, performance]
---

# Perf-lint — three rules with a measurement behind each

## 1. Overview

A repository that applies sborka gets three questions asked of its compiled output: is a regular
expression being compiled on every call, does a body allocate a container per link of a chain, and
is a body too long for the JIT to inline into a hot caller. Each question exists because a profile
of a running Ktor service charged more than 2 % of its allocations to that shape — the numbers, the
profiles they came from, and the counts of how often each fires on this portfolio are in
[research-perf-lint](../research/research-perf-lint.md).

What the reader gets is not a score. The point of the set is that each rule can say what it is
worth, and that only the one whose findings are cheap enough to read is ever allowed to fail a
build.

**What exists today, and what is a target.** This document describes current behaviour; every
sentence about behaviour that is not built yet says *(target)* and names the backlog item that
builds it.

| | Today | Target |
|---|---|---|
| pattern built per call | detected, printed by `kapkanMethodSizes` | fails inside a declared hot module — `B-03` |
| two or more materialisations | **not detected at all** | detected and printed — `B-01`; the counts are in the research already, from a probe |
| body over `FreqInlineSize` | detected, printed | stays printed, permanently — a decision, not an item |
| a declared scope (`sborka.perflint.hot`) | does not exist | `B-02`, and every gate is blocked on it |

So today the whole set is a report, and `kapkanMethodSizes` is not in `check`. The order in which
that changes is [backlog.md](../../backlog.md).

## 2. Business rules

**R1 — a pattern built per call is a finding.** `Regex(…)` or `Pattern.compile(…)` reached from a
method body. `<clinit>` is excluded: that is where a pattern belongs. `<init>` is **not** excluded
and the finding there is a question — a pattern in a constructor costs per instance, and bytecode
does not carry how long an instance lives.

**R2 — two or more eager materialisations in one body are a finding** *(target — the detector
does not exist yet; `B-01`)*. A materialisation is a fresh
`ArrayList`/`LinkedHashMap`/`LinkedHashSet` emitted by an inlined operator, or a call to a
non-inline eager operator over a collection **or a string** (`sortedWith`, `chunked`, `reversed`,
`joinToString`, `split`, …). `asSequence()` and `Flow` chains are not findings — they materialise
once at the end, and in bytecode they are different call targets, which is why the rule needs no
type resolution.

**R3 — a body over `FreqInlineSize` is printed, never failed.** The threshold is read from the JVM
that runs the build and printed beside the finding, because it is a `pd` (platform-dependent)
product flag. A refusal to inline is not a measured cost, so this rule reports.

**R4 — a gate needs a scope, and the scope is declared** *(target — `B-02`)*. `sborka.perflint.hot` in
`gradle.properties` names modules; a finding of R1 inside one fails the build, everything else
prints. With the property absent, the whole set is a report — a repository cannot be broken by
adopting a version of sborka.

**R5 — suppression carries a reason.** Enforced today for the ktlint rule ids; *(target)* for
the bytecode ids, which arrive with the first gate (`B-03`). `@Suppress("kapkan:pattern-built-per-call", "…because…")`,
the form kapkan's `suppression-needs-a-reason` already enforces, with the `kapkan:` prefix and no
ktlint prefix — ktlint refuses an id naming a rule it did not load, and it does not load the
bytecode rules.

**R6 — a body the walk could not read is named, not counted as clean.** `MethodSizes.Report.unwalked`
already does this and the reports print it; a rule inherits the same obligation.

## 3. Flow

1. `sborka.settings` registers the report tasks on the root project, after every `*Classes` task
   that the JVM-like targets register (`gradle.projectsEvaluated`, by name — a predicate over the
   task collection would instantiate every task in every project).
2. The task walks the class directories with sborka's own reader: constant pool → class header →
   fields skipped by their attribute lengths → method bodies, with an instruction walk per body.
3. Each body answers the three questions plus the `Intrinsics.check*` count.
4. The findings are written to `build/reports/kapkan/method-sizes.txt` and echoed to the log.
5. *(target)* Findings of R1 inside a module named by `sborka.perflint.hot`, minus suppressions,
   fail the task.

## 4. Code anchors

| Module | Code |
|---|---|
| core | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/MethodSizes.kt` — thresholds, the scan, the pattern question |
| core | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/Bytecode.kt` — the instruction walk |
| core | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/ConstantPool.kt` — the pool both readers share |
| settings | `build-logic/settings/src/main/kotlin/io/github/youndie/sborka/settings.settings.gradle.kts` — task registration and what the report prints |
| kapkan | `build-logic/kapkan/src/main/kotlin/io/github/youndie/sborka/kapkan/SuppressionNeedsAReasonRule.kt` — the suppression form R5 reuses |
| core | `build-logic/core/src/test/kotlin/io/github/youndie/sborka/internal/BytecodeTest.kt` — the pattern question, against compiler-emitted bytecode |
| probe | `docs/research/probe/perfprobe.py` — the probe that produced the counts, not the implementation |

## 5. Scenarios (BDD / test cases)

### Scenario: a pattern built in a method body is reported
* **Given:** a compiled class whose method calls `kotlin.text.Regex.<init>`
* **When:** `kapkanMethodSizes` runs
* **Then:** the report names the method and says `pattern(s) built per call — a Regex in <clinit> is built once`
* **Automated:** `BytecodeTest`

### Scenario: a pattern built in a static initialiser is not reported
* **Given:** a compiled class whose `<clinit>` calls `kotlin.text.Regex.<init>` and whose methods do not
* **When:** the same task runs
* **Then:** the class produces no pattern finding
* **Automated:** `BytecodeTest`

### Scenario: the size of a body is the size the JVM sees
* **Given:** a compiled method
* **When:** the reader measures it
* **Then:** the byte count equals what `javap -c -p` prints for the same method
* **Automated:** `MethodSizesTest`

### Scenario: a report over no class files fails rather than reporting nothing
* **Given:** a repository where nothing has been compiled
* **When:** `kapkanMethodSizes` runs
* **Then:** the task fails with `read no class files under N build directories`, naming the fix
* **Automated:** manual — the guard is a `check(...)` in the settings script with no test behind it

### Scenario: an eager chain is a finding and its sequence form is not *(target)*
* **Given:** two methods, `xs.filter { }.map { }.sortedBy { }` and the same with `asSequence()`
* **When:** the chain question runs over their compiled bodies
* **Then:** the first is a finding with four materialisations and the second is not a finding
* **And:** `docs/research/probe/Control.kt` holds both shapes as the control the probe was checked against

### Scenario: a hot module turns a pattern finding into a failure *(target)*
* **Given:** `sborka.perflint.hot=:server` and a `Regex` built per call in `:server`
* **When:** `check` runs
* **Then:** the build fails naming the method, the profile share behind the rule, and the suppression form
* **And:** the same finding in `:client` prints and the build passes

### Scenario: a suppression without a reason is itself a finding *(target)*
* **Given:** `@Suppress("kapkan:pattern-built-per-call")` with no reason beside it
* **When:** `check` runs
* **Then:** kapkan's `suppression-needs-a-reason` fails on it

## 6. Out of scope

* Boxing (1.26 % of bytes on the stand), lazy logging (≤ 1.57 %) and `Intrinsics.check*` — measured
  below the 2 % line and deliberately not rules. The assertion count stays a printed number.
* Anything about Ktor, coroutines or kotlinx: those own 90 % of the profile and no rule over user
  code reaches them. That was zavarnik's finding and closing it is not this set's job.
* A rewriting pass. zavarnik measured the ceiling of one and did not build it; these rules point,
  a person fixes.
* Sequences as an answer to everything: below a few dozen elements the eager chain is usually
  faster, and nothing here has measured the crossover.

## 7. Quirks

* **`kapkanMethodSizes` is not in `check` and that is deliberate.** It prints. Wiring it into
  `check` without R4's scope would fail 1 058 methods across this portfolio.
* **The report reads what was compiled, not what is in the tree.** A clone whose build directory predates
  the last edit reports yesterday's code, silently. The `classesRead > 0` guard catches "nothing
  compiled", not "stale".
* **A multiplatform build compiles one class into several directories**, so a body can appear more
  than once in a report; the counts in the research are of distinct methods for this reason.
* **Three of the eight pattern findings in this portfolio are constructors.** The rule is right that
  the pattern is rebuilt; whether that costs anything depends on instance lifetime, which is not in
  the class file.
* **`MaxInlineSize` (35 bytes) is in the threshold table and not in the report.** At 35 bytes almost
  every method qualifies and the report would be a counter.
