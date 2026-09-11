---
id: B-19
title: "Try zig cc as the linker — the route that decides whether this brief ends in a recipe or a ticket"
status: open
priority: P1
size: M
stage: stage-6-static-binary
---

# B-19 — Try `zig cc` as the linker — the route that decides whether this brief ends in a recipe or a ticket

Route 1 of the musl question got further than expected and stopped somewhere useless
([research-static-binary §1.6](../research/research-static-binary.md)): overriding `targetSysRoot`
needs a sysroot assembled in a layout the toolchain hardcodes, then fails on three archives
`linkerKonanFlags` names unconditionally — `-lcrypt`, `-lstdc++`, `-lgcc_s`. Shim all three and it
**links**: 398 840 bytes, `file` says "statically linked". Run it and it segfaults with no output at
all, and it still carries a `PT_INTERP` segment it should not have.

One of those three shims cannot be right: `libstdc++.a` was taken from the toolchain's **glibc**
sysroot and linked against musl's libc. `zig cc` brings a musl-built C++ runtime with it, which is
exactly the piece route 1 had to fake.

- **The decision and its reason.** Install zig on the Linux box and put it in as the linker
  (`-linker-option` / `KONAN_LINKER`), as the brief's second route. Two outcomes and both are
  deliverables: a binary that starts in `scratch`, which makes RQ4 and RQ5 answerable at last; or a
  failure at a point that can be *named*, which is what the upstream ticket needs and what a
  segfault does not provide.
- **The upstream ask is already legible either way.** Not "the runtime uses glibc symbols", which
  is what the brief expected, but "let a target's libc flags be replaced without replacing the whole
  of `linkerKonanFlags`". §1.6 has the three flags and the crt layout; this item decides whether
  that ask comes with a working recipe attached.
- **This is the last engineering the box pays for.** The brief's kill criterion is three days and a
  linker; route 1 is spent. If this does not produce a running binary, the outcome is the ticket and
  a paragraph on the site saying what the honest base image is — which §1.4 and D2 already say.
- **Rejected: continuing to shim route 1.** The next shim would be a musl-built `libstdc++`, which
  means building one, which is what zig hands over for free.
- **Does not cover** `linuxArm64`. One architecture is enough to answer the question, and arm64 adds
  a cross-link to a problem that is not yet solved natively.

- AC: either `probe-musl` prints `probe=started … dns-lookup=ok … probe=finished` inside a `scratch`
  image — in which case the image size and the cold start go into §1.7 beside the current numbers —
  or the failure is recorded with the symbol, flag or file it names, and that goes into a KT ticket
  with a link to the log.
- Anchors: `docs/research/static-probe/experiments.sh`,
  `docs/research/static-probe/build.gradle.kts`, `docs/research/research-static-binary.md`

Settles hypotheses H3 and H4 of the research.
