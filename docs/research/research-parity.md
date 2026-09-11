---
id: research-parity
title: One contract, two binaries — where the JVM and Kotlin/Native halves disagree
type: research
status: active
date: 2026-09-11
---

# Research: the JVM/native parity gate

Five of this portfolio's repositories compile one Kotlin source into two runtimes, develop against
the fast one and ship the slow-to-build one. That trade rests on the two behaving the same, and
until this document that was a belief with nothing behind it. The brief that asked for the
investigation is [source-brief-parity.md](source-brief-parity.md); this is what came back.

The short version is that the belief is mostly right and wrong in a way nobody would have guessed.
A probe of 129 behaviours across the standard library, the regex engine, kotlinx-serialization and
kotlinx-datetime found **17 disagreements**, and not one of them is in the two places the brief
predicted them. Number formatting, string case mapping, hashing and seeded randomness agree
**exactly**, row for row. What disagrees is hash-map iteration order, the text and sometimes the
class of exceptions, and a single regex construct. Meanwhile the four divergences this portfolio
has actually been bitten by are in none of those categories: they are platform APIs — a socket that
does not resolve a hostname, a TLS stack that is not there, a Ktor plugin that is not published for
native, a dispatcher that is `internal` — and a stdlib-level parity gate would have caught none of
them.

This document records **verified facts** (measured here, or read in the files named beside them),
**decisions**, **deviations from the brief**, and **hypotheses that say where they get settled**.
The probe is committed in [`parity-probe/`](parity-probe/); §1.2 says why.

---

## 1. Verified facts

### 1.1 Which repositories actually ship two binaries from one source

The brief names seven subjects. Read out of the build files, five qualify, and two of the seven do
not have the property the brief is about.

| Repository | Module | `jvm()` | Native targets | Verified in |
|---|---|---|---|---|
| shildik | `:server` | yes | `macosArm64`, `linuxX64` | `shildik/server/build.gradle.kts` |
| tracy | `:server` | yes | `macosArm64`, `linuxX64`, `linuxArm64` | `tracy/server/build.gradle.kts` |
| metrik | `:server`, `:agent` | yes | `macosArm64`, `linuxX64`, `linuxArm64` | `metrik/server/build.gradle.kts` |
| katcher | `:server` | yes | **one, chosen by the build host** and named `native` — `macosArm64`, `macosX64`, `linuxX64`, `linuxArm64` or `mingwX64` | `katcher/server/build.gradle.kts` |
| razves | `:cli`, `:core` | yes | `linuxX64`, `macosArm64` | `razves/cli/build.gradle.kts` |
| **telek** | — | **no** | `linuxX64`, `linuxArm64` | `grep -r 'jvm('` over the repository's build files returns **nothing**; six modules declare the two Linux targets |
| **booblik** | — | **split** | — | the broker is `kotlin("jvm")` (`booblik/booblik-app/build.gradle.kts`); `booblik-native` is a **client**, not the same program |

**Consequence — telek is not a subject and cannot be made one cheaply.** It has no JVM target
anywhere, so there is no second binary to compare against. Parity is not a question one can ask of
it; adding `jvm()` to answer the question would be adding a target nobody ships in order to test a
target nobody ships.

**Consequence — booblik is a different question wearing the same word.** Its JVM broker and its
Kotlin/Native client are two programs that speak one protocol, which is what
`booblik/booblik-conformance` and
`booblik/booblik-native-conformance` already test. That is
protocol conformance across implementations, and it is well covered. Brief B is about one source
producing two behaviours, and booblik has no module with that shape.

**Consequence — katcher's native half is whichever machine compiled it.** `:server` resolves one
native target from `os.name` and `os.arch`, so a parity run on a Mac compares the JVM against a
macOS binary and never against the Linux one that ships. For the other four that is a caveat about
the timezone table; for katcher it is the whole native side of the comparison. Any gate on katcher
has to run where the binary is built.

**Consequence — razves is the only subject where the brief's gate is buildable today.** See §1.5:
it is a CLI, so "run it and diff the output" needs a process and a command line, not a service, a
database and a stand.

### 1.2 What the two runtimes actually do differently

