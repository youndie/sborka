---
id: research-static-binary
title: A Kotlin/Native binary in a scratch image — what it needs, and what stops it
type: research
status: active
date: 2026-09-11
---

# Research: a Kotlin/Native service in an image with nothing in it

Go's deployment story is `FROM scratch` and a small image; a Kotlin/Native binary links glibc
dynamically, so the honest base today is `distroless/cc`. The brief asked whether that gap is the
linker's configuration or the runtime's design, and what it costs to close it
([source-brief-static-binary.md](source-brief-static-binary.md)).

The answer is that `scratch` is not reachable today, and the reason is further from the linker flag
than expected: `-static` against glibc does not link at all, and the musl route links only after
three archives the toolchain names unconditionally are shimmed — and then segfaults before printing
a line. Nothing in the standard `-linker-option` surface fixes that; it is
`linkerKonanFlags`, and that makes it an upstream question rather than a build-file one.

The useful finding is a different one, and it ships. Of the ten shared libraries a Kotlin/Native
binary declares, **six supply not one symbol it imports** — and one of those six, `libcrypt.so.1`,
is why both of this portfolio's native services carry a hand-written `COPY` line from the builder
image, and why their Dockerfiles carry a paragraph about matching the builder's glibc to the
runtime's. One linker option removes the declaration, the copy, and the whole hazard: the same
binary then starts in `gcr.io/distroless/cc-debian13` with nothing copied beside it. That is
measured below, not argued.

This document records **verified facts** (measured on a Linux host on 2026-09-11, by the scripts
named beside them), **decisions**, **deviations from the brief**, and **hypotheses with the
milestone that settles them**. The probe and the experiment script are in
[`static-probe/`](static-probe/); the full output of the run these numbers come from is
[`results/2026-09-11-linuxX64.txt`](static-probe/results/2026-09-11-linuxX64.txt).

---

## 1. Verified facts

### 1.1 The subjects, and which the brief excludes

The brief puts any service using `ktor-client-curl` out of scope: libcurl is a dynamic dependency by
design. Reading the build files for that dependency:

| Subject | In scope | Why |
|---|---|---|
| tracy `:server` | **yes** | curl is in `:agent`, and `:server` does not depend on it |
| katcher `:server` | **yes** | no curl anywhere in the repository |
| razves `:cli` | **yes** | no curl; a CLI rather than a service, which matters for RQ2's DNS question |
| metrik `:server` | no | `ktor-client-curl` on native targets — `metrik/server/build.gradle.kts` |
| shildik `:distribution` | no | depends on `:auth-google`, which uses `ktor-client-curl` |
| metrik `:cli`, shildik `:cli`, shildik `:oidc-auth-server`, tracy `:agent` | no | same dependency |
| telek, booblik | n/a | telek is a library with no binary; booblik's native module is a client library |

**Consequence — the exclusion is not a technicality, it is most of the portfolio.** Three of the
eight native artefacts qualify, and the two that do not are excluded by a dependency taken for a
reason this portfolio has already written down: `ktor-client-cio` has no TLS on Kotlin/Native. So
even a working `scratch` recipe would today apply to two services out of four.

### 1.2 What the binaries actually need — RQ0

`readelf -d`, `nm -D --undefined-only` and a symbol-by-symbol check of which `NEEDED` library
supplies anything, run on binaries built on the Linux box.

| Binary | Size | `NEEDED` | glibc symbols above the 2.2.5 floor |
|---|---|---|---|
| tracy `:server` linuxX64 | 13 863 696 | 10 | `epoll_create1@2.9`, `eventfd@2.7`, `memcpy@2.14`, `pthread_setname_np@2.12`, **weak** `__cxa_thread_atexit_impl@2.18` |
| katcher `:server` linuxX64 | 15 575 184 | 10 | **the same five** |
| razves `:cli` linuxX64 | 3 183 440 | 10 | `memcpy@2.14`, `pthread_setname_np@2.12` |
| `static-probe` hello-world | 506 968 | 10 | `memcpy@2.14`, `pthread_setname_np@2.12` |
| tracy `:server` linuxArm64 | 12 788 616 | 9 | 159 symbols at `GLIBC_2.17`, one at 2.18, one at 2.25 |

