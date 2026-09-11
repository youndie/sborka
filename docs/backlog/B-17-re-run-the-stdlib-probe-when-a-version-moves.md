---
id: B-17
title: "Make a Kotlin or kotlinx bump re-run the stdlib probe, so the transcript cannot silently rot"
status: open
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