Measured on 2026-09-11 by [`parity-probe/`](parity-probe/) — one `commonTest` source set, 129
probes, run on `jvm` and on `macosArm64`, each probe printing one row of a transcript. Kotlin
2.4.10, kotlinx-serialization 1.11.0, kotlinx-datetime 0.8.0, all three read out of
[`catalog/sborka.versions.toml`](../../catalog/sborka.versions.toml) rather than picked: a
divergence on a version nobody ships is a divergence nobody has.

Nothing is asserted in the probe. An assertion would encode which target is right, and that is the
question rather than the setup; [`compare.py`](parity-probe/compare.py) prints both values and
names neither a reference.

```
129 rows compared, 17 differ, 0 present on one side only
```

| Class | Rows | Differ | What differs |
|---|---|---|---|
| number formatting | 17 | **0** | nothing — `0.1+0.2`, `1e23`, `1e-5`, `-0.0`, `Double.MIN_VALUE`, `1f/3f`, radix conversion |
| strings and Unicode | 19 | **0** | nothing — `"straße".uppercase()`, the `ﬁ` ligature, dotted `İ`, the `ǅ` digraph, surrogate pairs, `sorted()`, `trim()` on NBSP and ideographic space |
| hashing | 10 | **0** | nothing — `String.hashCode` including non-BMP text, data-class, list, map, `Double`, `Long`, `Char` |
| seeded randomness | 3 | **0** | nothing — `Random(42)` sequence, `nextDouble`, `shuffled` |
| regex | 22 | 2 | `\b` before a non-ASCII letter; the text of a bad-pattern exception |
| serialization | 19 | 1 | key order of a map built as a `HashMap` — and nothing else |
| number parsing | 11 | 2 | the message of `NumberFormatException`, twice |
| datetime | 12 | 3 | the size of the zone table and two message texts; **every offset agreed** |
| exceptions | 10 | 6 | messages, and in one case the class |
| collections | 6 | 3 | `HashMap` / `HashSet` iteration order |

The rows themselves, verbatim from
[`parity-probe/results/2026-09-11-jvm.tsv`](parity-probe/results/2026-09-11-jvm.tsv) and
[`…-macosArm64.tsv`](parity-probe/results/2026-09-11-macosArm64.tsv):

| Probe | JVM | Kotlin/Native |
|---|---|---|
| `collections/hashmap-iteration-order` | `epsilon,zeta,eta,alpha,delta,theta,beta,gamma` | `alpha,beta,gamma,delta,epsilon,zeta,eta,theta` |
| `collections/hashmap-int-keys` | `49,35,84,21,70,7,56,42,28,77,14,63` | `7,14,21,28,35,42,49,56,63,70,77,84` |
| `serialization/map-from-hashmap` | `{"a":1,"b":3,"z":0,"m":2}` | `{"z":0,"a":1,"m":2,"b":3}` |
| `regex/word-boundary-unicode` — `Regex("\\bé").find("é")` | `null` | `é` |
| `exception/string-index-out-of-bounds` | `StringIndexOutOfBoundsException: Index 7 out of bounds for length 3` | `ArrayIndexOutOfBoundsException: null` |
| `exception/integer-division-by-zero` | `ArithmeticException: / by zero` | `ArithmeticException: null` |
| `exception/cast-failure` | `class java.lang.String cannot be cast to class java.lang.Integer …` | `class kotlin.String cannot be cast to class kotlin.Int` |
| `number-parse/int-overflow-message` | `NumberFormatException: For input string: "2147483648"` | `NumberFormatException: null` |
| `datetime/available-zone-count` | `604` | `597` |

**Consequence — the catalogue in the brief is mostly a list of things that agree.** Seven of the
eleven classes it names were measured and came back identical. Publishing the *negative* result is
the point: a portfolio that believes `Double.toString` might differ writes defensive formatting
code forever, and 17 rows of evidence retire that belief in one commit. This is the finding worth
putting on kotlin.website, more than the disagreements are.

**Consequence — `HashMap` order is the one divergence with an ordinary, silent consequence.** The
other sixteen show up as a message a human reads. This one shows up as a different byte sequence in
a payload, and §1.3 finds it in shipped code.