The ten, identical on every x86-64 binary measured: `libresolv.so.2`, `libm.so.6`,
`libpthread.so.0`, `libutil.so.1`, `libcrypt.so.1`, `librt.so.1`, `libdl.so.2`, `libgcc_s.so.1`,
`libc.so.6`, `ld-linux-x86-64.so.2`.

And how many of the binary's 187 undefined symbols each of them supplies:

| Library | Symbols it provides to tracy's server |
|---|---|
| `libc.so.6` | the rest |
| `libgcc_s.so.1` | **13** — `_Unwind_Backtrace`, `_Unwind_GetIP`, `_Unwind_RaiseException`, … |
| `libm.so.6` | **2** — `log`, `pow` |
| `libcrypt.so.1`, `libresolv.so.2`, `libutil.so.1`, `librt.so.1`, `libdl.so.2`, `libpthread.so.0` | **0** |

**Consequence — the dependency set is the runtime's, not the application's.** Two unrelated
services and a CLI give a byte-identical `NEEDED` list, and a hello-world gives the same one. That
is what makes the tiny probe a fair subject for every linking question below — though not for the
glibc floor, which is a property of what the application calls.

**Consequence — the glibc floor is 2.18 and it is a weak symbol.** A service needs nothing newer
than `__cxa_thread_atexit_impl@GLIBC_2.18` (2013), and that one is weak, so the hard floor is
`memcpy@GLIBC_2.14`. Every base image anyone would consider is a decade past both. **The
"builder and runtime must be a matched glibc pair" paragraph in tracy's and katcher's Dockerfiles
is therefore not about the binary at all** — see §1.3.

**Consequence — `libgcc_s` is the one dependency that cannot be argued away.** Thirteen `_Unwind_*`
symbols: Kotlin/Native's exception handling is the C++ unwinder. It is why `distroless/base` is not
a candidate (§1.4) and why a static link has to carry the unwinder with it.

*(Not this brief's question, but visible in the same command: tracy's binary is 13 863 696 bytes
unstripped and 10 241 264 stripped — 26 %. Size is razves' brief, and §4 says why that number is
mentioned here anyway.)*

### 1.3 The `libcrypt.so.1` line in two Dockerfiles is a dependency nothing calls

Both shipped native Dockerfiles carry

```
COPY --from=build /usr/lib/x86_64-linux-gnu/libcrypt.so.1 /usr/lib/x86_64-linux-gnu/
```

and a comment explaining that the builder's glibc must be no newer than the runtime's, with the
failure it once produced: `GLIBC_2.38 not found`.

Reproduced, and then explained:

| Fact | How |
|---|---|
| the binary imports **zero** symbols from `libcrypt` | §1.2 |
| copying Ubuntu 24.04's `libcrypt.so.1` (glibc 2.39) into `distroless/cc-debian12` (glibc 2.36) reproduces `version 'GLIBC_2.38' not found` exactly | `experiments.sh`, base-image matrix |
| without the copy, the binary does not start either: `error while loading shared libraries: libcrypt.so.1: cannot open shared object file` | same |
| linking the same source with `-Wl,--as-needed` drops `libcrypt.so.1`, `libresolv.so.2` and `libutil.so.1` from `NEEDED` — seven entries instead of ten | same |
| the `--as-needed` binary then starts in `gcr.io/distroless/cc-debian13` **with nothing copied beside it** and resolves a hostname | same |

**Consequence.** The hazard those Dockerfile paragraphs describe is real and is entirely
self-inflicted: it exists because the link line names a library the program does not use, the
runtime image does not carry it, and the workaround drags a glibc-version-coupled file across from
the builder. One linker option removes all three. This is D1, and it is the brief's shippable
outcome regardless of what happens to `scratch`.

**Done, 2026-09-11 (B-18).** The option is in `sborka.kmp`, on Linux native executables only — not
in `sborka.native-service` as first planned, because no repository in the portfolio applies that
convention yet. On `stand/native-service`, the one module that links an executable through the
conventions, it takes `NEEDED` from ten entries to **six** — `libcrypt`, `libresolv`, `libutil` and
`librt` all go — and the binary runs in `gcr.io/distroless/cc-debian13` with nothing copied beside
it. tracy and katcher get it when they take a sborka release: B-22.

