# Backlog: the two strands of research this repository owns

> Role of this document: the backlog of sborka's research work — the perf-lint, and the JVM/native
> parity gate. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. What lives here is the index (generated)
> and everything that is not an item: the goal, the stages, and the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `python3 scripts/backlog_index.py` after editing.
>
> This is not sborka's backlog as a whole — the conventions, the publishing and the migration are
> tracked as issues. It exists because these pieces of work have an order that a list of issues
> does not show: nothing can become a gate before there is a scope, and nothing is worth defending
> before a saving has been measured once.

## Goal — the perf-lint

Three rules, each carrying the profile line that justifies it and the count of how often it fires
on this portfolio, replacing the thirty-entry performance checklist that carries neither. The
research is [research-perf-lint](docs/research/research-perf-lint.md); the behaviour they are
supposed to produce is [feature-perf-lint](docs/features/feature-perf-lint.md).

The order below is the order the evidence demands. Today two of the three questions are asked and
printed, none is enforced, and no measurement has yet shown a *saving* rather than a *share* —
which is why the last stage is not the smallest one.

## Goal — the JVM/native parity gate

A build that stays red until the JVM half and the native half of one source agree, and a published
list of where they do not. The research is
[research-parity](docs/research/research-parity.md); the brief it answers is
[source-brief-parity](docs/research/source-brief-parity.md).

The order here is set by one measurement and one absence. The measurement: 129 probes, 17
differences, and every one of them taken on macOS — so nothing downstream is worth building until
the probe has run on the target that ships. The absence: two of the five subjects have no JVM tests
at all, and a gate that compares two suites needs both of them to exist.

## Stages

A stage is a field on the item, not a directory. Items are cited by id from the research and the
feature document, so re-prioritising must never move a file.

| Stage id | Stage | What it is |
|---|---|---|
| `stage-1-evidence` | The detectors match what was measured | The chain question does not exist yet, and two of the counts it will report are known to be wrong in ways the probe already found. |
| `stage-2-gate` | One rule becomes a gate, inside a declared scope | A rule that obliges nothing is a report. The cheapest rule (8 findings across eleven repositories) is the one that can afford to fail a build. |
| `stage-3-saving` | A measured saving, not only a measured share | Every number so far says what a shape owns. None says what removing it bought. |
| `stage-4-parity-evidence` | The parity numbers survive leaving one machine | The 17 differences were measured on `macosArm64`, and two subjects have no JVM test run to compare against at all. Both are cheap to settle and everything below waits on them. |
| `stage-5-parity-gate` | A gate that is red on a real divergence, and honest about what it skipped | The diff is seconds — the native link is already in the pull-request build. What needs deciding is what a test run writes down, and how a gate says what it did not test. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (7)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-09](docs/backlog/B-09-run-the-probe-on-linux.md) `[ ]` | Run the parity probe on linuxX64 and say which of its numbers were about macOS | P0 | XS | - |
| [B-10](docs/backlog/B-10-move-a-native-only-server-test-to-common.md) `[ ]` | Move one native-only server test to commonTest and find out what stops it | P0 | S | - |
| [B-12](docs/backlog/B-12-a-transcript-a-test-run-can-be-compared-by.md) `[ ]` | Give a test run something to compare: a normalised transcript, written by the suite itself | P1 | M | B-10 |
| [B-13](docs/backlog/B-13-parity-check-that-reports-its-own-blind-spot.md) `[ ]` | parityCheck: fail on an unlisted difference, and say in the same line what it did not test | P1 | L | B-12 |
| [B-15](docs/backlog/B-15-the-page-people-will-search-for.md) `[ ]` | The page people will search for: what the JVM and Kotlin/Native actually do differently | P1 | S | B-09 |
| [B-11](docs/backlog/B-11-sort-the-series-metrik-puts-on-the-wire.md) `[ ]` | Sort the series metrik's agent puts on the wire, the way its histogram already does | P2 | XS | - |
| [B-14](docs/backlog/B-14-the-process-level-gate-where-it-is-buildable.md) `[ ]` | Run razves as two processes and diff the output — the gate the brief asked for, where it fits | P2 | S | - |

## Closed (8)

**The detectors match what was measured**

- [B-01](docs/backlog/B-01-chain-question-in-the-class-file-walk.md) `[x]` - Ask the class-file walk how many containers a body materialises
- [B-04](docs/backlog/B-04-read-the-compose-chain-findings.md) `[x]` - Read the chain findings inside Compose code and decide whether the rule applies there
- [B-07](docs/backlog/B-07-count-a-method-once-in-a-multiplatform-build.md) `[x]` - Count a method once when a multiplatform build compiles it into several directories

**One rule becomes a gate, inside a declared scope**

- [B-02](docs/backlog/B-02-declare-which-modules-are-hot.md) `[x]` - Let a repository name the modules whose findings are gates
- [B-03](docs/backlog/B-03-fail-on-a-pattern-built-per-call.md) `[x]` - Fail on a pattern built per call inside a hot module
- [B-06](docs/backlog/B-06-answer-the-interpolated-pattern.md) `[x]` - Decide what the pattern rule tells a caller who interpolates the pattern
- [B-08](docs/backlog/B-08-keep-the-gate-off-test-output.md) `[x]` - Keep the gate off test output, and mark what it will not judge

**A measured saving, not only a measured share**

- [B-05](docs/backlog/B-05-measure-a-saving-not-a-share.md) `[x]` - Measure a saving, not only a share: fix one named finding and A/B it

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
- **A rule is accepted when the profile stops charging the shape it named**, not when the service
  gets measurably faster — and "the shape", not "the method name": a rewrite renames things, and a
  metric keyed on the old name reads as a total win (research §1.7's correction). `B-05` fixed the largest finding on a real service and the A/B moved −0.75 %
  against a 4.46 % spread: at this size the stand cannot see a correct fix, and asking a rule to
  prove itself that way is asking for a number nobody can produce honestly.