**Consequence — an exception's class is not only its message.** `"abc".substring(2, 1)` and
`"abc"[7]` raise `StringIndexOutOfBoundsException` on the JVM and `ArrayIndexOutOfBoundsException`
on native. Both are `IndexOutOfBoundsException` subclasses, so a `catch` on the base class behaves
the same and a `catch` on the JVM class compiles only on the JVM — but a `when (e)` chain written in
common code against the broader type will take a different branch. No occurrence was found in this
portfolio; it is recorded because it is the one row that changes control flow rather than text.

**Caveat that has to travel with these numbers: the native half was `macosArm64`, not `linuxX64`.**
Kotlin/Native tests are not cross-run, and the machine this probe was written on is a Mac. For the
stdlib, the regex engine and kotlinx-serialization that is a distinction without a difference —
same runtime, same sources. For `datetime/available-zone-count` it is exactly the difference:
kotlinx-datetime reads the host's zone database, so 597 is macOS's number and Linux will give
another. That row is a hypothesis until H1 closes.

### 1.3 Where a differing row reaches shipped code

Two places in the portfolio put a `HashMap` between the program and the wire. One of them was
already immune, and the contrast is the useful part.

| Fact | Where verified |
|---|---|
| `WindowAggregator.drain()` builds the `routes` list by iterating a `HashMap<SeriesKey, SeriesAccumulator>` with no ordering step | `metrik/agent/src/commonMain/kotlin/io/github/youndie/metrik/agent/WindowAggregator.kt` |
| that module publishes for `jvm`, `linuxX64`, `linuxArm64` and `macosArm64`, so both orders are shipped | `metrik/agent/build.gradle.kts` |
| the test that covers it reads results with `.single()` and `.single { … }` — order-insensitive, and green on both targets | `metrik/agent/src/commonTest/kotlin/io/github/youndie/metrik/agent/WindowAggregatorTest.kt` |
| `Histogram.toSparse()` sorts its bucket keys before serialising, so the same `HashMap` reaches the wire in a target-independent order | `metrik/shared/src/commonMain/kotlin/io/github/youndie/metrik/wire/Histogram.kt` |

**Consequence.** A metrik window payload sent by a JVM-hosted agent and by a native one lists the
same series in a different order. Nothing in metrik depends on that order today — the server merges
by key — so this is not a defect being reported, it is the shape the gate exists to notice *before*
something starts depending on it: a golden file, a payload hash, an idempotency key, a diff-based
test. The sorted `Histogram` next door shows the fix costs one `.sorted()`.

**Consequence — the existing test proves the brief's premise rather than disproving it.** It passes
on both targets *because* it was written not to look. That is the failure mode the brief describes,
sitting in the repository, green.

### 1.4 The divergences this portfolio has actually paid for, and where they were

None of them is in the standard library.

| Divergence | Cost | Where recorded |
|---|---|---|
| `InetSocketAddress(host, port)` does not resolve a hostname on Kotlin/Native — `connect` fails with `EINVAL`; on the JVM the same code works | every native agent reporting through a Kubernetes service name was silent **from its first day**, and the monitoring rule for "no data" reported a healthy service as dead | metrik `docs/research/research-architecture.md` §1.6 |
| `ktor-client-cio` has no TLS on native — `IllegalStateException: TLS sessions are not supported` | Telegram notifications never left the server; fixed with `ktor-client-curl` on native and `ktor-client-cio` on the JVM, through `expect/actual` | metrik `docs/research/research-architecture.md` §1.7; the split is visible in `metrik/server/build.gradle.kts` |
| `ktor-server-compression` is published for the JVM only | compression moved into the image build as pre-made `.gz` files | metrik `docs/research/research-architecture.md` §1.8 |
| `Dispatchers.IO` is `internal` on Kotlin/Native in coroutines 1.11.0 — "checked by compiling, not read in the documentation, which says otherwise" | the producer owns a thread through `newSingleThreadContext` instead of offloading | comment in `booblik/booblik-native/build.gradle.kts` |

**Consequence — the gate has to be able to see the platform layer, and the stdlib probe cannot.**
Every entry above is a library or a system call behaving differently, not a language primitive. A
`parityCheck` that only diffs pure-function output would have been green through all four.