**Why `--as-needed` drops three of the six and keeps `libpthread`, `librt` and `libdl`:** because
they are named twice. `-lresolv -lutil -lcrypt` appear only in the `platform.posix` manifest, and
`-ldl -lm -lpthread` appear there *and* again in `linkerKonanFlags.linux_x64`, which the toolchain
emits after the point the option covers (§1.6a). `librt` is the odd one and is not explained by
that reading. This is read out of the distribution, not confirmed against a dumped link line, and
it costs nothing either way: every glibc since 2.34 ships those as stubs and every candidate base
image has them.

### 1.4 Which base image each variant starts in — RQ1

`experiments.sh`, base-image matrix. "result" is what the container printed.

| Base | default link | `--as-needed` link |
|---|---|---|
| `gcr.io/distroless/cc-debian13` | **fails**: `libcrypt.so.1: cannot open shared object file` | **works**: `probe=started hosts-file-lookup=ok dns-lookup=ok read-file=ok` |
| `gcr.io/distroless/base-debian13` | fails: `libcrypt.so.1` | **fails**: `libgcc_s.so.1: cannot open shared object file` |
| `gcr.io/distroless/static-debian13` | fails: `exec /probe: no such file or directory` | same |
| `scratch` | fails: `exec /probe: no such file or directory` | same |

**Consequence — the brief's RQ1 green criterion is wrong, and in a way worth writing down.** It
predicted "base works, static and scratch fail on a missing loader". The loader half is exactly
right and is the control it was meant to be. The `base` half is not: `distroless/base` has glibc
but no `libgcc_s`, and a Kotlin/Native binary needs the unwinder. **`distroless/cc` is the floor,
not a convenience** — and the reason is C++ exception handling, not libc.

**Consequence — "fails on a missing loader" is the failure mode to recognise.** `exec /probe: no
such file or directory` names neither the loader nor the file that is actually missing; it is the
kernel refusing an ELF whose `PT_INTERP` does not exist. Anyone reading it as "the binary was not
copied into the image" will spend an afternoon there.

### 1.5 Static glibc does not link — RQ2

`-linker-option -static` against the toolchain's own sysroot
(`x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2`, which does contain `libc.a`,
`libpthread.a` and 33 other archives):

```
LINK FAILED — 7 distinct undefined symbols
  __libc_setup_tls   __syscall_error        _dl_cpuclock_offset  _dl_init_static_tls
  _dl_pagesize       _dl_stack_flags        _dl_wait_lookup_done
```

**Consequence — the prediction was right about the colour and wrong about the stage.** The brief
expected "builds but DNS fails", NSS under static glibc being the known trap, and asked for a test
that includes a real name lookup. The binary never gets that far: these are glibc-internal symbols
that a complete static glibc provides out of `libc.a`'s loader-support objects, and this sysroot's
does not. Seven symbols is under the brief's kill line of ten, which makes it a reportable list
rather than a dead end — but it is a list about a **sysroot's completeness**, not about the
Kotlin/Native runtime, and that is a different ticket from the one the brief anticipated.

The probe still carries the DNS test, and it is not wasted: it is what proves the `--as-needed`
binary of §1.4 resolves a hostname inside `distroless/cc`, and it is what any future static build
has to pass.

### 1.6 musl links, and the binary segfaults — RQ3, route 1

`-Xoverride-konan-properties=targetSysRoot.linux_x64=…`, in four steps, each of which failed
differently:

| Step | Result |
|---|---|
| point the property at `/usr/lib/x86_64-linux-musl` | `cannot open /usr/lib/x86_64-linux-musl/usr/lib/crt1.o` — the toolchain looks for crt files under `<sysroot>/usr/lib` and for `crtbegin.o` under `<sysroot>/../../lib/gcc/<triple>/8.3.0`. **A distribution's musl directory can never be used as-is**, whatever the property says |
| assemble a sysroot in that shape | `unable to find library -lcrypt`, `-lstdc++`, `-lgcc_s` — musl has none of the three under those names. §1.6a says where each one comes from, and it is not one place |
| shim the three: an **empty** `libcrypt.a` (§1.2 says nothing imports it), `libgcc_eh.a` as `libgcc_s.a`, and the toolchain's own **glibc-built** `libstdc++.a` | **links.** 398 840 bytes, `file` says "statically linked" |
| run it | **`rc=139`, segmentation fault, no output at all** |

