---
id: B-19
title: "Set the four properties instead of working around them, and give musl a C++ runtime built for it"
status: open
priority: P1
size: M
stage: stage-6-static-binary
---

# B-19 — Set the four properties instead of working around them, and give musl a C++ runtime built for it

Route 1 of the musl question links and then segfaults with no output
([research-static-binary §1.6](../research/research-static-binary.md)). It got there by assembling
a directory tree two levels deep to satisfy a relative crt path, and by shimming three archives —
one of which, a **glibc-built** `libstdc++.a` linked against musl's libc, cannot possibly be right.

Reading the distribution afterwards (§1.6a) shows most of that was unnecessary. The flags come from
four places, and three of them are properties:

| Flags | Source | Overridable |
|---|---|---|
| `-lresolv -lm -lpthread -lutil -lcrypt -lrt` | the `platform.posix` klib's manifest | **no** |
| `-lstdc++ -ldl -lm -lpthread`, `--gc-sections` | `linkerKonanFlags.linux_x64` | yes |
| `-lgcc -lgcc_s -lc` | global `linkerGccFlags` | yes |
| the crt paths | `targetSysRoot.linux_x64` + `libGcc.linux_x64` | yes |

- **The decision and its reason.** Redo the experiment by setting `libGcc.linux_x64` and
  `linkerKonanFlags.linux_x64` alongside `targetSysRoot.linux_x64`, and take `libstdc++.a` out of an
  Alpine image, where `g++` builds it against musl. That is one shim replaced by the real thing and
  two work-arounds replaced by the settings they were working around — the smallest change that
  addresses what was actually measured.
- **`zig cc` is the fallback, not the first move**, and the earlier version of this item had that
  backwards. It was carried over on the brief's authority; the justification written under it — that
  zig brings a musl-built C++ runtime — is at best imprecise, because zig ships **libc++** and the
  link line asks for `-lstdc++` by name, so that flag would still need mapping. What zig genuinely
  offers is removing sysroot assembly altogether and cross-linking `linuxArm64` from an x86 host.
  Worth reaching for if Alpine's `libstdc++.a` does not fix the segfault, not before.
- **The one unoverridable source is the ticket.** `platform.posix` hardcodes `-lcrypt -lresolv
  -lutil` in a klib manifest for every Linux program, whether or not anything calls them — which is
  the same fact B-18 works around with `--as-needed`. Whatever this item's outcome, that sentence is
  filable on its own, with §1.2's measurement behind it.
- **Does not cover** `linuxArm64`. One architecture answers the question; arm64 adds a cross-link to
  a problem not yet solved natively.

- AC: either `probe-musl` prints `probe=started … dns-lookup=ok … probe=finished` inside a `scratch`
  image — in which case its size and cold start go into §1.7 beside the current numbers — or the
  failure names a symbol, a flag or a file, and that goes into a KT ticket with a link to the log.
  Either way `experiments.sh` carries the property-setting version, so the next person does not
  re-derive the directory layout.
- Anchors: `docs/research/static-probe/experiments.sh`,
  `docs/research/static-probe/build.gradle.kts`, `docs/research/research-static-binary.md`

Settles hypotheses H3 and H4 of the research; H2 as a side effect, if the link line gets dumped.