**Consequence — and the metrik §1.6 story says exactly how to see them.** Quoting its own
post-mortem: the tests were green because the plugin test substitutes a fake sender, *so a real
socket was never opened once*. The divergence was reachable only by a check that runs the real
transport. That is an argument for the gate being end-to-end — and an argument against believing a
green `parityCheck` that runs in-process.

### 1.5 What runs on which target today

Files named `*Test.kt`, counted per source set on 2026-09-11. A test in `commonTest` runs on every
declared target; a test in `nativeTest` never runs on the JVM, and vice versa.

| Module | `commonTest` | `jvmTest` | `nativeTest` | `linuxX64Test` |
|---|---|---|---|---|
| shildik `:server` | 4 | 0 | 0 | 0 |
| shildik `:core` | 8 | 0 | 0 | 0 |
| tracy `:server` | 22 | 0 | 0 | 1 |
| tracy `:agent` | 9 | 0 | 0 | 1 |
| tracy `:shared` | 6 | 0 | 0 | 0 |
| metrik `:server` | **0** | 0 | **8** | 0 |
| metrik `:agent` | 3 | 1 | 0 | 0 |
| metrik `:shared` | 6 | 0 | 0 | 0 |
| katcher `:server` | 10 | 0 | **11** | 0 |
| razves (whole repo) | 15 | 3 | 0 | 0 |

**Consequence — metrik's server has no JVM test coverage at all.** Its eight route, ingest and
query tests live in `nativeTest`; the `jvm()` target compiles the production code and runs nothing
against it. For that module "develop on the JVM, ship native" is already not what happens, and a
parity gate that assumed both halves ran the same suite would be comparing a suite against an empty
set. katcher is the same shape with eleven tests.

**What is *not* established:** why. `openDatabase` — the one thing those tests need that looked
platform-specific — is in `commonMain` on both
(`metrik/server/src/commonMain/kotlin/io/github/youndie/metrik/server/Application.kt`,
`tracy/server/src/commonMain/kotlin/io/github/youndie/tracy/server/Application.kt`),
so nothing in the type system forced the choice, and nothing in either repository records it. It is
H2, not a finding.

**Consequence — tracy is the reference subject.** 37 of its 39 tests are in `commonTest` and its CI
runs them on `jvm`, `linuxX64` and `macosArm64` on every pull request. Whatever the gate turns out
to be, tracy is where it is cheapest to prove and where a red result means the most.

### 1.6 What a pull-request build already costs

Wall time of the Gradle job on the most recent successful `main` run of each repository,
2026-09-09, `ubuntu-latest`, read from `gh run view --json jobs`.

| Repository | Job | Wall time |
|---|---|---|
| shildik | `check` | 4 m 22 s |
| tracy | `build` | 4 m 56 s |
| katcher | `build` | 7 m 29 s |
| metrik | `build` | 10 m 03 s |

And what those minutes already contain — task list from the log of tracy's run `34400536457`:

| Fact | Where verified |
|---|---|
| `./gradlew build` runs `:server:linkReleaseExecutableLinuxX64`, `…LinuxArm64` **and** `…MacosArm64` | `gh run view 34400536457 -R youndie/tracy --log` |
| the same run executes `:server:jvmTest`, `:server:linuxX64Test` and `:server:macosArm64Test` | same log |
| metrik's run does the same for `:server`, `:agent` and `:shared` | `gh run view 34391036889 -R youndie/metrik --log` |
| all five subject repositories are public, so standard GitHub runners cost nothing | `gh repo view --json visibility`, five repositories, all `PUBLIC` |

**Consequence — RQ4's question has the wrong subject.** The brief budgets ten minutes for
`parityCheck` on the assumption that the native link is what it buys. The native release link for
every declared target is **already inside the existing PR build**, and so is running the test suite
on both. The marginal cost of a parity gate is not a compile; it is capturing two transcripts and
diffing them, which is seconds. The ten-minute question answers itself and the real budget question
is metrik's ten minutes, which is a separate problem from this brief.

---

## 2. Decisions

### D1. The gate diffs two transcripts of the same suite, not two running services *(deviation from the brief)*

Brief: "`parityCheck`: builds both targets, **starts each** against the same stand, runs the same
suite, applies the normaliser, and fails on any diff".

