---
id: research-parity
title: One contract, two binaries — and the gate belongs at the platform layer, not the stdlib
type: research
status: active
date: 2026-09-11
---

# Research: the JVM/native parity gate

Five of this portfolio's repositories compile one Kotlin source into two runtimes. The pattern they
are supposed to be following is "develop on the JVM, ship native"; what the build files actually
say is **"test on the JVM — and not everywhere"**, which is a different and worse thing. The brief
that asked the question is [source-brief-parity.md](source-brief-parity.md).

The finding is not the measured behaviour of the two standard libraries. That was measured, it is
in §1.2, and it is close to a non-event: 129 probes, 17 rows apart, and of those seventeen not one
has ever cost this portfolio anything. **The three divergences that did cost something are all in
the platform layer** — a socket that does not resolve a hostname, a TLS stack that is not there, a
Ktor plugin published for one target only — and no amount of stdlib diffing sees any of them. A
fourth case, long recorded as a divergence, turns out not to be one at all: it was a compiler
message read as a fact (§1.5), and a probe that merely compiled the line would have said so.

So the gate this brief asked for changes shape. Not a diff of stdlib transcripts, which is a
*version-bump probe* and is already written; but a **platform probe** — a dozen tests in
`commonTest` that open a real socket to a hostname, make a real TLS request, and assert that every
pinned Ktor plugin resolves on the target being built. They run on every target in the
pull-request build that already exists, in seconds, and they are exactly what would have caught all
three real cases.

This document records **verified facts** (measured here, or read in the files named beside them),
**decisions**, **deviations from the brief**, and **hypotheses that say where they get settled**.
The probe is committed in [`parity-probe/`](parity-probe/); §2 D4 says why.

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
`booblik/booblik-conformance` and `booblik/booblik-native-conformance` already test. That is
protocol conformance across implementations, and it is well covered. Brief B is about one source
producing two behaviours, and booblik has no module with that shape.

**Consequence — katcher's native half is whichever machine compiled it.** `:server` resolves one
native target from `os.name` and `os.arch`, so a parity run on a Mac compares the JVM against a
macOS binary and never against the Linux one that ships. Any gate on katcher has to run where the
binary is built.

**Consequence — razves is the only subject where a process-level gate is buildable today** (D2).
It is a CLI, so "run it and diff the output" needs a command line, not a service, a database and a
stand.

### 1.2 What the two standard libraries do differently — the least important section here

Measured on 2026-09-11 by [`parity-probe/`](parity-probe/): one `commonTest` source set, 129
probes, each printing one row of a transcript. Kotlin 2.4.10, kotlinx-serialization 1.11.0,
kotlinx-datetime 0.8.0, read out of [`catalog/sborka.versions.toml`](../../catalog/sborka.versions.toml)
rather than picked. Nothing is asserted in the probe: an assertion would encode which target is
right, and that is the question rather than the setup.

Four transcripts, because two variables turned out to matter besides the target:

| Comparison | Rows apart | Transcripts |
|---|---|---|
| JVM (JDK 25.0.2) vs `macosArm64` | **17** | [`…-jvm.tsv`](parity-probe/results/2026-09-11-jvm.tsv), [`…-macosArm64.tsv`](parity-probe/results/2026-09-11-macosArm64.tsv) |
| JVM (JDK 25.0.4) vs `linuxX64`, *the pair that ships* | **17** — the same rows | [`…-linuxX64.tsv`](parity-probe/results/2026-09-11-linuxX64.tsv) |
| JVM (JDK **17**.0.20.1) vs `macosArm64` | **18** — and not the same eighteen | [`…-jvm-jdk17.tsv`](parity-probe/results/2026-09-11-jvm-jdk17.tsv) |
| `macosArm64` vs `linuxX64` | **1** | the two native transcripts |
| JVM on macOS vs JVM on Linux, same JDK line | **0** | — |

By class, for the pair that ships:

