---
id: B-24
title: "File the two hardcoded sources upstream, with a reproduction that reaches main"
status: open
priority: P2
size: XS
stage: stage-6-static-binary
blocked_by: [B-19]
---

# B-24 — File the two hardcoded sources upstream, with a reproduction that reaches `main`

B-19 accounted for everything between `-linker-option -static` and the Kotlin/Native runtime's
start-up, which is what a ticket needed and did not have. Three findings, in the order a reader
needs them:

1. **`platform.posix`'s klib manifest hardcodes `-lresolv -lm -lpthread -lutil -lcrypt -lrt`** for
   every Linux program, whether or not anything imports them. No property reaches a klib manifest,
   and one of the six is why two Dockerfiles in this portfolio copied a library out of the builder
   image by hand (B-18). Measured: identical ten-entry `NEEDED` on two servers, a CLI and a
   hello-world, with symbols imported from three of them.
2. **`-dynamic-linker /lib64/ld-linux-x86-64.so.2` is emitted unconditionally**, before `-static`
   and regardless of `targetSysRoot`, so `-linker-option -static` cannot by itself produce a static
   binary. Reachable from none of `targetSysRoot`, `libGcc`, `linkerGccFlags`, `linkerKonanFlags`.
3. **With both worked around, the binary hangs during runtime start-up** against musl — before any
   user code runs.

- **The decision and its reason.** One ticket, in that order, with
  [`static-probe/`](../research/static-probe/) as the reproduction: it is a hello-world, it runs from
  one script, and it needs only docker and a JDK. The third finding alone would be closed as needing
  more information; the first two are small, precise and independently fixable, and they are what
  makes the third worth someone's afternoon.
- **Ask for the smallest thing that unblocks the route**, not for musl support: let a klib's
  `linkerOpts` be overridden, and let `-dynamic-linker` follow `-static`. Both are the kind of change
  that lands; "support musl" is not.
- **The first finding is worth filing even if the rest never moves.** Every Kotlin/Native Linux
  binary in the world declares six libraries it does not use, and at least one base image people
  actually deploy to does not carry one of them.
- **Ask before filing**, as this portfolio does for any issue in somebody else's tracker.
- **Does not cover** the hang's cause. Naming where it stops is this item; finding out why is not,
  and may not be worth it here at all.

- AC: an issue on youtrack with the three findings, the argv excerpt, and a link to the probe; its
  number written into research §2 D3.
- Anchors: `docs/research/static-probe/results/2026-09-12-musl.txt`,
  `docs/research/research-static-binary.md`, `docs/research/static-probe/experiments.sh`
