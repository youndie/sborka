# Backlog: the two strands of research this repository owns

> Role of this document: the backlog of sborka's research work — the perf-lint, the JVM/native
> parity gate, and the static-binary question. **One file per item in
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

The order here is set by what the measurements turned out to say. 129 probes found 17 differences
between the two standard libraries and **none of them has ever cost this portfolio anything**; the
three that did are platform APIs — a socket that will not resolve a hostname, a missing TLS stack,
a Ktor plugin published for one target — and a fourth entry on that list was not a divergence at
all but a compiler message read as a fact. So the gate is a platform probe (B-13), the stdlib
transcript moves to a version-bump trigger (B-17), and the item everything waits on is B-10: two of
the five subjects run no JVM tests at all, and a gate needs a second half to run on.

## Goal — the static binary

An honest answer to "can a Kotlin/Native service run in an image with nothing else in it, and what
does it cost". The research is [research-static-binary](docs/research/research-static-binary.md);
the brief is [source-brief-static-binary](docs/research/source-brief-static-binary.md).

The answer is no, not today: `-static` against glibc does not link, and the musl route links only
after three archives are shimmed and then segfaults before printing a line. What came out of it
instead ships on its own — of the ten shared libraries a Kotlin/Native binary declares, six supply
no symbol it imports, and one of those six is why two Dockerfiles here copy a file out of the
builder image by hand and carry a paragraph about matching glibc versions. B-18 deletes all of it.

## Stages

A stage is a field on the item, not a directory. Items are cited by id from the research and the
feature document, so re-prioritising must never move a file.

| Stage id | Stage | What it is |
|---|---|---|
| `stage-1-evidence` | The detectors match what was measured | The chain question does not exist yet, and two of the counts it will report are known to be wrong in ways the probe already found. |
| `stage-2-gate` | One rule becomes a gate, inside a declared scope | A rule that obliges nothing is a report. The cheapest rule (8 findings across eleven repositories) is the one that can afford to fail a build. |
| `stage-3-saving` | A measured saving, not only a measured share | Every number so far says what a shape owns. None says what removing it bought. |
| `stage-4-parity-evidence` | Find out which claimed divergences are real | One is already gone — `Dispatchers.IO` on native was a misread compiler message — and the biggest open question is why two subjects run no JVM tests. Cheap items, and everything below depends on their answers. |
| `stage-5-parity-gate` | A gate at the layer that has actually broken | The native link is already inside the pull-request build, so a dozen platform assertions cost seconds. What needs deciding is what they assert and how the task reports the scope it did *not* cover. |
| `stage-6-static-binary` | Take the win that does not depend on `scratch`, then decide about `scratch` | One item ships a smaller, safer image today and is independent of the rest; one decides whether the brief ends in a recipe or an upstream ticket; two are things the measurements turned up on the way. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (2)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-15](docs/backlog/B-15-where-the-two-runtimes-actually-diverge.md) `[~]` | The page: where the JVM and Kotlin/Native actually diverge, and it is not in the stdlib | P1 | S | - |
| [B-24](docs/backlog/B-24-file-what-the-musl-route-found.md) `[ ]` | File the two hardcoded sources upstream, with a reproduction that reaches main | P2 | XS | B-19 |

## Closed (24)

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

**Find out which claimed divergences are real**

- [B-09](docs/backlog/B-09-run-the-probe-on-linux.md) `[x]` - Run the parity probe on linuxX64 and say which of its numbers were about macOS
- [B-10](docs/backlog/B-10-move-a-native-only-server-test-to-common.md) `[x]` - Move one native-only server test to commonTest and find out what stops it
- [B-11](docs/backlog/B-11-sort-the-series-metrik-puts-on-the-wire.md) `[x]` - Sort the series metrik's agent puts on the wire, the way its histogram already does
- [B-16](docs/backlog/B-16-drop-the-workaround-a-wrong-comment-justifies.md) `[x]` - Correct booblik's Dispatchers.IO comment, and decide whether the thread it justifies is still wanted
- [B-23](docs/backlog/B-23-move-the-rest-of-the-native-only-suites.md) `[x]` - Move the other eighteen native-only server tests to commonTest

**A gate at the layer that has actually broken**

- [B-12](docs/backlog/B-12-a-transcript-a-test-run-can-be-compared-by.md) `[-]` - Give a test run something to compare: a normalised transcript, written by the suite itself
- [B-13](docs/backlog/B-13-a-platform-probe-that-runs-on-every-target.md) `[x]` - parityCheck: a dozen tests that make the platform layer answer on every target
- [B-14](docs/backlog/B-14-the-process-level-gate-where-it-is-buildable.md) `[-]` - Run razves as two processes and diff the output — the gate the brief asked for, where it fits
- [B-17](docs/backlog/B-17-re-run-the-stdlib-probe-when-a-version-moves.md) `[x]` - Make a Kotlin or kotlinx bump re-run the stdlib probe, so the transcript cannot silently rot
- [B-25](docs/backlog/B-25-wire-the-probe-into-a-build.md) `[x]` - Wire platform-probe into a build: the task, one consumer, and the TLS assertion
- [B-26](docs/backlog/B-26-take-the-gate-into-the-other-four.md) `[x]` - Take the platform gate into the other four subjects, metrik first

**Take the win that does not depend on `scratch`, then decide about `scratch`**

- [B-18](docs/backlog/B-18-drop-the-library-nothing-calls.md) `[x]` - Link with --as-needed, and delete the COPY line two Dockerfiles carry because of it
- [B-19](docs/backlog/B-19-set-the-properties-instead-of-working-around-them.md) `[x]` - Set the four properties instead of working around them, and give musl a C++ runtime built for it
- [B-20](docs/backlog/B-20-strip-the-binary.md) `[-]` - Strip the release binary: a third of the scratch prize, for one flag, today
- [B-21](docs/backlog/B-21-print-what-the-binary-declares.md) `[x]` - Print the binary's NEEDED list into the build log, so a new dependency shows up in a diff
- [B-22](docs/backlog/B-22-take-the-flag-into-the-two-images.md) `[x]` - Release the flag and take the COPY line out of the two images that carry it

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
