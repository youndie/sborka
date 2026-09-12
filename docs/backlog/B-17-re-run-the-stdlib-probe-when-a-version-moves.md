---
id: B-17
title: "Make a Kotlin or kotlinx bump re-run the stdlib probe, so the transcript cannot silently rot"
status: done
priority: P2
size: S
stage: stage-5-parity-gate
---

# B-17 — Make a Kotlin or kotlinx bump re-run the stdlib probe, so the transcript cannot silently rot

Research D3 keeps the 129-row stdlib transcript **off** the pull-request path: those rows move when
Kotlin, a kotlinx library or the JDK moves, not between commits, and gating on them would grow an
allowlist of exception message texts that nobody ever prunes.

That decision has an obvious failure mode, and it is the one this portfolio has a name for: a check
that is not on anyone's path stops happening. The numbers are already known to be version-bound —
five of 129 rows move between JDK 17 and JDK 25, and `\bé` is not a JVM/native difference at all on
JDK 17.

- **The decision and its reason.** A renovate bump touching `kotlin`, `serialization`, `datetime`
  or `coroutines` in `catalog/sborka.versions.toml` gets a checklist line and a job that runs
  `run.sh jvm` and `run.sh macosArm64`, commits the two transcripts, and posts the `compare.py`
  diff against the previous pair into the pull request. The person reads it; nothing fails
  automatically.
- **Not a gate, deliberately.** A moved row is usually an improvement in one of the runtimes, and a
  red build would teach people to update the expected file without reading it — which is how a
  transcript becomes a rubber stamp.
- **The JDK is a trigger too**, and a less obvious one: the toolchain version is not in the catalog.
  Whatever form this takes has to notice a toolchain bump as well, or it will miss the variable that
  moved the most rows so far.
- **Rejected: a scheduled monthly run.** It would produce a diff with no commit to attach it to,
  which is the report nobody reads.
- **Does not cover** `linuxX64`, which needs a Linux runner. The two transcripts above are enough
  to see a row move; §1.3 established that the native targets agree with each other bar the zone
  table.

- AC: a bump of `kotlin` in the shared catalog produces a pull request carrying both refreshed
  transcripts and the diff against the previous pair, and the research's §1.2 either keeps its
  numbers or is updated in the same pull request.
- Anchors: `catalog/sborka.versions.toml`, `docs/research/parity-probe/run.sh`,
  `docs/research/parity-probe/compare.py`, `renovate.json`

## Done, 2026-09-12 — a note for the person and a summary for the machine, neither of them a gate

Two pieces, because the item asked for two and they answer different halves.

**`renovate.json`** gains a rule matching `org.jetbrains.kotlin*` and `org.jetbrains.kotlinx*` whose
`prBodyNotes` carry the three commands that refresh the transcript. It is in sborka's own
`renovate.json` rather than in the shared preset: the transcript is this repository's artefact, and
a note about it in every repository's bump would be noise.

**`.github/workflows/parity-probe.yaml`** runs the probe on `ubuntu-latest` and `macos-14` — a
native test binary is not cross-run, so each host produces its own — diffs each fresh transcript
against the newest committed one for that target, and writes the result into the job summary.
Nothing in it can fail the pull request: every step is `continue-on-error`, and the comparison step
is `if: always()`.

**Path-filtered, unlike `check.yaml`, and the difference is the point.** There a filter buys seconds
and costs a red default branch; here the filter *is* the trigger — the question is only worth asking
when one of the versions the transcript is a property of has moved.

**"No baseline" is reported, not skipped.** A missing previous transcript and no differences look
identical in a log and mean opposite things, so the summary says which it is.

Dry-run against a deliberately altered row: `129 rows compared, 1 differ`, naming the row and both
values. The glob picks `2026-09-11-jvm.tsv` and ignores `2026-09-11-jvm-jdk17.tsv`, which is the
separate JDK-17 baseline and not a previous run.

**The residual gap, stated because the item asked for it.** The JDK is the variable that moves the
most rows, and the probe does not pin one — it uses whatever the runner provides. `gradle.properties`
and the setup action are watched, so a deliberate toolchain change triggers the job; **a change in
what `ubuntu-latest` ships does not**, because no file in this repository moves. Pinning a toolchain
in the probe would fix that and would also stop the probe measuring the JDK a developer actually
has, which is the more useful of the two. Left as it is, on purpose, and written down here.