| Class | Rows | Differ | What differs |
|---|---|---|---|
| number formatting | 17 | 0 *(1 on JDK 17)* | `1e23` — and only because the JDK changed, see below |
| strings and Unicode | 19 | **0** | nothing — `"straße".uppercase()`, the `ﬁ` ligature, dotted `İ`, the `ǅ` digraph, surrogate pairs, `sorted()`, `trim()` on NBSP and ideographic space |
| hashing | 10 | **0** | nothing — `String.hashCode` including non-BMP text, data-class, list, map, `Double`, `Long`, `Char` |
| seeded randomness | 3 | **0** | nothing — `Random(42)` sequence, `nextDouble`, `shuffled` |
| regex | 22 | 2 *(1 on JDK 17)* | the text of a bad-pattern exception; `\b` before a non-ASCII letter — a JDK row, see below |
| serialization | 19 | 1 *(2 on JDK 17)* | key order of a map built as a `HashMap` |
| number parsing | 11 | 2 | the message of `NumberFormatException`, twice |
| datetime | 12 | 3 | the size of the zone table and two message texts; **every offset agreed** |
| exceptions | 10 | 6 | messages, and in one case the class |
| collections | 6 | 3 | `HashMap` / `HashSet` iteration order |

The rows that matter, verbatim:

| Probe | JVM | Kotlin/Native |
|---|---|---|
| `collections/hashmap-iteration-order` | `epsilon,zeta,eta,alpha,delta,theta,beta,gamma` | `alpha,beta,gamma,delta,epsilon,zeta,eta,theta` |
| `collections/hashmap-int-keys` | `49,35,84,21,70,7,56,42,28,77,14,63` | `7,14,21,28,35,42,49,56,63,70,77,84` |
| `serialization/map-from-hashmap` | `{"a":1,"b":3,"z":0,"m":2}` | `{"z":0,"a":1,"m":2,"b":3}` |
| `exception/string-index-out-of-bounds` | `StringIndexOutOfBoundsException: Index 7 …` | `ArrayIndexOutOfBoundsException: null` |
| `exception/integer-division-by-zero` | `ArithmeticException: / by zero` | `ArithmeticException: null` |
| `datetime/available-zone-count` | `604` | `597` on macOS, `496` on Linux |

**Consequence — `HashMap` order is the only row here with a silent consequence.** Every other one
surfaces as a message a human reads, or as an exception class (`"abc"[7]` raises
`StringIndexOutOfBoundsException` on the JVM and `ArrayIndexOutOfBoundsException` on native; both
are `IndexOutOfBoundsException`, so a `catch` on the base type is unaffected, but a `when (e)` chain
in common code takes a different branch). Hash order shows up as different bytes in a payload, and
§1.4 finds it in shipped code.

**Consequence — "the JVM does X" is a claim about a JDK.** Running the same probe on JDK 17 moves
five rows, and two of the movements change what this document would otherwise assert:

- `regex/word-boundary-unicode` — `Regex("\bé").find("é")` returns `null` on JDK 25 and **`é` on
  JDK 17, which is what Kotlin/Native returns**. This is JDK-19 behaviour (JDK-8264160 aligned `\b`
  with the ASCII `\w` it is defined against), not a Kotlin/Native divergence at all. On a JDK 17
  service the two targets agree here.
- `number-format/1e23` and `serialization/double-1e23` print `9.999999999999999E22` on JDK 17 and
  `1.0E23` on JDK 25 — the shortest-representation fix that also landed in JDK 19. Native prints
  `1.0E23`, so **on JDK 17 there are two double-formatting divergences that do not exist on JDK 25.**
- two `StringIndexOutOfBoundsException` messages were reworded between the two JDKs as well.

Every transcript therefore carries a header naming its runtime, its JDK and its host, and
`compare.py` skips those lines rather than reporting the setup as a finding.