And one more measured detail that says the link is not what it claims: the "statically linked"
binary still carries a `PT_INTERP` segment, so in `scratch` it fails with the loader error of §1.4
rather than running. A 173 054-byte image that cannot start.

**Superseded 2026-09-12 (B-19) — see §1.6b.** Every work-around in that table was one, and the
segfault was not about the C++ runtime at all.

**Consequence — the deliverable here is a flags list, not a symbol list.** The brief expected to
end with "the symbols the Kotlin/Native runtime takes from glibc", and that list would have been
the JetBrains ticket. What the experiment produces instead is a set of `-l` flags and a crt layout
that come from four different places — §1.6a — none of them reachable from `-linker-option`.

### 1.6a Where each of those flags actually comes from

Read out of the distribution rather than inferred from the failures, which is what the first
version of this section did and got wrong. `konan.properties` and the platform klib manifests of
`kotlin-native-prebuilt-macos-aarch64-2.4.10`:

| Flags | Source |
|---|---|
| `-lresolv -lm -lpthread -lutil -lcrypt -lrt` | **the `platform.posix` klib's own manifest** — `klib/platform/linux_x64/org.jetbrains.kotlin.native.platform.posix/default/manifest`, key `linkerOpts` |
| `-Bstatic -lstdc++ -Bdynamic -ldl -lm -lpthread --defsym __cxa_demangle=… --gc-sections` | `linkerKonanFlags.linux_x64` |
| `-lgcc --as-needed -lgcc_s --no-as-needed -lc …` | the **global** `linkerGccFlags` — note it already wraps `-lgcc_s` in `--as-needed` itself |
| `<sysroot>/usr/lib/crt1.o` and `<sysroot>/../../lib/gcc/x86_64-unknown-linux-gnu/8.3.0/crtbegin.o` | `targetSysRoot.linux_x64` and **`libGcc.linux_x64`, which is its own key** and is documented in the file as "targetSysroot-relative" |
| `ld.lld` | `linker.linux_x64` |

**Consequence — `-lcrypt` is not a target setting at all, it is part of the standard library.** It
travels in the `platform.posix` klib's manifest, which every Kotlin/Native program on Linux links,
which is why §1.2 finds the identical ten-entry `NEEDED` list on two servers, a CLI and a
hello-world. Nothing about a repository's build can opt out of it, and `--as-needed` (§1.3) works by
letting the linker discard what the flag asked for after seeing that nothing referenced it.

**Consequence — the crt gymnastics of §1.6 were unnecessary.** `libGcc.linux_x64` is a property with
its own key; assembling a directory tree two levels deep to satisfy a relative path was working
around a setting that could have been set. That is the cost of reading a link failure instead of
the file that produced it, and it is the correction this section exists for.

**Consequence — the upstream ask is now precise.** Not "let a target's libc flags be replaced": the
target's own flags *are* replaceable, one key at a time. The ask is that **`platform.posix` should
not hardcode a libc's library names in a klib manifest**, because that is the one of the four that
no property override reaches and the one that names `-lcrypt`.

**Consequence — route 2 was not tried, and after §1.6a it is no longer the obvious next step.**
`zig cc` is the brief's second route and it was carried into the backlog on the brief's authority
rather than on evidence. What §1.6a shows is that three of the four obstructions are properties
that can simply be set, and the fourth — the C++ runtime — is answered more directly by a
musl-built `libstdc++.a`, which Alpine's `g++` produces and which replaces exactly the one shim
known to be wrong. zig's argument is a different and weaker one: it would remove sysroot assembly
altogether and cross-link `linuxArm64` from an x86 host. It is a fallback, not the lever. B-19 was
rewritten accordingly.

**Hypothesis for the segfault, with its address:** the `libstdc++.a` shim is glibc-built and was
linked against musl's libc, which is the one shim of the three that cannot be right. Settled by
retrying with a musl-built `libstdc++.a` — Alpine's `g++` builds one — which is B-19.

### 1.6b Setting the properties instead: it links cleanly, and then hangs

