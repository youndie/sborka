---
id: B-02
title: "Let a repository name the modules whose findings are gates"
status: open
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