**Correction found while building the probe — one of its own rows was measuring the compiler.**
Kotlin folds `toString()` on a constant primitive expression at compile time, so `1e23.toString()`
written against a literal was evaluated by the *compiler's* JVM and baked into the class file. The
row recorded the build machine, not the target. It was caught because
`serialization/double-1e23` moved between the two JDKs and `number-format/1e23`, which is the same
value through the same formatter, did not. Every literal feeding a formatting or parsing probe now
goes through a non-inline `opaque(…)`, and after the fix the row moves with the JDK as it should.
The lesson is the probe's, not the platform's: **a measurement of a runtime has to be forced
through the runtime.**

**Consequence — the negative result is the publishable half, and it is now a sharper claim.**
Strings and Unicode, hashing and seeded randomness are identical on every JDK tried; number
formatting is identical on any JDK from 19 onward. A portfolio that half-believes
`Double.toString` might differ between its two targets writes defensive code forever, and 129
measured rows retire that belief — with the caveat, which is itself the finding, that the JDK is
the variable at least as often as the target is.

### 1.3 The two native targets agree with each other; the JVM carries its own timezone database

`macosArm64` and `linuxX64` differ in **one** row of 129 —
`datetime/available-zone-count`, 597 against 496 — and the JVM's two hosts differ in none, at 604
on both.

**Consequence.** kotlinx-datetime on Kotlin/Native reads the host's zone database and the JDK ships
its own. The difference is therefore not JVM-versus-native at all; it is "native inherits the
image" — and for a service in a container that makes the zone table a property of the **base
image**, not of the language. Every other number in §1.2 is a property of the runtimes and carries
over between native hosts unchanged, which is what makes the probe worth running on a Mac.

*(This closes hypothesis H1 as it was written: 128 of 129 identical, the zone count the exception.)*

### 1.4 Where a differing row reaches shipped code

Two places in the portfolio put a `HashMap` between the program and the wire. One of them was
already immune, and the contrast is the useful part.

| Fact | Where verified |
|---|---|
| `WindowAggregator.drain()` builds the `routes` list by iterating a `HashMap<SeriesKey, SeriesAccumulator>` with no ordering step | `metrik/agent/src/commonMain/kotlin/io/github/youndie/metrik/agent/WindowAggregator.kt` |
| that module publishes for `jvm`, `linuxX64`, `linuxArm64` and `macosArm64`, so both orders are shipped | `metrik/agent/build.gradle.kts` |
| the test that covers it reads results with `.single()` and `.single { … }` — order-insensitive, and green on both targets | `metrik/agent/src/commonTest/kotlin/io/github/youndie/metrik/agent/WindowAggregatorTest.kt` |
| `Histogram.toSparse()` sorts its bucket keys before serialising, so the same `HashMap` reaches the wire in a target-independent order | `metrik/shared/src/commonMain/kotlin/io/github/youndie/metrik/wire/Histogram.kt` |

**Consequence.** A metrik window payload sent by a JVM-hosted agent and by a native one lists the
same series in a different order. Nothing depends on that order today — the server merges by key —
so this is not a defect being reported; it is the one shape worth removing before a golden file, a
payload hash or an idempotency key starts depending on it. The sorted `Histogram` next door shows
the fix costs one `.sorted()`.

**Consequence — the existing test demonstrates the brief's premise rather than refuting it.** It
passes on both targets *because* it was written not to look.

### 1.5 The divergences this portfolio actually paid for — and the one that was never a divergence

None of them is in the standard library.

