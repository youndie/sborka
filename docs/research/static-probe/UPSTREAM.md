# Where each finding goes, and the text to post

Checked against YouTrack on 2026-09-13. **Nothing here has been posted.** Two of the three findings
belong in existing tickets, and the third is the only one without a home.

| finding | destination | state as of 2026-09-13 |
|---|---|---|
| 1. `platform.posix` names libc's libraries in a klib manifest | comment on [KT-55643](https://youtrack.jetbrains.com/issue/KT-55643) | Bug, Open, 11 votes, 12 comments, created 2022-12-26 |
| 2. `-dynamic-linker` is emitted unconditionally | **new ticket** — nothing found in KT or the archived `kotlin-native` repo | — |
| 3. the runtime deadlocks against musl | comment on [KT-85658](https://youtrack.jetbrains.com/issue/KT-85658) | Bug, Open, 2 votes, created 2026-04-17, affects 2.3.0 / 2.3.20 / 2.4.0-Beta2 |

**Finding 3 does not go to [KT-38876](https://youtrack.jetbrains.com/issue/KT-38876).** That one is
the Alpine feature request: Feature, Open since 2020-05, 61 votes, and its last comment is from
2023-06-19 — the "updated" date is vote traffic. KT-85658 is the specific bug, opened 2026-04-17 out
of KTOR-9141, and it already names the mechanism: a `FUTEX_WAKE` lost after `FUTEX_REQUEUE_PRIVATE`
in GC thread coordination. On 2026-04-27 JetBrains wrote there: "I suggest we declare musl as
unsupported for now." That is the thread the evidence bears on.

Searches run before concluding finding 2 is unreported: `dynamic-linker`, `PT_INTERP`,
`Native static linking executable`, `linker-option -static`, `Native musl` — 15 results each,
nothing about the unconditional emission. The nearest are KT-43501 (static linking causes linker
errors, open, 2020) and the archived `JetBrains/kotlin-native#469` (closed 2017, answered "for Linux
we dynamically link with libc, as static linking would be waste of space").

All three texts cite one run:
[`results/2026-09-13-workaround-halves-and-gc.txt`](results/2026-09-13-workaround-halves-and-gc.txt).

## The order matters

1. **File finding 2 first.** It is the only new ticket, and its number is quoted in the other two —
   `KT-XXXXX` appears in the KT-55643 comment and beside `--no-dynamic-linker` in the KT-85658 one.
   Posting the comments first means editing them afterwards to add a number.
2. **Then merge `docs/the-ticket-and-its-reproduction`.** All three texts end in a link to the
   reproduction, and until the branch is merged `main` carries the script *with* the seven defects —
   a link that reproduces the defects instead of the findings is worse than no link. The clone line
   in [`TICKET.md`](TICKET.md) drops its `-b` at the same time.
3. **Then the two comments**, in either order.

Mentions are written as YouTrack logins (`@di.gerasimov`, `@aleksei.glushko`), because a full name
with a space does not resolve and notifies nobody. Both were checked against the comments they refer
to on 2026-09-13: the `posix.def` observation is the first comment on KT-55643 (2022-12-27), and
"a couple more incompatibility problems" is Aleksei Glushko on KT-85658 (2026-04-27).

---

## 1 → comment on KT-55643

> The `-Wl,--as-needed` workaround in this thread works, but it is being passed around — here and in
> KT-38876 — together with `-Xoverride-konan-properties=linkerGccFlags=-lgcc -lgcc_eh -lc`, as
> though the two were one cure. They are not, and separating them says where this bug lives.
>
> The same hello-world, Kotlin 2.4.10, linuxX64, linked four ways, `readelf -d`:
>
> | build | NEEDED |
> |---|---|
> | as the toolchain links it | resolv, m, pthread, util, **crypt**, rt, dl, gcc_s, c, ld-linux |
> | + the `linkerGccFlags` override only | resolv, m, pthread, util, **crypt**, rt, dl, c, ld-linux |
> | + `-Wl,--as-needed` only | m, pthread, rt, dl, gcc_s, c, ld-linux |
> | both | m, pthread, rt, dl, c, ld-linux |
>
> (`pthread`, `rt` and `dl` survive `--as-needed` because `--as-needed` judges at link time, and the
> toolchain links against its own glibc 2.19 sysroot, where those three are real libraries that do
> export symbols the binary imports. On a 2.34+ host they are stubs — the second table below counts
> both.)
>
> The property removes exactly one library — `libgcc_s`, the one `linkerGccFlags` contributes — and
> leaves `libcrypt` in place. It cannot do otherwise: the six this issue is about
> (`-lresolv -lm -lpthread -lutil -lcrypt -lrt`) come from `linkerOpts` in the `platform.posix`
> klib's manifest, as @di.gerasimov pointed at in the first comment on this issue, via `posix.def`,
> and
> `-Xoverride-konan-properties` names `konan.properties` keys — there is no key that names that
> list. So `--as-needed` is not one workaround among several; it is the only lever a user has.
>
> How much of that list is real, measured against the toolchain's own link-time sysroot (glibc 2.19)
> and against a host glibc 2.39, counting how many of the binary's 103 imported symbols each library
> actually exports:
>
> | library | glibc 2.19 | glibc 2.39 |
> |---|---|---|
> | libresolv | 0 | 0 |
> | libutil | 0 | 0 |
> | libcrypt | 0 | 0 |
> | librt | 1 (`clock_gettime`) | 0 |
> | libdl | 1 (`dladdr`) | 0 |
> | libm | 2 (`ceil`, `floor`) | 2 |
>
> From glibc 2.34 on, where librt, libdl and libpthread are stubs, five of the six supply nothing at
> all. `libcrypt` supplies nothing on either.
>
> What it costs on distroless, same probe, image bytes and what happens when it starts:
>
> | image | as linked | `--as-needed` | `--as-needed` + override |
> |---|---|---|---|
> | cc-debian13 | libcrypt.so.1 missing | runs, 10.8 MB | runs, 10.8 MB |
> | base-debian13 | libcrypt.so.1 missing | libgcc_s.so.1 missing | runs, 9.6 MB |
> | static-debian13 | `exec: no such file` | `exec: no such file` | `exec: no such file` |
> | scratch | `exec: no such file` | `exec: no such file` | `exec: no such file` |
>
> So the half of the recipe that does nothing for `libcrypt` is what gets you one image smaller, and
> the two smallest images are out of reach for an unrelated reason — the link command always carries
> `-dynamic-linker`, which I have filed separately as KT-XXXXX.
>
> Would you consider re-stating this issue as the general case — that a klib manifest's `linkerOpts`
> cannot be overridden, so `platform.posix` adds six libraries to every Linux binary and five of them
> are dead on any modern glibc? `libcrypt` is the one that breaks first, because distros dropped it
> soonest, but a fix aimed only at `libcrypt` leaves the mechanism and the other five.
>
> Everything above is one script: <REPO URL>

## 2 → the new ticket

**Title:** `Native: -dynamic-linker is emitted unconditionally on linux_x64, so -linker-option -static cannot produce a static binary`

**Type:** Bug **Subsystem:** Native **Affected versions:** 2.4.10

> **What happens.** Every `linux_x64` link command contains `-dynamic-linker
> /lib64/ld-linux-x86-64.so.2`, and it is emitted before the user's own linker arguments. Recorded
> by putting a shim in place of `linker.linux_x64` that writes `ld.lld`'s argv and execs the real
> linker:
>
> ```
>  1  --sysroot=<the sysroot>
>  7  -dynamic-linker
>  8  /lib64/ld-linux-x86-64.so.2      <- glibc's loader, whatever the sysroot is
> 23  -static
> ```
>
> **Why nothing in a build file can undo it.** The *path* is a property — `dynamicLinker.linux_x64`
> — so `-Xoverride-konan-properties` can point it elsewhere. The *emission* is not:
> [`GccBasedLinker.finalLinkCommands`](https://github.com/JetBrains/kotlin/blob/v2.4.10/native/utils/src/org/jetbrains/kotlin/konan/target/Linker.kt#L451-L452)
> adds `-dynamic-linker` and its value with no condition, twenty-nine lines above the
> `+linkerArgs` that brings in the user's own flags (line 481 of the same function).
> Neither `targetSysRoot`, `libGcc`, `linkerGccFlags` nor `linkerKonanFlags` reaches it.
>
> **What it costs.** A binary linked with `-linker-option -static` still carries a `PT_INTERP`. The
> kernel hands it to glibc's dynamic loader, which relocates a statically linked program as though
> it were dynamic; the result is a segfault with no output, which looks like a Kotlin/Native runtime
> fault and names nothing. Passing `--no-dynamic-linker` alongside `-static` removes the segment and
> produces a genuine static binary — no `PT_INTERP`, no `NEEDED`, 430 904 bytes for a hello-world.
> That workaround is only reachable by a user who has already read the linker's argv.
>
> This is the first of the things that keeps Kotlin/Native out of `gcr.io/distroless/static` and
> `scratch`, and I am not claiming it is the only one: with `--as-needed` the binary runs on
> `cc-debian13`, but the two images without a loader refuse it with `exec: no such file or directory`
> no matter what else is set. Behind this one there are more — `-static` against the toolchain's own
> glibc sysroot fails at link time with 7 undefined symbols (`__libc_setup_tls`, `_dl_pagesize`,
> `_dl_init_static_tls` and so on), and the musl route gets past the linker and stops in the runtime
> (KT-85658). Each is a separate obstruction; this issue is about the one that no setting can reach.
>
> **Suggested fix.** `linkerArgs` is a field of the same `LinkerArguments` receiver, so the minimal
> change is to skip the two lines when the user asked for a static link:
>
> ```kotlin
> if ("-static" !in linkerArgs) {
>     +"-dynamic-linker"
>     +dynamicLinker
> }
> ```
>
> A `STATIC_EXECUTABLE` in `LinkerOutputKind` would be the fuller answer — the enum has only
> `DYNAMIC_LIBRARY`, `STATIC_LIBRARY` and `EXECUTABLE` today — but the check above is enough to make
> `-linker-option -static` mean what it says.
>
> With this fixed the musl route gets as far as the runtime, and stops there for a different reason:
> see KT-85658.
>
> **Reproduction:** <REPO URL> — `./experiments.sh`, sections "musl (properties...)" and
> "base images". Linux only; it needs a JDK, docker and binutils, and fetches the ~1 GB toolchain on
> a first run.

## 3 → comment on KT-85658

> An independent reproduction that may be cheaper to debug than the Ktor one, because it has almost
> nothing in it.
>
> No Ktor, no curl, no HTTP, no gcompat, and Alpine is not the host: a hello-world — resolve a
> hostname, read a file, print four words — cross-linked on Debian x86_64 against a musl sysroot
> the script extracts from `alpine:3.21` (musl `libc.a` and crt files, and Alpine's own
> musl-built `libstdc++.a`), statically, with `--no-dynamic-linker` — which is needed because the
> link command carries `-dynamic-linker` unconditionally, filed as KT-XXXXX. Kotlin 2.4.10.
>
> **It hangs before its first `println`.** Under `strace -f`: 41 lines of output in total, not one
> `write(2)` among them, and at the kill three threads, all three in `FUTEX_WAIT`. Their names are
> "Main GC thread" and "GC Timer thread". So the deadlock does not need GC pressure or 1 MB payloads
> — no user code has run yet.
>
> **With `-Xbinary=gc=noop` the hang goes away**, as this issue says it should, and is replaced by a
> segfault at address 0, after ten syscalls, before the first `clone`:
>
> ```
> arch_prctl(ARCH_SET_FS, 0x243790)       = 0
> set_tid_address(0x243890)               = 74735
> brk(NULL)                               = 0x2e539000
> brk(0x2e53b000)                         = 0x2e53b000
> mmap(0x2e539000, 4096, PROT_NONE, ...)  = 0x2e539000
> mmap(NULL, 4096, PROT_READ|PROT_WRITE, ...)  = 0x7dc782226000
> mmap(NULL, 77824, PROT_READ|PROT_WRITE, ...) = 0x7dc782213000
> --- SIGSEGV {si_signo=SIGSEGV, si_code=SEGV_MAPERR, si_addr=NULL} ---
> +++ killed by SIGSEGV (core dumped) +++
> ```
>
> Control, because "still broken with the GC off" and "the switch does nothing here" look identical
> from one run: the same `-Xbinary=gc=noop` on the ordinary glibc build of the same program exits 0
> and prints normally.
>
> I cannot tell from here whether that second failure is the runtime or the sysroot the script extracts,
> so please read it as a hint rather than a finding — it is consistent with @aleksei.glushko's
> "a couple more incompatibility problems" above. The deadlock is the solid part: it reproduces on
> x86_64, on a glibc host, with no gcompat and no libraries, in a program that has not done
> anything.
>
> Reproduction: <REPO URL> — `./experiments.sh`, section "the musl hang against KT-85658".
