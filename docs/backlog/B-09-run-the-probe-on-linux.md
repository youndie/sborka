---
id: B-09
title: "Run the parity probe on linuxX64 and say which of its numbers were about macOS"
status: done
priority: P0
size: XS
stage: stage-4-parity-evidence
---

# B-09 — Run the parity probe on linuxX64 and say which of its numbers were about macOS

Every number in [research-parity §1.2](../research/research-parity.md) was measured on `jvm` and
`macosArm64`, because Kotlin/Native tests are not cross-run and the probe was written on a Mac. The
targets that ship are `linuxX64` and `linuxArm64`. For the standard library, the regex engine and
kotlinx-serialization that gap is almost certainly nothing — same runtime, same sources — but
"almost certainly" is what this whole document exists not to say, and one row is known to be
host-dependent already: `datetime/available-zone-count` reads the host's zone database, and 597 is
macOS's answer.

- **The decision and its reason.** Run [`run.sh linuxX64`](../research/parity-probe/run.sh) on the
  Linux box, commit the transcript beside the other two, and diff **native against native** as well
  as native against JVM. The second diff is the one this item is for: it separates "Kotlin/Native
  differs from the JVM" from "macOS differs from Linux", and those have been conflated in every
  table in §1.2 until it runs.
- **The result decides the document's scope, not just a row.** If one row moves, §1.2 keeps its
  numbers and gains a footnote. If several move, every table there is about macOS and has to say
  so in its heading.
- **Rejected: taking the macOS numbers as read and adding a disclaimer.** A disclaimer on a table
  of 129 measured rows is an invitation to trust the table anyway; the probe is one command and the
  box is already there.
- **Does not cover** `linuxArm64`. No machine in reach runs it, and the arm64 question is a
  different one — a second architecture rather than a second OS.

- AC: `docs/research/parity-probe/results/<date>-linuxX64.tsv` is committed, and §1.2 either says
  "identical on both native hosts except `available-zone-count`" or names every row that moved.
- Anchors: `docs/research/parity-probe/run.sh`, `docs/research/parity-probe/compare.py`,
  `docs/research/research-parity.md`

Settles hypothesis H1 of the research.

## Done, 2026-09-11 — the prediction held, and the mechanism is worth more than the row

`run.sh linuxX64` on the Linux box (Ubuntu 24.04 under WSL2, JDK 25.0.4), transcript committed as
`results/2026-09-11-linuxX64.tsv`. Three comparisons, not one:

- **`macosArm64` vs `linuxX64`: 1 row of 129.** `datetime/available-zone-count`, 597 against 496.
  Everything else — including all seventeen JVM/native differences — is identical, so §1.2's
  numbers are about the runtimes and not about macOS.
- **JVM on macOS vs JVM on Linux: 0 rows.** 604 zones on both.
- **JVM on Linux vs `linuxX64`, the pair that actually ships: the same 17.**

The reason those two facts sit together is the finding: **kotlinx-datetime on Kotlin/Native reads
the host's zone database and the JDK ships its own.** For a service in a container the zone table
is therefore a property of the base image, not of the language — which is a Brief A question
wearing a Brief B row, and it is now written into §1.3 as such.

Two things came out of the same run that were not in this item's scope and changed the document
more than it did: the transcripts now carry a header naming the JDK, and one probe row turned out
to be measuring the compiler rather than the runtime (research §1.2, "Correction found while
building the probe").