| Case | Cost | Where recorded |
|---|---|---|
| `InetSocketAddress(host, port)` does not resolve a hostname on Kotlin/Native — `connect` fails with `EINVAL`; on the JVM the same code works | every native agent reporting through a Kubernetes service name was silent **from its first day**, and the monitoring rule for "no data" reported a healthy service as dead | metrik `docs/research/research-architecture.md` §1.6 |
| `ktor-client-cio` has no TLS on native — `IllegalStateException: TLS sessions are not supported` | Telegram notifications never left the server; fixed with `ktor-client-curl` on native and `ktor-client-cio` on the JVM, through `expect/actual` | metrik `docs/research/research-architecture.md` §1.7; the split is visible in `metrik/server/build.gradle.kts` |
| `ktor-server-compression` is published for the JVM only | compression moved into the image build as pre-made `.gz` files | metrik `docs/research/research-architecture.md` §1.8 |
| ~~`Dispatchers.IO` is `internal` on Kotlin/Native~~ — **it is not** | a thread booblik does not need | see the correction below |

**Correction, verified 2026-09-11: `Dispatchers.IO` exists on Kotlin/Native in coroutines 1.11.0.**
`booblik/booblik-native/build.gradle.kts` says it is `internal` and gives that as the reason the
producer owns a thread through `newSingleThreadContext`. Compiled against coroutines 1.11.0, three
results:

| Source | `macosArm64` | `linuxX64` |
|---|---|---|
| `Dispatchers.IO` with only `import kotlinx.coroutines.Dispatchers` | `e: Cannot access 'val IO: CoroutineDispatcher': it is internal in 'kotlinx.coroutines.Dispatchers'` | — |
| the same line plus `import kotlinx.coroutines.IO` | compiles | compiles |
| `withContext(Dispatchers.IO) { … }` at runtime | runs; `Dispatchers.IO` prints `Dispatchers.IO` | — |

`Dispatchers.IO` on native is an **extension property** in package `kotlinx.coroutines`, so it needs
its own import; without it the compiler resolves the internal member of the same name and says so.
The message is accurate and reads exactly like "IO is internal on native", which is how it was
recorded. **The comment in booblik is wrong and the `newSingleThreadContext` it justifies is
probably unnecessary** — probably, because whether the producer wants a dispatcher or a dedicated
thread is a design question this document has not asked. That is B-16.

**Consequence — the gate has to reach the platform layer, and the stdlib probe cannot.** Every
entry above is a library, a system call or an import behaving differently, not a language
primitive. A `parityCheck` built out of §1.2 would have been green through all four.

**Consequence — and metrik §1.6 says exactly why a green in-process gate is not enough.** Quoting
its own post-mortem: the tests were green because the plugin test substitutes a fake sender, *so a
real socket was never opened once*. The divergence was reachable only by a check that runs the real
transport.

**Consequence — three of the four are answerable by a test that merely compiles and runs.** Resolve
a hostname through the real socket API; make one TLS request; reference each pinned Ktor plugin on
the target being built. The fourth would have been answered by compiling one line. That is a
platform probe, and it is what D1 makes the gate.

### 1.6 "Develop on the JVM" is really "test on the JVM", and not everywhere

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

**Consequence — metrik's server has no JVM test coverage at all**, and katcher's has half. Their
route, ingest and query tests live in `nativeTest`; the `jvm()` target compiles the production code
and runs nothing against it. For those two modules the pattern is not "develop on the JVM, ship
native" — the JVM half is a compile check.

**Consequence — and that makes the JVM target's purpose worth stating out loud.** No subject's
server module applies the `application` plugin, declares a `mainClass`, or produces an installable
distribution; shildik's only `application` module, `:distribution`, declares **`linuxX64` alone**.
The JVM targets exist so common tests run in seconds. Nothing ships on a JVM. Once that is written
down, most of the brief's design follows from it (D1).

**Settled 2026-09-11 (B-10): habit, not necessity.** metrik's `QueryRoutesTest.kt` moved from
`nativeTest` to `commonTest` unchanged and passes on `jvmTest` and `linuxX64Test` both. Nothing
required the placement: `openDatabase` is in `commonMain` and sqlx4k publishes a JVM variant. The
other eighteen are a `git mv` — B-23.

