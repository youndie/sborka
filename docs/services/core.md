---
id: core
title: core — the shared data and the class-file readers
type: service
module: build-logic/core
tech_stack: [Kotlin, embedded-kotlin, JUnit 5]
owner: unassigned
depends_on: []
publishes: [io.github.youndie.sborka:core]
---

# core

## 1. Responsibility

What both halves of sborka need and neither owns: the reference `.editorconfig`, the version of the
release the jar came from (generated, not typed), and the two readers of compiled output —
`Joins` ("what did this repository build and never call") and `MethodSizes` (bodies against the C2
inline thresholds, patterns built per call, chains of eager materialisations, `Intrinsics.check*`
counts). Both readers exist here
rather than in the conventions because the **settings** plugin registers the tasks that use them,
and `core` is the only module a settings plugin is allowed to depend on.

**What it deliberately does not do.** It holds no plugin — hence `embedded-kotlin` rather than
`kotlin-dsl`, which would announce on every build that it found no plugin descriptors. It has no
runtime dependency at all, which is why the class-file readers are handwritten instead of using
ASM: the parsing of a constant pool was already here for `kapkanJoins`, and adding a dependency to
a jar that has none is a bigger decision than a hundred lines of reader.

## 2. Code anchors

| What | Code |
|---|---|
| the reader | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/MethodSizes.kt` |
| the instruction walk | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/Bytecode.kt` |
| the pool both readers share | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/ConstantPool.kt` |
| the unused-API reader | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/Joins.kt` |
| suppressions, read from source | `build-logic/core/src/main/kotlin/io/github/youndie/sborka/internal/Suppressions.kt` |
| the generated version | `build-logic/core/build.gradle.kts` — `generateVersionConstant` |
| tests against emitted bytecode | `build-logic/core/src/test/kotlin/io/github/youndie/sborka/internal/` |

## 3. How it is built

The version constant is generated into `build/generated/sborka/kotlin` and added to the main source
set, so the coordinate `sborka.settings` asks the published catalog for is this build's version
rather than a number typed beside it. ktlint excludes that generated directory.

**`internal` in Kotlin is public on the JVM, and it leaks into this jar's API.** `ConstantPool.Pool`
once held a `Map<Int, Pair<Int, Int>>` and the jar exported `component4`, `copy` and a getter naming
`kotlin.Pair` — which a consumer cannot name, because `core` is compiled with `embedded-kotlin` and
hands out no stdlib. Two releases went red with compilation, tests, `check` and ktlint all silent.
The check is `javap` over the built jar; the fix is a named type instead of `Pair`/`Triple`.

## 4. Dependencies

None at runtime. JUnit 5 for tests; the tests read class files the compiler produced, including
`javap` through `ToolProvider` as a second reader.

## 5. Configuration

None of its own. The thresholds in `MethodSizes` are constants read from `-XX:+PrintFlagsFinal` on
the JDK in use and printed beside each finding rather than assumed by a reader.

## 6. Quirks

* **The walk refuses rather than answering zero.** A body whose instruction walk does not land
  exactly on the end of the code array is listed in `Report.unwalked`; its call counts are not
  reported as zeroes. `when` compiles to `tableswitch`/`lookupswitch`, the only opcodes whose length
  depends on their offset, and that is the case the property test in `BytecodeTest` exists for.
* **Fixtures live in their own package.** `JoinsTest` asserts about the whole contents of
  `internal/fixture`, so a fixture added there for another test breaks it.
* **`<clinit>` is excluded from the pattern question and `<init>` is not** — see
  [feature-perf-lint](../features/feature-perf-lint.md) §7.
