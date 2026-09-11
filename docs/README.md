# docs — sborka

sborka is this portfolio's Gradle conventions as a plugin: the coordinate and the version, the
toolchain, publishing, the formatter and its rule set, the test gate, the multiplatform mechanics
and a shared version catalog — one line each in `gradle.properties` instead of dozens of lines of
Kotlin per repository.

This tree is the **layered** part of the documentation, added with the perf-lint work. The older
notes beside it are prose and stay that way:

| File | What it is |
|---|---|
| [conventions.md](conventions.md) | what each convention plugin does, for a person adopting them |
| [decisions.md](decisions.md) | why the conventions are shaped the way they are |
| [kapkan.md](kapkan.md) | the research behind the ktlint rule set — six proposed rules, three survivors, with the counts |
| [migration.md](migration.md) | moving a repository onto sborka |

```
[ Research — why, with the numbers and where they were verified ]
                     │
[ Feature — what it does, plus BDD scenarios as acceptance criteria ]
                     │
[ Service — which module owns what, how it is built, what surprises ]
```

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what is verified, what is a hypothesis | the artefacts and profiles each fact names |
| Feature | `features/` | *what* it does and *why*; BDD scenarios | this repository |
| Service | `services/` | which module owns what, how it is built, quirks | this repository |

There is no `screens/` layer (no client) and no `api/` layer (no HTTP surface) — a missing
directory is a valid answer, a renamed one is not.

**Backlog** — [backlog.md](../backlog.md): the index and the decisions; the items are one file each
in [`backlog/`](backlog/), cited as `[B-03](backlog/B-03-fail-on-a-pattern-built-per-call.md)`.

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body.
- One document, one entity. A feature spanning three modules is **one** file with three entries in
  `involved_services`.
- BDD scenarios are written from the code: real messages, real task names. A scenario for behaviour
  that does not exist yet says *(target)*.
- **The primary consumer is a coding agent.** Every document carries code anchors, so the reader
  reaches the code in one hop. Do not duplicate what lives in code; give the path.
- **A number is written down only with the file it was computed from.** The scripts in
  [`research/probe/`](research/probe/) recompute every share this tree quotes.
- Language: English, like the rest of this repository's code comments and README.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.

## Checks

```bash
make check
```

which is `scripts/backlog_index.py --check`, `scripts/docs_check.py` and
`scripts/coverage_map.py --check` as the gate, plus two reports read by a person. They are not
wired into `.github/workflows/check.yaml` yet — that workflow builds and publishes, and mixing a
documentation gate into it is a decision the next person to touch CI should take deliberately.

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with
no file behind it, fails `coverage_map.py`.

### Research (1)

- [x] [research-perf-lint](research/research-perf-lint.md) — the three rules, their profiles, how often each fires, and what none of them claims

### Services (3)

- [x] [core](services/core.md) — the shared data and the class-file readers
- [x] [settings](services/settings.md) — the settings plugin and where the reports are registered
- [x] [kapkan](services/kapkan.md) — the ktlint rule set, and why the perf rules are not in it

### Features (1)

- [x] [feature-perf-lint](features/feature-perf-lint.md) — the three rules as behaviour, with BDD scenarios