Decision: for the four services, the gate runs one `commonTest` suite on both targets, has each run
write a normalised transcript of what it observed, and diffs the transcripts.

Why:

- **There is no JVM service to start.** No subject's server module applies the `application`
  plugin, declares a `mainClass`, or produces an installable distribution; the only `application`
  in any of them is shildik's `:distribution`, and that module declares **`linuxX64` only**
  (`shildik/distribution/build.gradle.kts`). The `jvm()` targets exist so common tests run in
  seconds, not so a JVM process ships.
- Building one would be a real piece of work — an entry point, a runtime classpath, a JVM image or
  a start script, per repository — and it would exist only to be tested. That is not a three-day
  box, and it puts an artefact nobody ships between the question and the answer.
- `ktor-server-test-host` is already a `commonTest` dependency in tracy and metrik and already runs
  on `linuxX64` in CI, so the HTTP surface is reachable in-process **on both targets** with no new
  machinery.
- The price, stated plainly: the in-process route does not exercise the real socket, the real DNS
  resolver or the real TLS stack — which is exactly where all four of §1.4's divergences lived.
  This gate would not have caught any of them. That is not a reason to skip it; it is a reason not
  to let it be mistaken for coverage it does not provide, and it is why Risk 1 in §5 exists.

### D2. razves keeps the brief's design, because it can

razves is a CLI with `jvm()` and two native executables. "Run the binary, capture stdout, diff" is
one script, needs no stand, and gives the portfolio one instance of the process-level gate the
brief actually asked for. Where the two designs disagree, razves is the one that tells us what the
in-process version is missing.

### D3. Neither target is the reference

The JVM is older, better documented and wrong more often than a reader expects — `\bé` matching
nothing is the JVM's answer, not native's. `compare.py` prints `left` and `right` and refuses to
label either. A divergence is resolved by deciding what the *contract* says, which is sometimes
"the JVM", sometimes "native", and sometimes "neither, both get a `.sorted()`".

### D4. The probe is committed, and it is not the gate

[`parity-probe/`](parity-probe/) is a standalone Gradle build under `docs/research/`, deliberately
outside sborka's own build: applying `sborka.kmp` to it would put this repository's explicit-API
rule, ktlint pass and test gate between the question and the answer. It is re-runnable on a version
bump, which is the whole point — the 17 rows are true of Kotlin 2.4.10 and of nothing else, and the
next bump is when anyone will want to know whether they still are.

Same reasoning as the perf-lint probe in [`probe/`](probe/), and the same benefit: two readers
answering one question is what catches a defect in the first reader.

### D5. The allowlist is per-row, not per-class

`parity-allowlist.yaml`, one entry per differing transcript row: the probe id, the class from §1.2,
the reason, and the value each side produces. A class-level entry ("exception messages may differ")
would silence the row that changes an exception's *class* along with the sixteen that only change
text. The allowlist is the platform-differences page in machine form, and a page that says
"messages differ" teaches nobody anything.

---

## 3. Deviations from the brief, collected

Each of these is a place where the brief asked for something the code does not support. They are
gathered here rather than scattered because this list, not the RQ table, is what the next person
needs before they start.

1. **Seven subjects, five.** telek has no JVM target; booblik's two halves are two programs (§1.1).
2. **"Starts each against the same stand" is not buildable today** — no JVM runnable exists for any
   of the four services (D1).
3. **"Under 10 minutes with cache" is not the question.** The native link is already in the PR
   build; the marginal cost of the gate is a diff (§1.6).
4. **The predicted divergences were wrong in both directions.** The brief predicted 2–5, with
   "key order in JSON produced from a map and a regex with a character class or a look-around"
   first. Key order: confirmed, and it is the only serialization row that moved. Regex character
   classes and look-arounds: `\w` on accented text, `\d` on Arabic-Indic digits, `\p{L}`,
   `\p{IsCyrillic}`, `[[:alpha:]]`, fixed and variable look-behind, named groups, back-references,
   possessive quantifiers and atomic groups **all agreed**. Total: 17, mostly in classes the brief
   did not rank.
5. **"Both binaries pass their unit tests" understates it for metrik and katcher** — their server
   suites do not run on the JVM at all (§1.5).