**And the move logged something nobody was looking for: the two targets do not run against the same
database.** sqlx4k on the JVM loads Xerial's `sqlite-jdbc`; on Kotlin/Native it is the Rust driver.
So a green `jvmTest` covers the routes and not the storage engine that ships — and, the other way
round, these tests once they run on both are the only thing in the portfolio that would notice the
two drivers disagreeing. It is a platform-layer divergence of exactly the kind §1.4 is about, found
by moving one file.

**Consequence — tracy is the reference subject.** 37 of its 39 tests are in `commonTest` and its CI
runs them on `jvm`, `linuxX64` and `macosArm64` on every pull request.

### 1.7 What a pull-request build already costs

Wall time of the Gradle job on the most recent successful `main` run of each repository,
2026-09-09, `ubuntu-latest`, read from `gh run view --json jobs`.

| Repository | Job | Wall time |
|---|---|---|
| shildik | `check` | 4 m 22 s |
| tracy | `build` | 4 m 56 s |
| katcher | `build` | 7 m 29 s |
| metrik | `build` | 10 m 03 s |

And what those minutes already contain:

| Fact | Where verified |
|---|---|
| `./gradlew build` runs `:server:linkReleaseExecutableLinuxX64`, `…LinuxArm64` **and** `…MacosArm64` | `gh run view 34400536457 -R youndie/tracy --log` |
| the same run executes `:server:jvmTest`, `:server:linuxX64Test` and `:server:macosArm64Test` | same log |
| metrik's run does the same for `:server`, `:agent` and `:shared` | `gh run view 34391036889 -R youndie/metrik --log` |
| all five subject repositories are public, so standard GitHub runners cost nothing | `gh repo view --json visibility`, five repositories, all `PUBLIC` |

**Consequence — RQ4's question has the wrong subject.** The brief budgets ten minutes for
`parityCheck` on the assumption that the native link is what it buys. The native release link for
every declared target is **already inside the existing pull-request build**, and so is running the
suite on both. A platform probe is a dozen more tests in a suite that already runs on every target:
seconds, on hardware that is already paid for.

---

## 2. Decisions

### D1. The gate is a platform probe, not a stdlib diff *(deviation from the brief)*

Brief: "`parityCheck`: builds both targets, starts each against the same stand, runs the same suite,
applies the normaliser, and **fails on any diff** not listed in `parity-allowlist.yaml`".

Decision: `parityCheck` is a set of ordinary `commonTest` tests — perhaps a dozen — that assert the
**platform layer** behaves on the target being built:

- resolve a hostname through the real socket API and connect (metrik §1.6's exact failure);
- make one HTTPS request with the client engine this repository pins, so a missing TLS stack fails
  here rather than in production (§1.7's);
- reference every pinned Ktor server and client plugin, so one published for the JVM only fails to
  resolve at compile time on the target that lacks it (§1.8's);
- and, as the cheapest possible member of the family, compile the coroutine dispatchers the code
  uses (§1.5's correction, which a single compiling line would have settled).

They run on every declared target in the build that already exists, they need no second process and
no allowlist, and a failure names a platform capability rather than a differing string.

Why this and not the brief's design:

- **It is the only shape that would have caught anything.** All three real cases in §1.5 are
  platform APIs; a transcript diff of §1.2 would have been green through every one of them.
- **There is no JVM service to start** (§1.6): no server module produces a runnable JVM artifact,
  and building one per repository, to be tested and never shipped, is not a three-day box.
- **A failing platform probe is a sentence, not a diff.** "linuxX64 cannot resolve `postgres`" is
  actionable; "row 41 differs" needs a reader to decide whether it matters, which is the work an
  allowlist then accumulates.
- The price, stated plainly: this gate says nothing about the 17 rows of §1.2. Those move at a
  Kotlin or JDK bump, not at a pull request, and D4 puts them where things that move on a bump go.

### D2. razves keeps a process-level gate, because it can

razves is a CLI with `jvm()` and two native executables. "Run the binary on the fixture, capture
stdout, diff" is one script and needs no stand. It is the portfolio's one instance of the brief's
original design, and worth keeping for exactly that reason: where it and D1's probe disagree about
the same commit, the difference is what the in-process design cannot see.

### D3. The stdlib transcript is a probe for version bumps, not a gate

The 17 rows are a property of Kotlin 2.4.10, kotlinx-serialization 1.11.0, kotlinx-datetime 0.8.0
**and the JDK** — §1.2 shows five of them moving between JDK 17 and JDK 25. Re-running is two
commands. Making it a per-pull-request gate would spend the budget where nothing changes between
pull requests, and would grow an allowlist of message texts that nobody ever removes an entry from.

So: `run.sh` on a version bump, the transcript committed beside the previous one, and the diff read
by a person. A renovate bump to Kotlin or to a kotlinx library is the trigger, and that is B-17.

### D4. The probe is committed, and it is not the gate

[`parity-probe/`](parity-probe/) is a standalone Gradle build under `docs/research/`, deliberately
outside sborka's own build: applying `sborka.kmp` to it would put this repository's explicit-API
rule, ktlint pass and test gate between the question and the answer. `-PprobeJdk=17` runs the JVM
half on another toolchain, because for at least three rows the JDK is the variable rather than the
target.

Same reasoning as the perf-lint probe in [`probe/`](probe/), and the same benefit: two readers
answering one question is what catches a defect in the first reader — here, literally, the
constant-folding defect in §1.2 that only showed up because a second JDK disagreed with the first.

### D5. Neither target is the reference

`compare.py` prints `left` and `right` and labels neither. The JVM is the older runtime and is not
automatically the right one: `\bé` matching nothing is JDK 19+'s answer, and JDK 17 agrees with
Kotlin/Native rather than with JDK 25. A divergence is resolved by deciding what the *contract*
says, which is sometimes "the JVM", sometimes "native", and sometimes "neither, both get a
`.sorted()`".

---

## 3. Deviations from the brief, collected

1. **Seven subjects, five.** telek has no JVM target; booblik's two halves are two programs (§1.1).
2. **The gate is a platform probe, not a transcript diff** (D1) — the brief's design would have
   caught none of the three cases the portfolio has paid for.
3. **"Starts each against the same stand" is not buildable today** — no JVM runnable exists for any
   of the four services (§1.6).
4. **"Under 10 minutes with cache" is not the question.** The native link is already in the
   pull-request build; a dozen more tests cost seconds (§1.7).
5. **The predicted divergences were wrong in both directions.** The brief predicted 2–5, with "key
   order in JSON produced from a map and a regex with a character class or a look-around" first.
   Key order: confirmed, and the only serialization row that moved. Regex character classes and
   look-arounds: `\w` on accented text, `\d` on Arabic-Indic digits, `\p{L}`, `\p{IsCyrillic}`,
   `[[:alpha:]]`, fixed and variable look-behind, named groups, back-references, possessive
   quantifiers and atomic groups **all agreed**.
6. **"Both binaries pass their unit tests" understates it for metrik and katcher** — their server
   suites do not run on the JVM at all (§1.6).
7. **One entry of the portfolio's own divergence list was wrong** (§1.5) — and it had been carried
   in a build file's comment, as a reason for a workaround, since the module was written.

---

## 4. Hypotheses, each with the milestone that settles it

**H1 — closed, 2026-09-11.** The `macosArm64` results carry over to `linuxX64` except for the zone
table: 128 of 129 rows identical, `datetime/available-zone-count` the exception (§1.3). The
prediction held exactly.

**H2 — metrik's and katcher's server tests are in `nativeTest` by habit, not by necessity.**
Everything they call is in `commonMain` (§1.6). Settled by moving one file to `commonTest` and
running `jvmTest` — B-10. If it compiles and passes, those two repositories join the gate for the
price of a `git mv`; if it does not, *what* stops it is the most interesting fact this strand can
produce, because it is a platform-layer divergence found by trying rather than by reading.

**H3 — the three platform-layer cases of §1.5 are not a closed set.** Prediction: the next one is
in file-system or process APIs. Settled by the first `parityCheck` that exercises a real socket and
a real file system — B-13.

**H4 — no shipped payload in the portfolio depends on `HashMap` order except metrik's `routes`.**
§1.4 searched by hand over five repositories, which is a hypothesis, not a fact. Settled by a
`kapkan`-style scan for a `Map` reaching a serializer without an ordering step.

**H5 — narrowed, 2026-09-11 (B-16).** The platform half is settled and the comment corrected in
both places it appeared — booblik's build file and `Producer`'s KDoc, where it was the stated reason
for the thread. The design half is not sborka's to settle: an actor with one consumer has a real
argument for a dedicated thread, and `Dispatchers.IO` has a real argument in deleting the `close()`
contract that a caller can get wrong. Nothing was measured, so nothing moved, and the question now
lives beside the code.

---

## 5. Risks, with the machinery that mitigates them

**Risk 1 — the platform probe is mistaken for a behavioural equivalence proof.** It says the socket
resolves and the plugins are there; it says nothing about the 17 rows. Mitigation: the task reports
what it covered — "6 platform assertions on jvm, linuxX64, linuxArm64; stdlib behaviour is
`parity-probe/`, last run 2026-09-11" — so the gap is visible in the same line as the green.

**Risk 2 — the stdlib probe rots into a file nobody re-runs.** It is not on the pull-request path by
design (D3), which is exactly how a check stops happening. Mitigation: the trigger is a renovate
bump to Kotlin or a kotlinx library, and that trigger is an item with a checker behind it (B-17),
not an intention.

**Risk 3 — the platform probe needs the network and becomes flaky, then gets disabled.** A TLS
request to a public host is an outage away from a red build on an innocent pull request.
Mitigation: the DNS and TLS targets are the repository's own stand, brought up for the test; the
probe fails with "the stand did not answer" and that message is different from "the target cannot
do TLS".

**Risk 4 — a number in §1.2 is quoted after it stops being true.** Five rows already move between
two JDKs, and one row was measuring the compiler until it was caught. Mitigation: every transcript
carries a header with its runtime, JDK and host; every claim in §1.2 names the comparison it comes
from; and `opaque(…)` keeps the formatting probes on the runtime.

---

## 6. Code anchors

| What | Where |
|---|---|
| the probe, its runner and its comparison tool | [`docs/research/parity-probe/`](parity-probe/) |
| the four transcripts these numbers come from | [`docs/research/parity-probe/results/`](parity-probe/results/) |
| the versions every measurement was taken at | [`catalog/sborka.versions.toml`](../../catalog/sborka.versions.toml) |
| where the gate would be wired, as `sborka.kmp` wires the test gate today | [`build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts`](../../build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts) |
| the reference subject | `tracy/server` |
| the only shipped payload found to depend on hash order | `metrik/agent/src/commonMain/kotlin/io/github/youndie/metrik/agent/WindowAggregator.kt` |
| the same shape, already immune | `metrik/shared/src/commonMain/kotlin/io/github/youndie/metrik/wire/Histogram.kt` |
| the comment §1.5 corrects | `booblik/booblik-native/build.gradle.kts` |
| the platform-layer cases, in the repository that paid for them | `metrik/docs/research/research-architecture.md` §§1.6–1.8 |

---

## 7. What happens next

The order is in [backlog.md](../../backlog.md), stages `stage-4-parity-evidence` and
`stage-5-parity-gate`. H1 is closed; what everything else waits on is B-10, because if metrik's and
katcher's suites cannot run on the JVM then two of the five subjects have no second half to gate.
B-13 — the platform probe — is the item this document exists to argue for.
