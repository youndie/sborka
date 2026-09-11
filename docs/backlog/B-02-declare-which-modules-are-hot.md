---
id: B-02
title: "Let a repository name the modules whose findings are gates"
status: done
priority: P1
size: S
stage: stage-2-gate
epic: feature-perf-lint
blocked_by: []
---

# B-02 — Let a repository name the modules whose findings are gates

Nothing static knows what is hot. The rules measure shapes; the profile that justified them was
taken on one endpoint of one service. Without a scope, the only honest setting for all three rules
is "print", and a report nobody has to act on is where this set stops being a lint.

- **The decision and its reason.** `sborka.perflint.hot` in `gradle.properties` names module paths
  (`:server,:shared:domain`). A finding of a gate rule inside one fails the build; everywhere else
  it prints. Absent, everything prints — so taking a new version of sborka cannot break anybody's
  build, which is the property that lets the gate ship at all.
- **Rejected: reachability from a route.** Computable — `Joins` already resolves calls through the
  class hierarchy — and still not hotness: konekt's OpenAPI document builder is reachable from a
  route and runs once per process. It would replace a person's one line with a call graph that is
  wrong in a way nobody can see.
- **Rejected: an annotation on the hot code.** It needs an artefact on every consumer's
  compile classpath, which is the argument that made `@Kapkan.Allow` a `@Suppress` instead
  (`docs/kapkan.md` §1, row 7).
- **Does not cover** what a repository should put there. That is a judgement about which process
  runs under load, and it belongs to the repository.

- AC: with `sborka.perflint.hot` unset, `check` on the stand passes and the report still prints
  every finding.
- AC: with it set to a module that holds a deliberate finding, `check` fails naming the module, the
  method and the property that scoped it — verified by control, i.e. by removing the property and
  seeing the same build pass.
- Anchors: `sborka/build-logic/settings/src/main/kotlin/io/github/youndie/sborka/settings.settings.gradle.kts`,
  `sborka/stand/`

## Done, 2026-09-11 — the scope, not yet the failure

`sborka.perflint.hot` is read by the settings plugin, comma-separated project paths, `:` optional.
The scan now runs **per module** — same files, one call each — so every finding carries the module
it came from, and the report prints `:jvm-lib [hot] stand.DigitsKt.heaviest…` plus a closing line
naming the scope.

- **An empty scope fails, twice over.** A path no project has, and a module that compiled no class
  files this reader can see, both fail the task. Either would otherwise leave the property naming an
  empty set of findings, and an empty gate cannot be told from a passing one.
- **The report says so even when nothing is scoped**: `nothing declared hot — set
  sborka.perflint.hot to the modules whose findings should be judged`. Silence that reads as a
  decision rather than as a check that did not run.
- **Three controls, run on the stand**: `-Psborka.perflint.hot=:jvm-libb` fails naming the five
  paths the build has; `=:platform` fails because that module compiles nothing; `=` (empty) prints
  the "nothing declared hot" line and passes. The stand itself now declares `:jvm-lib` hot and
  carries a deliberate chain in it, so CI exercises the marking rather than only its absence.

**Deviation from this item's own acceptance criteria, and it is deliberate.** The AC asked for a
build that fails with the property set. The failure ships with `B-03` instead, because what makes a
failure actionable is the message and the suppression form, and a gate with neither is a gate people
switch off within a week. What is here is everything that decides *which* findings a gate may judge.
