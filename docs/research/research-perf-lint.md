---
id: research-perf-lint
title: A perf-lint whose rules carry their own measurements
type: research
status: active
date: 2026-09-11
---

# Research: a performance lint with three rules and a number behind each

The usual performance checklist has thirty entries and no arithmetic. It is not wrong so much as
unfalsifiable: nothing in it says how much any entry is worth on the code it is applied to, so
every entry costs the same to argue about and none of them can ever be retired. This document is
the opposite exercise. A rule is written down only where a profile of a running service charged
more than 2 % of something to the shape the rule forbids, and every rule carries that number, the
file the number came from, and the count of how often it fires on this portfolio — because a rule
that fires a thousand times is a counter, whatever its evidence.

Three rules came out of it. Two of them are worth their noise; the third is worth printing and not
worth failing a build on, and that is a conclusion rather than a compromise.

This document records **verified facts** (recomputed here, from the files named beside them),
**decisions**, and **risks**. Anything not verified is called a hypothesis and says where it gets
settled. The tools are in [`probe/`](probe/) — a probe, not the lint; §3 D6 says why it is
committed.

---

## 1. Verified facts

### 1.1 Where a Ktor service's allocations actually go

The profiles are zavarnik's, taken 2026-09-06 on Ubuntu 24.04 / OpenJDK 25.0.4 with
async-profiler 4.5 — the stand is a Ktor 3.5.2 CIO service under `oha` at 64 keep-alive
connections, and `/business` is its endpoint written to give user code the most to do. Every share
below was **recomputed on 2026-09-11** from the collapsed files with
[`probe/profile.py`](probe/profile.py) rather than copied out of the post that first reported them.

| Fact | Where verified |
|---|---|
| User code owns **9.95 %** of allocated bytes on `/business`, by owner | recomputed here: `profile.py owners zavarnik/bench/profile/results/baseline/business.alloc.collapsed bench.` |
| The same endpoint's CPU is 2.1 % user code by owner; the rest is kotlinx 73.9 %, Ktor 15.5 %, stdlib 8.4 % | not recomputed — zavarnik `docs/research/research-optimizer.md` §1.4, from `business.cpu.collapsed` |
| Every stack carrying both a `bench.*` frame and a `java.util.regex.*` frame: **6.45 %** of all bytes — one `Regex(…)` built inside a handler, with its `Matcher` (1.77 %), `int[]` (1.56 %), `boolean[]` (1.49 %) | `profile.py shares …/business.alloc.collapsed` |
| Intermediate collections: **2.72 %** counted as "a `CollectionsKt` frame is on the stack", **4.20 %** counted as the four container leaves whose owner is user code — `Object[]` 1.80, `LinkedHashMap$Entry` 0.89, `ArrayList` 0.80, `ArrayList$Itr` 0.71 | same two commands |
| Boxing 1.26 %, three `logger.debug` templates ≤ 1.57 %, Ktor's `KClassImpl.toString` on every `call.receive<T>()` 1.98 % | zavarnik `docs/research/research-optimizer.md` §1.4 |
| 10 of 283 user methods compile to more than 325 bytes; under load, five inlining refusals name user methods and all five are "hot method too big" — `Pricing::quote` 1827 b, an `invokeSuspend` 816 b, a serializer's `deserialize` 374 b | zavarnik `bench/profile/results/`, `-XX:+PrintCompilation -XX:+PrintInlining` |
| On a real service — konekt, Ktor CIO with Exposed and Postgres — user code owns **5.41 %** of allocated bytes at 200 rps and **3.41 %** at 50 rps | `profile.py owners zavarnik/bench/profile/results/konekt-screens-200/screens.alloc.collapsed io.konekt.` |

**Consequence — the 2 % line is not arbitrary, it is the noise floor of the whole exercise.** User
code owns a tenth of the allocations on a stand built to flatter it and a twentieth on a real
service. A rule that addresses 1 % of bytes is addressing a tenth of a tenth, and there is no
mechanism by which anyone would ever notice it working. Two shapes clear the line — a pattern
rebuilt per call and intermediate containers — and they clear it by a factor of three.

**Consequence — the interesting number is a share of a share.** 6.45 % of all allocated bytes is
two thirds of everything user code owns on that endpoint. The rule is not "a small win": on that
one profile it is most of what a rewrite of user code could ever have won, which is the same
finding that closed zavarnik's second phase.

### 1.2 The thresholds C2 actually uses

