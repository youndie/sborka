---
id: B-19
title: "Set the four properties instead of working around them, and give musl a C++ runtime built for it"
status: done
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

## Done, 2026-09-12 — it links cleanly, and the segfault was never what §1.6 suspected

Redone with the sysroot from an `alpine:3.21` image and with `libGcc.linux_x64`, `linkerGccFlags`
and `linkerKonanFlags.linux_x64` set rather than worked around. Output:
[`results/2026-09-12-musl.txt`](../research/static-probe/results/2026-09-12-musl.txt); research
[§1.6b](../research/research-static-binary.md).

**No shims were needed.** Alpine ships `libcrypt.a`, `libresolv.a`, `libutil.a` and `librt.a`, and
that is the thing that makes the route work at all: those four come from `platform.posix`'s klib
manifest, which no property overrides. The first attempt had to fake them because Debian's musl-dev
does not ship them.

**A fifth source, found by looking rather than guessing.** A shim in place of `linker.linux_x64`
recorded the linker's real argv: `-dynamic-linker /lib64/ld-linux-x86-64.so.2` — **glibc's** loader —
emitted before `-static`, regardless of the sysroot, and reachable from no property. The first
attempt's "statically linked" binary therefore declared an interpreter, and the kernel handed a
static musl program to glibc's `ld.so`. **That was the segfault.**

**The hypothesis this item was built on was wrong, and that is the most useful line here.** H3 said
the glibc-built `libstdc++.a` shim was the one that could not be right. A musl-built one changed
nothing about the crash. It looked like the obvious culprit, it was plausible, and it was not tested
until now — which is exactly how a wrong suspicion gets written into a research document and quoted
for a year.

**Where it now stops.** With `--no-dynamic-linker` the binary is genuinely static — 430 904 bytes,
no `PT_INTERP`, no `NEEDED` — and it **hangs before its first `println`**: the Kotlin/Native runtime
does not finish starting against musl. Everything up to `main` is accounted for by name, so the
ticket has a body it did not have this morning. Filing it is B-24.

**Two things about running this that cost time and belong in anyone's hands who repeats it.** An
untimed run of the hung binary leaves a stuck process behind every invocation — `experiments.sh` now
wraps it in `timeout`, and two of them had to be cleaned off the box by hand. And `pkill -f
probe-musl` would match the very ssh command issuing it; `pkill -x probe-musl.kexe` is the form that
does not shoot its own session.

**zig was not needed and is not next.** It was carried into the first version of this item on the
brief's authority; the two obstructions were hardcoded flags, not a missing toolchain.
