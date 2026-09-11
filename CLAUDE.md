# Working in sborka

## How to start a session

1. **[docs/README.md](docs/README.md)** — the layered documentation and what each layer answers.
   For anything touching the perf-lint or the class-file readers, read
   [docs/research/research-perf-lint.md](docs/research/research-perf-lint.md) **first**: it carries
   the measurements the rules stand on, and a task read without it looks like "add the obvious
   rule", which is how a checklist with thirty entries and no arithmetic gets written.
2. **[backlog.md](backlog.md)** — what is next and in which order; the items are one file each in
   `docs/backlog/`.
3. The layer document for the thing being changed: `docs/features/` for behaviour,
   `docs/services/` for a module.

The older notes — `docs/conventions.md`, `docs/decisions.md`, `docs/kapkan.md`,
`docs/migration.md` — are prose and in Russian. `docs/kapkan.md` is the research behind the ktlint
rule set; it holds measurements, so do not paraphrase its conclusions from memory.

## Commands

```bash
./gradlew check    # builds the plugins, runs their tests, and APPLIES them in stand/
make check         # the documentation gate plus the two non-blocking reports
```

Neither runs the other. `make check` is `scripts/*.py` over `docs/`; `./gradlew check` is the build.

Reports that are deliberately not gates, run by hand or once in CI on the stand:

```bash
./gradlew -p stand kapkanJoins kapkanMethodSizes
```

## What this repository is careful about

- **A number goes into a document with the file it was computed from.** `docs/research/probe/`
  recomputes every share the research quotes; a number without a derivation cannot be challenged.
- **`main` describes what exists.** A document about behaviour that is not built yet is
  `status: draft` and lives in an open pull request.
- **Conventions configure Kotlin, they do not apply it** (`compileOnly` on KGP, `plugins.withId`).
  The settings plugin must reference no Kotlin type at all — it is loaded by a classloader that has
  none.
- **sborka is linted by the rules sborka publishes**: the root puts `:kapkan` on `ktlintRuleset`
  through `includeBuild`, so a rule set that does not build fails here rather than in somebody's
  migration.