| Fact | Where verified |
|---|---|
| `FreqInlineSize` 325, `MaxInlineSize` 35, `InlineSmallCode` 2500, `DontCompileHugeMethods` true — identical on Linux/x86-64 (JDK 25.0.4) and macOS/aarch64 (JDK 25.0.2) | `java -XX:+PrintFlagsFinal -version`, both machines |
| `FreqInlineSize` is a `C2 pd product` flag — platform-dependent by declaration, equal on both platforms in fact | the `pd` marker in the same output |

**Consequence.** The number is read at report time and printed beside each finding
(`MethodSizes.Threshold`), not written into prose. A `pd` flag that happens to agree on two
platforms today is exactly the kind of fact that a document repeats for three years after it stops
being true.

### 1.3 What the three rules would find in this portfolio

A probe — [`probe/perfprobe.py`](probe/perfprobe.py), `javap -c -p` over compiled output, **not**
the lint — was run on 2026-09-11 over eleven repositories' JVM classes as they lay in each clone's build
directory. Counts are of distinct methods; the full output is
[`probe/results-2026-09-11.txt`](probe/results-2026-09-11.txt).

| repository | classes | methods | pattern per call | chain ≥ 2 | body > 325 b |
|---|---|---|---|---|---|
| konekt | 1646 | 12 108 | 0 | 43 | 184 |
| metrik | 535 | 5 001 | 0 | 27 | 176 |
| katcher | 781 | 7 198 | 2 | 60 | 203 |
| shashki | 1488 | 13 341 | 1 | 18 | 230 |
| bochka | 354 | 4 688 | 3 | 90 | 118 |
| s3kn | 410 | 2 119 | 0 | 17 | 12 |
| petich | 192 | 1 791 | 0 | 3 | 19 |
| chronik | 84 | 777 | 0 | 7 | 14 |
| proba | 175 | 1 768 | 2 | 35 | 51 |
| boulab | 89 | 1 089 | 0 | 11 | 36 |
| kmp-smtp-client | 749 | 3 543 | 0 | 14 | 15 |
| **total** | **6 503** | **53 423** | **8** | **325** | **1 058** |

