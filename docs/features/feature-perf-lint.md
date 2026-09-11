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
| pattern built per call | detected, printed, and **failing** inside a declared hot module (`B-03`) | — |
| two or more materialisations | detected, printed (`B-01`) | a gate inside a declared hot module, if `B-04` says the rule holds for client code |
| body over `FreqInlineSize` | detected, printed | stays printed, permanently — a decision, not an item |
| a declared scope (`sborka.perflint.hot`) | declared, validated, marked `[hot]`, and what puts the task into `check` (`B-02`, `B-03`) | — |

So the set is a report, except for one gate: a pattern compiled per call inside a hot module.
`kapkanMethodSizes` joins `check` only where `sborka.perflint.hot` is set — where nothing is
declared hot it cannot fail, and a task that cannot fail has no business slowing a build down.

## 2. Business rules

**R1 — a pattern built per call is a finding.** `Regex(…)` or `Pattern.compile(…)` reached from a
method body. `<clinit>` is excluded: that is where a pattern belongs. `<init>` is **not** excluded
and the finding there is a question — a pattern in a constructor costs per instance, and bytecode
does not carry how long an instance lives.

**R2 — two or more eager materialisations in one body are a finding.** A materialisation is a fresh
`ArrayList`/`LinkedHashMap`/`LinkedHashSet` emitted by an inlined operator, or a call to a
non-inline eager operator over a collection **or a string** (`sortedWith`, `chunked`, `reversed`,
`joinToString`, `split`, …). `asSequence()` and `Flow` chains are not findings — they materialise
once at the end, and in bytecode they are different call targets, which is why the rule needs no
type resolution.

**R2a — what a chain finding promises, measured.** Rewriting the two methods konekt's profile
charged most took them from 1.30 % of all allocated bytes to 0.00 %, while bytes per request moved
−0.75 % against a 4.46 % spread between repetitions — the finding was removed and the service-level
A/B could not see it ([research-perf-lint §1.7](../research/research-perf-lint.md)). So a message
this lint prints says what the profile will stop charging, and says nothing about throughput or
latency.

**R3 — a body over `FreqInlineSize` is printed, never failed.** The threshold is read from the JVM
that runs the build and printed beside the finding, because it is a `pd` (platform-dependent)
product flag. A refusal to inline is not a measured cost, so this rule reports.

**R4 — a gate needs a scope, and the scope is declared.** `sborka.perflint.hot` in
`gradle.properties` names modules; a finding of R1 inside one fails the build, everything else
prints. With the property
absent, the whole set is a report — a repository cannot be broken by adopting a version of sborka.
A path no project has, or a module that compiled no class files, fails the task: an empty scope
cannot be told from a passing one.

**R5 — suppression carries a reason.** For a bytecode finding the annotation goes **on the line
that builds the pattern** — that is where the reader wants the reason, and it is the one anchor a
source reader can be sure of without a parser. `@Suppress("kapkan:pattern-built-per-call", "…because…")`,
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
4. The findings are written to `build/reports/kapkan/method-sizes.txt` and echoed to the log, each
   line carrying its module and `[hot]` where the module is in scope, followed by a line naming the
   scope itself — "nothing declared hot" included.
5. Findings of R1 inside a module named by `sborka.perflint.hot`, minus the ones a `@Suppress`
   beside the pattern answers for, fail the task — and `check` depends on the task wherever a scope
   is declared. A finding whose source file cannot be found is **not** treated as suppressed.

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

### Scenario: a class compiled into two outputs is one finding
* **Given:** the same class file under `kotlin/jvm/main` and under `kotlin/androidDebug`
* **When:** the reader scans the tree above both
* **Then:** the method is reported once, naming both outputs, and the summary counts it as one method
* **Automated:** `MethodSizesTest`

### Scenario: a scope that matches nothing fails rather than gating nothing
* **Given:** `sborka.perflint.hot` naming a path no project has, or a module that compiled no class files
* **When:** `kapkanMethodSizes` runs
* **Then:** the task fails naming the unknown path and listing the paths the build does have
* **And:** with the property empty the report prints `nothing declared hot` and passes
* **Automated:** manual — three controls run by hand on the stand, recorded in `B-02`

### Scenario: a finding in a hot module is marked
* **Given:** `sborka.perflint.hot=:jvm-lib` and an eager chain in `:jvm-lib`
* **When:** the report runs
* **Then:** the line reads `:jvm-lib [hot] stand.DigitsKt.heaviest…: 4 eager materialisation(s)`
* **And:** the summary says how many findings are in hot modules
* **Automated:** the stand carries the chain and CI runs `-p stand kapkanMethodSizes`

### Scenario: a report over no class files fails rather than reporting nothing
* **Given:** a repository where nothing has been compiled
* **When:** `kapkanMethodSizes` runs
* **Then:** the task fails with `read no class files under N build directories`, naming the fix
* **Automated:** manual — the guard is a `check(...)` in the settings script with no test behind it

### Scenario: an eager chain is a finding and its sequence form is not
* **Given:** two methods, `numbers.filter { }.map { }.sortedBy { }.map { }` and the same with `asSequence()`
* **When:** the chain question runs over their compiled bodies
* **Then:** the first is a finding and the second is not, and neither is a method with one operator
* **And:** the count agrees with what `javap` prints for the same body
* **Automated:** `BytecodeTest`

### Scenario: a string chain is a finding too
* **Given:** `value.toString().reversed().chunked(3).joinToString(",").reversed()` — four intermediates, no collection operator
* **When:** the same question runs
* **Then:** it is a finding, because a reader watching only `kotlin.collections` named none of the methods a real service's profile charges
* **Automated:** `BytecodeTest`

### Scenario: a hot module turns a pattern finding into a failure
* **Given:** `sborka.perflint.hot=:jvm-lib` and a `Regex` built per call in `:jvm-lib` with no suppression
* **When:** `check` runs
* **Then:** the build fails naming the method, the 6.45 % share with the profile it came from, and the suppression form
* **And:** the same finding in `:kmp-lib`, which is not in scope, prints and the build passes
* **Automated:** the stand carries both; the failing half is a control run by hand (`B-03`)

### Scenario: a suppression without a reason is itself a finding
* **Given:** `@Suppress("kapkan:pattern-built-per-call")` with no reason beside it
* **When:** `check` runs
* **Then:** ktlint fails with `kapkan:pattern-built-per-call is switched off here and the annotation does not say why`
* **Automated:** `SuppressionNeedsAReasonRuleTest`, plus a control run by hand on the stand

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
* **A multiplatform build compiles one class into several directories**, and the report says so:
  one finding, `(2 copies)` beside it, and `and they disagreed — the largest is reported` when the
  copies were not identical. The summary counts distinct classes and methods and names how many
  further copies stood behind them.
* **Three of the eight pattern findings in this portfolio are constructors.** The rule is right that
  the pattern is rebuilt; whether that costs anything depends on instance lifetime, which is not in
  the class file.
* **`MaxInlineSize` (35 bytes) is in the threshold table and not in the report.** At 35 bytes almost
  every method qualifies and the report would be a counter.