6. **The brief's non-goal "performance parity" is the right call and its reason is now stronger.**
   The measurable behavioural surface turned out to be small and mostly identical; the interesting
   risk moved to the platform layer, which is a behaviour question, not a speed one.

---

## 4. Hypotheses, each with the milestone that settles it

**H1 — the `macosArm64` results carry over to `linuxX64` except for the zone table.** Settled by
re-running [`parity-probe/run.sh`](parity-probe/run.sh) on the Linux box and diffing the two native
transcripts against each other. Prediction: 128 of 129 rows identical, `datetime/available-zone-count`
differs. If more than one row moves, every number in §1.2 is about macOS and says so.

**H2 — metrik's and katcher's server tests are in `nativeTest` by habit, not by necessity.**
Everything they call is in `commonMain` (§1.5). Settled by moving one file to `commonTest` and
running `jvmTest`. If it compiles and passes, the parity gate for those two repositories is mostly
a `git mv`; if it does not, the reason it does not is the most interesting fact in this document.

**H3 — the four platform-layer divergences of §1.4 are a closed set for the libraries this
portfolio pins.** Settled by the first `parityCheck` run that exercises a real socket. Prediction:
it is not closed, and the next one is in file-system or process APIs.

**H4 — no shipped payload in the portfolio depends on `HashMap` order except metrik's `routes`.**
Settled by the gate itself, on the first run over all five subjects. §1.3 searched by hand and found
one; a hand search over five repositories is a hypothesis, not a fact.

---

## 5. Risks, with the machinery that mitigates them

**Risk 1 — the gate is mistaken for coverage of the platform layer.** An in-process `parityCheck`
that is green says the stdlib agrees, and §1.4 is four cases where the stdlib agreed and production
was broken for months. Mitigation: the task's own output names what it did not test, in the same
line as the result — "42 rows compared on jvm and linuxX64; no socket, DNS or TLS path was
exercised". A gate that reports its own blind spot is the only kind that does not accumulate
misplaced trust.

**Risk 2 — the allowlist becomes the place differences go to be forgotten.** Sixteen of the
seventeen rows are message text, which is exactly the kind of entry that gets allowlisted on sight.
Mitigation: an entry carries the two values, so a row whose *values* change still fails even though
the row is allowlisted — the allowlist pins the known difference rather than muting the probe.

**Risk 3 — a normaliser that is written to make the build green.** Timestamps and generated ids have
to be normalised; the same mechanism can normalise away a real difference, and nothing distinguishes
the two from inside. Mitigation: the normaliser is a declared list of field names, not a regex over
the payload, and adding to it is a diff a reviewer sees.

**Risk 4 — the probe's own numbers rot at the next Kotlin bump.** 17 of 129 is a property of 2.4.10.
Mitigation: `run.sh` and `compare.py` are committed and take one command each; re-running is cheaper
than reading this document. **The numbers in §1.2 name their date and their versions for that
reason** — a table that does not is a table nobody can re-check.

---

## 6. Code anchors

| What | Where |
|---|---|
| the probe, its runner and its comparison tool | [`docs/research/parity-probe/`](parity-probe/) |
| the two transcripts these numbers come from | [`docs/research/parity-probe/results/`](parity-probe/results/) |
| the versions every measurement was taken at | [`catalog/sborka.versions.toml`](../../catalog/sborka.versions.toml) |
| where the gate would be wired, as `sborka.kmp` wires the test gate today | [`build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts`](../../build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts) |
| the reference subject | `tracy/server` |
| the only shipped payload found to depend on hash order | `metrik/agent/src/commonMain/kotlin/io/github/youndie/metrik/agent/WindowAggregator.kt` |
| the same shape, already immune | `metrik/shared/src/commonMain/kotlin/io/github/youndie/metrik/wire/Histogram.kt` |
| the platform-layer divergences, in the repository that paid for them | `metrik/docs/research/research-architecture.md` §§1.6–1.8 |

---

## 7. What happens next

The order is in [backlog.md](../../backlog.md), stages `stage-4-parity-evidence` and
`stage-5-parity-gate`. The first two items are the ones everything else waits on: H1, because every
number here is provisional until the probe has run on Linux, and H2, because if metrik's and
katcher's suites cannot move to `commonTest` then the gate has nothing to compare for two of the
five subjects.