**Consequence — the noise is what separates the three rules, and it separates them by two orders
of magnitude.** Eight findings across a portfolio can be read one by one in an afternoon; 325 can
be read per repository; 1 058 is a counter. Of the bodies over 325 bytes, 791 were written by
hand, 162 are `suspend` state machines and 72 are generated serializers — so even the "written by
a person" half is 791 findings, and the largest bodies in the portfolio are Compose composables
(`metrik`'s `ServiceGridCard`, 7 180 b), where the inline threshold decides nothing anybody cares
about.

**Every one of the eight pattern findings, since eight is a number one can print:**

| Finding | Reading |
|---|---|
| `katcher` `SymbolMapRouting.kt:318` `Regex("$key=\"([^\"]*)\"")` | interpolated — **cannot** be hoisted as it stands; the fix is a cache or a parser, not one line |
| `katcher` `Retracer.kt:24,25` two `val` in the constructor | per instance, not per call — beside them, line 7, the same file hoists a third to a companion |
| `shashki` `WebSocketShiftRepository.kt:76` `replaceFirst(Regex("^http"), "ws")` | constant pattern, per call, one-line fix |
| `bochka` `PostPolicy.kt:264,290`, `Measurements.allocatedBytes` | two constant patterns in the S3 policy path and one in a benchmark helper |
| `proba` `ReposiliteIndex.<init>`, `RepositoryIndex.of` | per instance; both are built once per run |

Three of the eight are constructors rather than method bodies. Bytecode does not carry how long an
instance lives, so those three are a question to the author and not a defect — see §1.6.

### 1.4 Whether the rules name the methods the profile charges

The only service in the portfolio with both a profile and compiled classes is konekt, so the join
was run there: `profile.py join`, which takes the owners out of the profile and the findings out of
the probe and intersects them.

| Rule | at 200 rps | at 50 rps |
|---|---|---|
| chain ≥ 2 | names methods owning **1.75 % of all bytes — 32.4 % of everything user code owns** | 1.01 %, **29.7 %** |
| body > 325 b | 0.13 %, 2.3 % | 0.09 %, 2.7 % |
| pattern per call | 0 %, 0 % | 0 %, 0 % |

The chain rule names `MoneyFormat.group` (1.08 % of all allocated bytes — the largest single user
owner in the profile), `UsageUnits.grouped` (0.55 %) and two Exposed `invokeSuspend` bodies. The
pattern rule names nothing on konekt because konekt builds no pattern per call: its evidence is the
stand's 6.45 % and the eight findings above, not this service.

**The largest owners no rule names**: `PlanDetailScreen.row` (0.58 %) and `MoneyFormat.format`
(0.50 %) — string interpolation, one materialisation each, below the rule's own threshold of two.
That is the honest boundary of the chain rule and it is written here so that nobody re-derives it
as a surprise.

### 1.5 What sborka already has

| Fact | Where verified |
|---|---|
| `kapkanMethodSizes` already prints bodies over `FreqInlineSize`, **and** patterns built outside `<clinit>`, **and** an `Intrinsics.check*` count | `build-logic/settings/src/main/kotlin/io/github/youndie/sborka/settings.settings.gradle.kts` |
| The reader is sborka's own, no ASM: constant pool, class header, method bodies, an instruction walk | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/MethodSizes.kt`, `ConstantPool.kt`, `Bytecode.kt` |
| A pattern build is recognised as `kotlin.text.Regex.<init>` or `java.util.regex.Pattern.compile`, and `<clinit>` is excluded by name | `MethodSizes.isPatternBuild`, `MethodSizes.parse` |
| The task is registered by the **settings** plugin, depends on every `*Classes` task, and is deliberately **not** in `check` | same settings script, `gradle.projectsEvaluated` block |
| The instruction walk refuses to answer rather than answering zero: a body it could not walk is named in the report (`unwalked`) | `MethodSizes.Report.unwalked` |
| kapkan's five ktlint rules are all source-level and none is about performance | `build-logic/kapkan/src/main/kotlin/io/github/youndie/sborka/kapkan/` |

**Consequence.** Two of the three rules already exist as *report lines* and neither exists as a
*rule*: nothing fails, nothing is suppressed, nothing is scoped. The work in the backlog is
therefore mostly not "write a detector" — it is "decide what each finding obliges a person to do",
which is the part the usual checklist skips.

### 1.6 Five mechanics that changed a rule, found while measuring

**A collection chain is invisible in bytecode as a chain.** `filter`, `map`, `groupBy` and their
neighbours are `inline`, so no `CollectionsKt.filter` call survives compilation; what survives is
the materialisation — a fresh `ArrayList`, a `collectionSizeOrDefault`, a loop. The rule therefore
counts materialisations per body, not operator calls, and that is why it is a bytecode rule at all.
Verified against emitted bytecode in [`probe/Control.kt`](probe/Control.kt).

**The string half is where the money was, and the first definition missed it entirely.** The
largest user-code allocation owner in konekt's profile is `MoneyFormat.group`, whose body is
`reversed().chunked(3).joinToString(sep).reversed()` — four intermediates, none of them a
collection operator. A rule watching only `kotlin.collections` named **none** of the methods that
profile charges; widened to `kotlin.text`, it names the top two. The measurement did not confirm
the rule, it corrected it.

**A positive control caught the reader, not the code.** The first run reported bodies of two
billion bytes. `javap` prints the keys of a `tableswitch`/`lookupswitch` in the same shape as an
instruction offset, and a `when` over strings switches on `hashCode`; requiring an opcode after the
colon fixed it. sborka's own reader has the same trap handled in `Bytecode.kt`, which is where it
was recognised.

**A multiplatform build compiles one class into more than one directory**, so a naive walk counts
every finding two or three times: shashki's single `socketUrl` appeared twice. Counts here are of
distinct methods, and the same care will be needed in the task.

**`<clinit>` is excluded, `<init>` is not — and three of eight findings are `<init>`.** A pattern in
a constructor is paid per instance, which is free for a singleton and expensive for a per-request
object. Bytecode does not carry the lifetime, so the rule cannot decide it; the acceptance form in
§2 turns that into a question with a written answer rather than into a false positive.

---

## 2. The three rules

Each rule is stated in the same shape on purpose: the claim, the number that justifies it, how
often it fires here, what it obliges, and what it does **not** claim.

### 2.1 `pattern-built-per-call` — a gate

* **Claim.** A `Regex` or `Pattern.compile` in a method body compiles the pattern on every call; in
  `<clinit>` it is compiled once.
* **The number.** 6.45 % of all allocated bytes on the stand's `/business` — `Matcher`, `int[]`,
  `boolean[]` and the `Pattern` itself, owner `Pricing.quote`
  (`zavarnik/bench/profile/results/baseline/business.alloc.collapsed`). Two thirds of everything
  user code allocated there.
* **How often it fires.** 8 methods in 53 423 (§1.3). It is the cheapest rule in the set by two
  orders of magnitude.
* **Obliges.** Hoist to `<clinit>`, or suppress with a reason. Three of the eight are constructors
  and one is an interpolated pattern that cannot be hoisted — so the reason is the normal outcome,
  not the exception.
* **Does not claim.** That any of the eight is hot. Nothing profiled them; the only profiled
  instance is `Pricing.quote`. The rule claims a shape with a measured price *somewhere*, and a
  cost of eight readings *here*.

### 2.2 `intermediate-containers` — a report first, a gate where it is scoped

* **Claim.** Two or more eager materialisations in one body — `ArrayList`/`LinkedHashMap` from an
  inlined operator, or a call to a non-inline eager operator over a collection **or a string** —
  allocate one container per link of the chain. `asSequence()` removes all but the last.
* **The number.** 2.72–4.20 % of all allocated bytes on the stand depending on how the sample is
  attributed (§1.1), and on konekt the methods it names own **30–32 % of everything user code
  allocates** (§1.4). Both above the 2 % line; the second is the stronger number, because it comes
  from a service nobody wrote for this measurement.
* **How often it fires.** 325 methods in 53 423 — 43 in konekt, 90 in bochka. Readable per
  repository, not readable per portfolio.
* **Obliges.** Nothing yet: it prints. Where a repository names its hot modules (§3 D4), a finding
  there is a gate.
* **Does not claim.** That the two materialisations are the same chain. Bytecode has no dataflow
  here, so two unrelated lists in one body count as two — an upper bound by construction. And it
  misses a single materialisation, which is where konekt's third- and fourth-largest owners are.

### 2.3 `body-over-freq-inline-size` — a report, and it stays one

* **Claim.** A body over `FreqInlineSize` (325 bytes) will not be inlined into a hot caller.
* **The number.** Under load, three of the ten over-threshold methods on the stand were refused by
  C2 with "hot method too big" — measured, and it refuted the argument that `invokeSuspend` is only
  ever a compilation root. On konekt the methods it names own 2.3 % of what user code allocates.
* **How often it fires.** 1 058 methods in 53 423 — 2 % of everything compiled, and the biggest
  are Compose composables.
* **Obliges.** Reading, by a person, in `build/reports/kapkan/method-sizes.txt`.
* **Does not claim.** **A cost.** A refusal to inline is not a measured slowdown: nothing here ran
  the service with those bodies made smaller. That is the entire reason it is not a gate, and it is
  the same reason `kapkanMethodSizes` was kept out of `check` when it was written.

---

## 3. Decisions

### D1. A rule ships only above 2 % of a profile, and the line is a floor, not a target

First idea: the brief's list of shapes worth linting.
Decision: three, and each names the profile line it came from.

Why: user code owns 9.95 % of allocations on a stand written to flatter it (§1.1). A shape worth
1 % of bytes is worth a tenth of a percent of the process, which is below the ±15 % run-to-run
spread of the machine that measured it. Boxing (1.26 %) and lazy logging (≤ 1.57 %) were dropped by
this rule, and both are in every checklist ever written.

The price: the set is small enough to look arbitrary, and a shape that is expensive in someone
else's service is absent. That is the intended trade — the number, not the intuition, is what a
new rule has to bring.

### D2. The rules read bytecode, not source *(deviation from the obvious)*

First idea: kapkan is a ktlint rule set, so a lint rule belongs in kapkan.
Decision: these three read compiled output; kapkan keeps the five source-level rules.

Why: the source does not carry the numbers. `inline` functions are copied into their callers, a
`suspend` function becomes a state machine, and `Pricing.quote` reads as a dozen lines and compiles
to 1827 bytes. For the chain rule the reason is sharper still: at source level the chain is
`.filter { }.map { }`, and telling a `List` chain from a `Flow` or `Sequence` chain — the fix
itself — needs type resolution, which is what sank kapkan's first rule (`docs/kapkan.md` §2.1). In
bytecode the two are different call targets, and no types are needed.

The price: a report over compiled output needs a compilation, so it cannot run in an editor, and
`kapkanMethodSizes` has to depend on every `*Classes` task.

### D3. Gate or report is decided by the finding count, per rule

Decision: 8 findings — a gate. 325 — a report, and a gate only inside modules a repository names.
1 058 — a report, permanently.

Why: a rule that fires 1 058 times is a counter; nobody reads a counter and the build that fails on
one gets an exemption within a week. This is the same ratio argument that keeps `MaxInlineSize`
(35 bytes) out of the report entirely — at 35 bytes almost every method qualifies.

### D4. "Hot" is declared by a person, not inferred *(open — see §4)*

Decision for now: the scope is a module list a repository declares, `sborka.perflint.hot=:server`;
absent, everything is a report and nothing is a gate.

Why: nothing static knows what is hot. Reachability from a Ktor route is computable with the reader
that already exists (`Joins` resolves calls through the hierarchy), and it is still not hotness —
konekt's own OpenAPI document builder is reachable from a route and runs once. A person naming two
modules is one line of configuration and is right for the reason that matters: they know which
process runs under load.

The price: a default of "nothing is hot" means a repository that never sets it gets reports only —
so the rules cannot rot a build, and they also cannot help anybody who does not opt in.

### D5. The chain rule watches strings as well as collections *(correction, not a widening)*

The brief said "intermediate collections in a hot path". Watching only collections named none of
the methods konekt's profile charges (§1.6); watching `kotlin.text` too named the top two, and cost
124 extra findings portfolio-wide (201 → 325 distinct methods). The title of the rule follows
the measurement.

### D6. The probe is committed, and it is not the implementation

`probe/` holds the two scripts that produced every number in §1 plus the control fixture. It is
committed because a number whose derivation is not re-runnable is a number nobody can challenge —
and `javap` output is not what the task will read (it will use sborka's own `MethodSizes`). Both
readers answering the same question is the arrangement that caught the switch-key defect.

---

## 4. Risks and open questions

**Risk 1 — no rule here has a measured *saving*, only a measured *share*.** The profile says these
methods own the bytes; nobody has run the service with them fixed. Mitigation: `B-05` fixes one
named finding on konekt — `MoneyFormat.group`, 1.08 % of all bytes — and measures alloc/req A/B
under the same harness, alternating variants with medians, because one run per variant is not a
measurement. Until that lands, every rule's claim is "the profile charges this shape", which is
weaker than it will read to somebody skimming.

**Risk 2 — the 6.45 % came from a stand, and a stand can be written for its answer.** Mitigation is
already partly done: konekt is a service written before any of this, and it puts user code at
3.41–5.41 % of allocations with zero pattern findings. So the pattern rule's share number does not
generalise; its *cost* number (eight findings) does. Stated in §2.1 rather than left to be found.

**Risk 3 — the chain rule's upper bound becomes a habit of suppression.** 325 findings with no
dataflow behind them is exactly the shape that teaches people to write `@Suppress` without reading.
Mitigation: the acceptance form of `docs/kapkan.md` §2.3 — a rule with no "zero findings on healthy
code" state is accepted only if every finding's reason reads as a table, and rejected if one reason
reads "it has to be this way".

**Open question 1 — does the chain rule survive contact with a Compose client?** Everything above is
server-shaped. metrik and shashki are Compose repositories and hold 45 of the 325 findings; nobody
has looked at whether a recomposing body allocating two containers is a defect or the framework.
Settled in `B-02` by reading those 45.

**Open question 2 — the interpolated pattern.** `katcher`'s `Regex("$key=…")` is the one finding
whose fix is neither a hoist nor a suppression. Hypothesis: a small `Regex` cache keyed by the
interpolated string is the wrong answer and parsing the header without a pattern is the right one.
Settled when somebody fixes it, in `B-06`.

---

## 5. What happens next

The order of work and the acceptance criteria are in [backlog.md](../../backlog.md). The first two
items are the ones everything else rests on: teach the existing class-file walk the chain question
(`B-01`), and give a repository a way to say which modules are hot (`B-02`), because until then
every rule is a report and "gate" is a word in a document.

## Code anchors

| Module | Code |
|---|---|
| core | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/MethodSizes.kt` — the reader, the thresholds, the pattern question |
| core | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/Bytecode.kt` — the instruction walk that answers "which calls" |
| settings | `build-logic/settings/src/main/kotlin/io/github/youndie/sborka/settings.settings.gradle.kts` — where `kapkanMethodSizes` is registered and what it prints |
| kapkan | `build-logic/kapkan/src/main/kotlin/io/github/youndie/sborka/kapkan/` — the five source-level rules, for contrast |
| probe | `docs/research/probe/perfprobe.py`, `docs/research/probe/profile.py`, `docs/research/probe/Control.kt` |
| zavarnik | `bench/profile/results/baseline/business.alloc.collapsed` — the profile behind 6.45 % |
| zavarnik | `docs/research/research-optimizer.md` — the phase that measured the ceiling and closed |
