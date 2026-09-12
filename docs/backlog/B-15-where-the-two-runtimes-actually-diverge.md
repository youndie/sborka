---
id: B-15
title: "The page: where the JVM and Kotlin/Native actually diverge, and it is not in the stdlib"
status: done
priority: P1
size: S
stage: stage-5-parity-gate
---

# B-15 — The page: where the JVM and Kotlin/Native actually diverge, and it is not in the stdlib

The brief asked for "Kotlin/Native vs JVM: the behaviour differences we test for". The research
found a better headline, and the difference between the two is the point of the page.

Everyone writing this page writes the stdlib table. This one has it — 129 probes, 17 rows apart,
every claim citing a committed transcript — and then says the thing the table cannot: **none of
those seventeen has ever cost this portfolio anything.** The three that did are platform APIs: a
socket that does not resolve a hostname on Kotlin/Native, a client engine with no TLS, a Ktor
plugin published for the JVM alone. Each has a story with a price attached, and those stories have
no equivalent anywhere else — [research-parity §1.5](../research/research-parity.md) is the text.

- **The decision and its reason.** Structure the page around the four cases, not the table. Each
  one: the code that looked correct, what the platform did instead, how long it was wrong, and how
  it was found. The stdlib table goes in as the *negative* result — strings, hashing and seeded
  randomness identical, number formatting identical on any JDK from 19 — because a reader who
  believes `Double.toString` might differ writes defensive code forever, and 129 rows retire that
  belief.
- **The fourth case is the best one and the least flattering.** `Dispatchers.IO` was recorded as
  `internal` on native, in a build file, as the reason for a workaround. It is not: it is an
  extension property that needs its own import, and without the import the compiler names the
  internal member of the same name. A true-sounding sentence backed by "I checked by compiling"
  survived unchallenged. That is a more useful thing to publish than another divergence.
- **Every row names a probe id, a transcript and its versions** — Kotlin 2.4.10, kotlinx-
  serialization 1.11.0, kotlinx-datetime 0.8.0, and **the JDK**, because five rows move between
  JDK 17 and JDK 25 and `\bé` is not a JVM/native difference at all on JDK 17. A page that names
  Kotlin and not the JDK is wrong for those rows in a way no reader can detect.
- **Rejected: the title the brief proposed.** "The behaviour differences we test for" promises a
  checklist and delivers one; the finding is that the checklist is the wrong artefact.
- **Does not cover** the gate. How this portfolio keeps the list true is B-13, and it is a sentence
  on the page rather than its subject.

- AC: a page on kotlin.website whose every claim names a probe id present in
  `docs/research/parity-probe/results/` or a case in research §1.5, with the versions and the JDK
  each was measured at, and whose lead is the platform layer rather than the table.
- Anchors: `docs/research/parity-probe/results/`, `docs/research/research-parity.md`,
  `metrik/docs/research/research-architecture.md`,
  `kotlin-website/site/src/jsMain/resources/markdown/blog`

## Drafted, 2026-09-12 — written, registered by the build, and not published

`kotlin-website`, branch `feat/jvm-native-differences` (`363e53d`):
`site/src/jsMain/resources/markdown/blog/JvmAndNativeDifferences.md`, 115 lines. The repository's
own check passes — `./gradlew :site:jsProcessResources` prints
`blog post: blog/JvmAndNativeDifferences.md -> /blog/jvm-and-native-differences`, which is what says
the post is registered and the route is the expected one.

**Not pushed, and that is the whole of what is left.** Publishing on that site is a push to `main`,
which builds an image and deploys; putting something under the owner's name on a public site is
theirs to do, not something to finish inside a loop.

**The framing in this item did not survive contact with the repository's own rules.** It said to
structure the page around the four cases with their cost. `kotlin-website`'s `blog-post` skill
rejects exactly that: *"это не жанр «история одного бага»… драматургия вокруг находки не нужна"*.
So the page is a reference page in that register — what was measured, what agrees, what differs, the
JDK as a variable, the platform layer, limits — and the cases appear as facts in the section they
belong to rather than as the plot. The substance is unchanged; the shape is the site's.

**One deviation from that checklist, flagged rather than skipped.** It requires a section with
installation coordinates, "so the reader has something to copy". The subject is a set of
measurements, not a tool, and there are no coordinates. The commands that reproduce it are in their
place instead. If that is the wrong call for this site, the fix is small and it is the owner's.

**What the page carries that no summary of it would.** Forty-nine rows agreeing exactly, twenty of
22 regex probes agreeing including the constructs usually named as risks, the JDK moving five rows
between 17 and 25, and the `InetSocketAddress` case having been true and now not — with the reason
that matters: a hand-kept list of platform differences only grows, because nothing in it reports the
day an entry stops being true.

## Published, 2026-09-12

[kotlin-website#20](https://github.com/vedutsya-raboty/kotlin-website/pull/20), merged as `d7179c2`,
at `/blog/jvm-and-native-differences`. That repository publishes on a push to `main`, so the merge is
the publication.

**Not published as written.** Between drafting and merging, two more platform-layer facts turned up
and a page that omitted them would have been out of date on the day it went out:

- **one library can be two.** sqlx4k is the Rust driver on Kotlin/Native and Xerial's `sqlite-jdbc`
  on the JVM behind one API, and they disagree about in-memory databases. A suite that ran on the
  native target alone met neither half (B-23);
- **a whole section on why the platform layer resists the treatment the stdlib takes.** The stdlib
  rows are a transcript — run one source twice, diff. The platform is not: an assertion has to go
  through the API the program uses rather than the syscall beneath it, and some of it cannot be
  settled hermetically at all, because curl reports its ordinary handshake failure with the same
  exception type ktor uses to refuse TLS outright (B-26).

That last section is the reason to read the page. Everything before it is a table other people could
produce; it is the part that says what to do about the half that matters.

**Two deviations from that repository's checklist, flagged in the pull request rather than skipped:**
no installation section, because the subject is a set of measurements and there are no coordinates to
copy; and 146 lines against a 70–110 guide, which is four tables and the closing section.