Redone with the sysroot taken from an `alpine:3.21` image — where `g++` builds `libstdc++.a` against
musl — and with `libGcc.linux_x64`, `linkerGccFlags` and `linkerKonanFlags.linux_x64` set rather
than worked around. Full output:
[`results/2026-09-12-musl.txt`](static-probe/results/2026-09-12-musl.txt).

**It links with no shims at all.** Alpine ships `libcrypt.a`, `libresolv.a`, `libutil.a` and
`librt.a`, which is what makes this possible: those four are named by `platform.posix`'s manifest,
no property overrides them, and a sysroot missing them could not have been rescued from a build file.

**And a fifth source turned up, read out of the linker's real argv** rather than inferred — a shim
in place of `linker.linux_x64` recorded what `ld.lld` was given:

```
 7  -dynamic-linker
 8  /lib64/ld-linux-x86-64.so.2
23  -static
```

`-dynamic-linker` pointing at **glibc's** loader, emitted before `-static`, regardless of the
sysroot, and in none of the properties. So the first attempt's binary declared an interpreter, the
kernel handed a static musl program to glibc's `ld.so`, and `ld.so` relocated it. **That is the
segfault**, and it was never the C++ runtime that §1.6 suspected.

`linkerOpts("--no-dynamic-linker")` — the only place it can be undone — produces a real static
binary: **430 904 bytes, no `PT_INTERP`, no `NEEDED`**. It does not crash. It **hangs, before its
first `println`**, so the Kotlin/Native runtime does not finish starting against musl.

**Consequence — that is where this stops, and it is a reportable place.** Everything up to `main` is
now accounted for by name: two hardcoded sources that no property reaches, and a runtime start-up
that does not complete. §2 D3's ticket has a body.

**Consequence — the hypothesis in §1.6 was wrong and the correction is the useful part.** The
glibc-built `libstdc++.a` shim looked like the one thing that could not be right, and replacing it
with a musl-built one changed nothing about the crash. A plausible explanation that survives because
nobody tests it is how a wrong suspicion gets written into a document; this one was tested.

### 1.7 What the image would buy — RQ5

| Image | Size |
|---|---|
| `debian:13-slim` | 29 803 103 |
| `gcr.io/distroless/cc-debian13` | 10 643 700 |
| `gcr.io/distroless/base-debian13` | 9 446 621 |
| `gcr.io/distroless/static-debian13` | 825 807 |
| tracy's server image as it ships, on `cc-debian13` | 15 192 364 — of which the binary is 13 863 696 |

