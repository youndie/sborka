# Backlog: a perf-lint whose rules carry their own measurements

> Role of this document: the backlog of the perf-lint work. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. What lives here is the index (generated)
> and everything that is not an item: the goal, the stages, and the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `python3 scripts/backlog_index.py` after editing.
>
> This is not sborka's backlog as a whole — the conventions, the publishing and the migration are
> tracked as issues. It exists because this piece of work has an order that a list of issues does
> not show: nothing can become a gate before there is a scope, and nothing is worth defending
> before a saving has been measured once.

## Goal

Three rules, each carrying the profile line that justifies it and the count of how often it fires
on this portfolio, replacing the thirty-entry performance checklist that carries neither. The
research is [research-perf-lint](docs/research/research-perf-lint.md); the behaviour they are
supposed to produce is [feature-perf-lint](docs/features/feature-perf-lint.md).

The order below is the order the evidence demands. Today two of the three questions are asked and
printed, none is enforced, and no measurement has yet shown a *saving* rather than a *share* —
which is why the last stage is not the smallest one.

## Stages

A stage is a field on the item, not a directory. Items are cited by id from the research and the
feature document, so re-prioritising must never move a file.

| Stage id | Stage | What it is |
|---|---|---|
| `stage-1-evidence` | The detectors match what was measured | The chain question does not exist yet, and two of the counts it will report are known to be wrong in ways the probe already found. |
| `stage-2-gate` | One rule becomes a gate, inside a declared scope | A rule that obliges nothing is a report. The cheapest rule (8 findings across eleven repositories) is the one that can afford to fail a build. |
| `stage-3-saving` | A measured saving, not only a measured share | Every number so far says what a shape owns. None says what removing it bought. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (7)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-01](docs/backlog/B-01-chain-question-in-the-class-file-walk.md) `[ ]` | Ask the class-file walk how many containers a body materialises | P1 | M | - |
| [B-02](docs/backlog/B-02-declare-which-modules-are-hot.md) `[ ]` | Let a repository name the modules whose findings are gates | P1 | S | - |
| [B-03](docs/backlog/B-03-fail-on-a-pattern-built-per-call.md) `[ ]` | Fail on a pattern built per call inside a hot module | P1 | S | B-02 |
| [B-05](docs/backlog/B-05-measure-a-saving-not-a-share.md) `[ ]` | Measure a saving, not only a share: fix one named finding and A/B it | P1 | M | - |
| [B-04](docs/backlog/B-04-read-the-compose-chain-findings.md) `[?]` | Read the chain findings inside Compose code and decide whether the rule applies there | P2 | S | B-01 |
| [B-06](docs/backlog/B-06-answer-the-interpolated-pattern.md) `[?]` | Decide what the pattern rule tells a caller who interpolates the pattern | P2 | S | B-03 |
| [B-07](docs/backlog/B-07-count-a-method-once-in-a-multiplatform-build.md) `[ ]` | Count a method once when a multiplatform build compiles it into several directories | P2 | XS | - |

## Closed (0)

<!-- END INDEX -->

## Decisions that are not items

- **The 2 % line.** A shape becomes a rule only where a profile charged it more than 2 % of
  something. Boxing (1.26 %) and lazy logging (≤ 1.57 %) were dropped by this line and are in every
  checklist ever written. See [research-perf-lint](docs/research/research-perf-lint.md) D1.
- **`body-over-freq-inline-size` stays a report, permanently.** A refusal to inline is not a
  measured cost, and 1 058 findings across the portfolio is a counter. This is a decision, not an
  item waiting to be done.
- **The probe stays in the repository** (`docs/research/probe/`) and is not the implementation.
  Two readers answering the same question is what caught a defect in the first one.
