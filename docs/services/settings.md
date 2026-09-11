---
id: settings
title: settings — the settings plugin, and where the reports are registered
type: service
module: build-logic/settings
tech_stack: [Kotlin, kotlin-dsl, Gradle settings plugin]
owner: unassigned
depends_on: [core]
publishes: [io.github.youndie.sborka:settings, io.github.youndie.sborka.settings.gradle.plugin]
---

# settings

## 1. Responsibility

Applied in `settings.gradle.kts`: repositories with content filters, the `wip` snapshot catalog,
the `.editorconfig` check, and the two reports that read compiled output — `kapkanJoins` and
`kapkanMethodSizes`. The reports live here rather than in a project convention so that no
repository has to remember to register them.

**What it deliberately does not do.** Nothing here may reference a Kotlin type. A settings plugin
is loaded by the settings classloader, which is the parent of every project buildscript classloader
and never carries KGP; a reference would compile and then fail at apply time — and, because the two
plugin jars share a classloader, would take the project conventions down with it.

## 2. Code anchors

| What | Code |
|---|---|
| the plugin | `build-logic/settings/src/main/kotlin/io/github/youndie/sborka/settings.settings.gradle.kts` |
| `kapkanMethodSizes` | same file — registration, the `classesRead > 0` guard, the four report sections |
| dependency on the readers | `build-logic/settings/build.gradle.kts` — `api(projects.core)` |

## 3. How it is built

The report tasks are registered on the root project and wired to compilations **by task name**:
`gradle.projectsEvaluated` collects `*Classes` names out of `tasks.names` without instantiating a
single task. A `tasks.matching { }` predicate would have to create every task in every project to
ask its name — the configuration-avoidance mistake with a measurable price, and it surfaced one of
KGP's own deprecation warnings on the way. KGP registers `<target>MainClasses` only for JVM-like
targets, so the list is exactly the JVM output without naming a target; `metadata…Classes` is
dropped because it assembles klibs, which the readers cannot read.

## 4. Configuration

`gradle.properties` keys, read through `providers.gradleProperty`. `sborka.catalog=off` turns the
`wip` catalog off (the stand needs it off: a settings plugin resolves the catalog before any task
in the same invocation has run). `sborka.perflint.hot` names the modules whose findings a gate will judge, comma-separated project
paths (`:server,:shared:domain`); absent, every perf finding is a report. A path no project has, or
a module that compiled no class files, fails the task rather than scoping nothing quietly. See
[feature-perf-lint](../features/feature-perf-lint.md) R4.

## 5. Quirks

* **The reports are in `check` only where a scope is declared.** With `sborka.perflint.hot` set,
  `check` depends on `kapkanMethodSizes`, which fails on an unanswered pattern finding inside one of
  the named modules — **outside test output**, which it prints and marks `[test]` but never judges. Without it the task cannot fail, so it stays out.
* **Neither report is in `check` otherwise.** `kapkanJoins` and `kapkanMethodSizes` print. CI runs them once
  on the stand (`-p stand kapkanJoins kapkanMethodSizes`), which is also the only thing that proves
  the registration works — before that, `kapkanJoins` was registered and called from nowhere.
* **A report over no class files fails.** `check(report.classesRead > 0)`: an empty report and a
  report that read nothing look identical in a log, and only one of them is an answer.
* **Filtered repositories go before the unfiltered one**, or a coordinate that exists upstream is
  resolved from the wrong copy.