**Consequence — the prize is 10.6 MB, and what looked like a cheaper one is not cheap.** Moving
tracy from `distroless/cc` to `scratch` would remove 10.6 MB of a 15.2 MB image. Stripping the
binary removes 3.6 MB and needs one flag — and **costs every native crash report**, which is why
B-20 was rejected: a Kotlin/Native stack trace prints `kfun:` names that live **only** in the symbol
table (123 of them in sborka's stand binary; 0 after `strip`, as symbols *and* as strings), and
katcher has no native mapping type to recover them from. The size is real and the price is a
production crash that arrives as addresses. Recorded here as available, not as recommended.

**Not measured: cold pull-plus-start on a k0s node with an empty cache.** That is the number the
brief said the whole exercise was for, and there is no variant that both runs and is smaller, so
there is nothing to compare against the current image. It becomes measurable the day §1.6 produces
a binary that starts; until then measuring it would be measuring the current image against itself.

### 1.8 RQ4 has nothing to measure

Runtime cost — RSS, throughput, p95 — was to be measured "for every variant that passed RQ2/RQ3".
None did. The musl binary does not reach `main`; the static-glibc one does not link. The allocator
question (`-Xallocator` against musl's malloc) that the brief flagged as the risk cannot be asked
of a binary that does not run.

---

## 2. Decisions

### D1. `--as-needed` goes into `sborka.native-service`, and the reference Dockerfile loses its `COPY` line

The brief's shippable outcome, and it does not depend on `scratch` ever working. Measured in §1.3
and §1.4: the option drops three `NEEDED` entries the program does not use, and the binary then
starts in `distroless/cc-debian13` with nothing copied beside it.

What it removes besides a line: the glibc pairing hazard. Today a Kotlin/Native image is only
correct if the builder's glibc is no newer than the runtime's, because a file is dragged across
between them — and the failure when it is wrong is the container exiting with `GLIBC_2.38 not
found` before any of the application's logging has run. With no copy there is no pairing.

The convention is the right place rather than each repository: two repositories have this line
today, they wrote it twice, and `NativeImageReference` already exists to stop exactly that.

### D2. `distroless/cc` is the honest base image, and the reason is the unwinder

Not "glibc", which is the usual shorthand. `distroless/base` has glibc and fails, because
Kotlin/Native's exception handling imports thirteen `_Unwind_*` symbols from `libgcc_s` (§1.2,
§1.4). Anything that makes `base` or `static` work has to carry the unwinder, statically or
otherwise — which is also why the musl attempt had to shim `-lgcc_s` before it would link.

### D3. No `image { base = scratch }` option in sborka, and no KT ticket yet

The brief's green deliverable is not earned: nothing produces a binary that starts in `scratch`.

Nor is the red deliverable, quite. The brief's red was "a KT ticket with the symbol list", and §1.6
shows the symbol list is not what the experiment produces — it produces a segfault with no
diagnostic and four sources of link flags, three of which are properties anyone can set (§1.6a).
The one that is not is `platform.posix`'s manifest, and *that* is the ticket: a klib that hardcodes
`-lcrypt -lresolv -lutil` for every Linux program whether or not anything calls them. But a ticket
filed today would also have to say "and then it segfaults for a reason we did not chase", which is
how a ticket gets closed as needing more information. B-19 is what makes it filable.

### D4. The probe is a hello-world, and that is justified rather than convenient

`static-probe` links the Kotlin/Native runtime and nothing else, and §1.2 measured that this gives
the same ten `NEEDED` entries as two real services and a CLI. Every linking question in §1.5 and
§1.6 is therefore asked in three seconds rather than three minutes. The probe is **not** a fair
subject for the glibc floor — a server additionally imports `epoll_create1`, `eventfd` and
`__cxa_thread_atexit_impl` — and the floor numbers in §1.2 come from the real binaries and say so.

Same shape as [`parity-probe/`](parity-probe/) and [`probe/`](probe/): a standalone Gradle build
under `docs/research/`, outside sborka's own, so that this repository's conventions are not standing
between the question and the answer.

### D5. The experiments live in one script that runs on a Linux host

[`static-probe/experiments.sh`](static-probe/experiments.sh) does the whole sequence — four link
modes, the assembled musl sysroot, the base-image matrix — and prints a report. Everything in §1 is
in [its output](static-probe/results/2026-09-11-linuxX64.txt), committed with the host, the glibc
and the JDK it ran on.

It exists because half of this brief is arrangements rather than findings: the sysroot has to sit
exactly two directories below the gcc auxiliaries, three archives have to be shimmed, and each of
those was discovered by a failure that named something else. A reader who has to rediscover them
will not repeat this.

---

## 3. Deviations from the brief

1. **`distroless/base` does not work** (§1.4). RQ1's pre-declared green said it would. The blocker
   is `libgcc_s`, not glibc.
2. **RQ2 fails at link, not at DNS** (§1.5). The predicted NSS trap is real and untested, because
   nothing gets far enough to test it.
3. **RQ3's deliverable is a flags list, not a symbol list** (§1.6). The brief's red outcome assumed
   the obstruction would be the Kotlin/Native runtime's use of glibc; it is the toolchain's
   unconditional link line.
4. **RQ3 route 2 was not run** (§1.6) — `zig` is not installed on the available Linux host. A gap,
   stated as one.
5. **RQ4 is unanswerable** (§1.8): no variant passed RQ2 or RQ3.
6. **RQ5's headline number was not measured** (§1.7): cold pull-plus-start needs two images that
   both start.
7. **The exclusion list is larger than the subject list** (§1.1). Three of eight native artefacts
   are in scope; `ktor-client-curl` takes out both of metrik's and shildik's shipped service.
8. **The brief's non-goal collides with its goal** (§1.7). Size reduction was explicitly out of
   scope as razves' subject, and stripping — one flag, working today — is worth a third of what the
   entire `scratch` exercise would win.

---

## 4. Hypotheses, each with the milestone that settles it

**H1 — dropping the `COPY libcrypt` line breaks nothing in tracy and katcher.** §1.3 and §1.4 are
measured on the probe, whose `NEEDED` list is identical to theirs; the servers were not rebuilt with
`--as-needed`. Settled by B-18, which is the change itself: if a real service's link drops a library
the probe's does not, the build says so.

**H2 — mostly closed by reading (§1.6a).** `-ldl -lm -lpthread` are named a second time in
`linkerKonanFlags.linux_x64`, after the point `--as-needed` covers, which is why they survive it.
`librt` is not explained by that and nothing depends on the answer; confirming it needs the link
line the toolchain generates, which B-19 has to look at anyway.

**H3 — the segfault is the glibc-built `libstdc++.a` on musl libc.** It is the one shim of the
three that cannot be right (§1.6). Settled by B-19: set `libGcc.linux_x64` and
`linkerKonanFlags.linux_x64` properly instead of working around them, and take `libstdc++.a` from
an Alpine image, where `g++` builds it against musl. Prediction: it links, and either runs — in
which case RQ4 and RQ5 become answerable — or fails at a point that can be named in a ticket.
`zig cc` is the fallback if that fails, not the first move (§1.6).

**H4 — the static-glibc seven are a sysroot completeness problem, not a Kotlin/Native one.**
`__libc_setup_tls`, `_dl_pagesize` and the rest are provided by a complete glibc `libc.a`. Settled
by repeating §1.5 against a full modern static glibc — which needs the crt-layout work of §1.6, so
it comes free with B-19.

---

## 5. Risks

**Risk 1 — D1 is applied and a repository's image silently starts needing a library again.** A
future dependency with a real `libcrypt` or `libresolv` call would be dropped from `NEEDED` by
`--as-needed` only if unused, so this is safe by construction — but the *check* that it is safe is
`readelf -d` on the produced binary, not an argument. **Mitigated 2026-09-12 (B-21):**
`stageNativeImage` prints the list at `lifecycle` and writes it beside the binary, so a change
appears in the log of the build that introduced it. It is not a gate, and on a Mac it reports that
it could not look rather than reporting nothing.

**Risk 2 — the shimmed musl result is mistaken for "musl nearly works".** It links and segfaults,
and a link that succeeds is the most persuasive kind of false progress. Mitigation: §1.6 records
the `PT_INTERP` that should not be there and the three shims by name, one of which is known to be
wrong. The binary is not committed.

**Risk 3 — these numbers are read as facts about Kotlin/Native rather than about one toolchain
version.** Everything here is Kotlin 2.4.10, its bundled LLVM 21 and its
`gcc-8.3.0-glibc-2.19-kernel-4.9-2` sysroot. Mitigation: the results file names all three, and
`experiments.sh` re-runs in minutes.

**Risk 4 — a red brief is read as "nothing came of it".** The deliverable people will actually feel
is D1, which is unrelated to `scratch` and was found on the way. Mitigation: B-18 is separated from
the rest and carries no dependency on the static work.

---

## 6. Code anchors

| What | Where |
|---|---|
| the probe, the experiment script and the run these numbers come from | [`docs/research/static-probe/`](static-probe/) |
| the convention that would carry `--as-needed` | [`build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts`](../../build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts) |
| the reference Dockerfile that would lose its `COPY` line | [`build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/internal/NativeImageReference.kt`](../../build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/internal/NativeImageReference.kt) |
| the two shipped Dockerfiles the line lives in today | `tracy/server/Dockerfile`, `katcher/server/Dockerfile` |
| the dependency that excludes half the portfolio | `metrik/server/build.gradle.kts`, `shildik/auth-google/build.gradle.kts` |
| the sibling brief, taken first | [research-parity](research-parity.md) |

---

## 7. What happens next

The order is in [backlog.md](../../backlog.md), stage `stage-6-static-binary`. B-18 first and on its
own: it is the only item here that ships something, it takes a line, and it is independent of
whether `scratch` is ever reached. B-19 — route 2 — is what decides whether this brief ends in a
recipe or in a ticket, and it is the last engineering this box pays for.
