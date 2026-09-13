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

## The patch, and why it does not replace the ticket

`upstream/kotlin-no-dynamic-linker-for-static.patch` is finding 2 fixed against
`JetBrains/kotlin` master (`3dc21510c`): eight lines in `GccBasedLinker.finalLinkCommands` and a
`LinkerStaticExecutableTest` beside the module's existing tests.

**It does not replace the ticket.** Their [contribution
checklist](https://github.com/JetBrains/kotlin/blob/master/docs/contributing.md) opens with "You
provided the link to the related issue(s) from YouTrack", and their commit rules say significant
commits must mention the issue. So the ticket comes first either way; the patch is what turns it
from a report into a pull request.

**What was verified, on the Linux box** — the unit tests, then a compiler built from the patched
source and used in anger. Transcript:
[`results/2026-09-13-patched-compiler.txt`](results/2026-09-13-patched-compiler.txt).

* `./gradlew :native:kotlin-native-utils:test` — 10 classes, 24 tests, 0 failures, with the patch;
* the new test **fails without the patch**, printing the argv with `-dynamic-linker` still in it,
  and its sibling — an ordinary executable *does* get an interpreter — passes either way, so the
  change is not over-reaching;
* **the compiler was built** (`:kotlin-native:dist :kotlin-native:distPlatformLibs`, 4m34s) and the
  probe compiled with it, passing `-linker-option -static` and **nothing else** — no
  `--no-dynamic-linker` anywhere. The same `master` built twice, the patch the only variable:

  | 2.5.255-SNAPSHOT from this tree | `PT_INTERP` | exit | output |
  |---|---|---|---|
  | without the patch | 1 | 139 | none — segfault |
  | with the patch | 0 | 0 | `hosts-file-lookup=ok dns-lookup=ok read-file=ok` |

* **no regression on the ordinary path**: the same probe built normally through Gradle with
  `kotlin.native.home` pointed at the patched distribution still gets its interpreter and all ten
  `NEEDED` entries, and runs;
* **a real project of ours**: `platform-probe` — ktor-network, coroutines, kotlinx-io — compiles,
  links and passes its five `linuxX64Test` cases under the patched compiler.

**A real service, on the tag it actually uses.** Our projects are on Kotlin 2.4.10, and none of them
compiles against a 2.5 snapshot at all: the `kotlinx.serialization` plugin shipped with Gradle plugin
2.4.10 fails in `SerializerClassPreLowering` against a 2.5 back-end, and `hub-backend` dies the same
way on a ktor `@Resource`. Both failures reproduce with the **unpatched** 2.5 build and neither
happens on stock 2.4.10, so that is the version jump and not this change.

So the patch was applied to `v2.4.10` as well — the same three lines, same anchor, no adjustment —
and the distribution built from the tag (8m16s). With it,
[`katcher`](https://github.com/youndie/katcher) — ktor, sqlx4k, kotlinx.serialization, KSP —
builds its `linuxX64` server green, and the binary is not just linked but working:

| | bytes | `PT_INTERP` | `NEEDED` |
|---|---|---|---|
| patched 2.4.10 | 15 575 200 | 1 | m, pthread, rt, dl, gcc_s, c, ld-linux |
| stock 2.4.10 | 15 575 152 | 1 | the same seven |

48 bytes apart, and the patched one runs its migrations, starts ktor in 0.015 s, listens on 8080 and
answers `401` to an unauthenticated `GET` — which is what it is supposed to do. Transcript:
[`results/2026-09-13-katcher-on-patched-2.4.10.txt`](results/2026-09-13-katcher-on-patched-2.4.10.txt).

**A note for whoever builds this tree next:** the first attempt died in dependency resolution with
"Network is unreachable". The box has AAAA records for `cloudfront.net` and no IPv6 default route,
and Gradle's HTTP client does not fall back the way `curl` does.
`-Dorg.gradle.jvmargs="… -Djava.net.preferIPv4Stack=true"` fixes it.

**What the patch deliberately leaves alone:** the `-Bdynamic` half. It lives in
`konan.properties`, not in code, a user can already override it, and changing a shipped property
affects every Linux link — a separate decision for the maintainers, raised in the issue text.

**Still to do, and none of it is mine to do:** fork `JetBrains/kotlin`, apply the patch, push, and
open the PR against `master` quoting the new issue number. No CLA is mentioned in their contributing
doc, but a first-time contributor may still be asked for one.

## The order matters

1. **File finding 2 first.** It is the only new ticket, its number is quoted in the other two, and
   the pull request cannot be opened without it —
   `KT-XXXXX` appears in the KT-55643 comment and beside `--no-dynamic-linker` in the KT-85658 one.
   Posting the comments first means editing them afterwards to add a number.
2. ~~Merge `docs/the-ticket-and-its-reproduction`.~~ **Done** — merged as #57 on 2026-09-13, CI
   green on `main`, and the clone line in [`TICKET.md`](TICKET.md) no longer carries `-b`. The links
   below point at `main`, which now has the script with all seven defects fixed.
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
> klib's manifest, as @di.gerasimov pointed at in the first comment on this issue, via `posix.def`;
> and `-Xoverride-konan-properties` names `konan.properties` keys, of which none names that
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
> So the half of the recipe that does nothing for `libcrypt` is what gets you one image smaller. The
> two smallest images are out of reach for a separate reason: the link command always carries
> `-dynamic-linker`, so a dynamically linked binary is all `--as-needed` can give you. Passing
> `--no-dynamic-linker` and `-static` by hand does reach them — a 684 KB `scratch` image that still
> resolves hostnames — which is filed separately as KT-XXXXX.
>
> Would you consider re-stating this issue as the general case — that a klib manifest's `linkerOpts`
> cannot be overridden, so `platform.posix` adds six libraries to every Linux binary and five of them
> are dead on any modern glibc? `libcrypt` is the one that breaks first, because distros dropped it
> soonest, but a fix aimed only at `libcrypt` leaves the mechanism and the other five.
>
> Everything above is one script:
> https://github.com/youndie/sborka/tree/main/docs/research/static-probe
> The transcript quoted here is `results/2026-09-13-workaround-halves-and-gc.txt`.

## 2 → the new ticket

**Title:** `Native: -linker-option -static cannot produce a static binary on linux_x64`

**Type:** Bug **Subsystem:** Native **Affected versions:** 2.4.10

> **What happens.** `-linker-option -static` does not produce a static binary on `linux_x64`, because
> the link command undoes it twice, in flags emitted *after* the user's own. Recorded by putting a
> shim in place of `linker.linux_x64` that writes `ld.lld`'s argv and execs the real linker:
>
> ```
>  1  --sysroot=<the toolchain sysroot>
>  7  -dynamic-linker
>  8  /lib64/ld-linux-x86-64.so.2      <- glibc's loader, whatever the sysroot is
> 23  -static                          <- the user's flag
> 30  -Bstatic
> 32  -Bdynamic                        <- cancels it for everything after
> 43  -lc                              <- so this resolves to the SHARED libc
> ```
>
> **First: `-dynamic-linker`, which no setting reaches.** Its *path* is a property
> (`dynamicLinker.linux_x64`), so `-Xoverride-konan-properties` can point it elsewhere. Its
> *emission* is not:
> [`GccBasedLinker.finalLinkCommands`](https://github.com/JetBrains/kotlin/blob/v2.4.10/native/utils/src/org/jetbrains/kotlin/konan/target/Linker.kt#L451-L452)
> adds `-dynamic-linker` and its value with no condition, twenty-nine lines above the `+linkerArgs`
> that brings in the user's flags (line 481 of the same function). The binary then carries a
> `PT_INTERP`; the kernel hands a statically linked program to glibc's dynamic loader, which
> relocates it as though it were dynamic, and it segfaults with no output.
>
> **Second: `-Bdynamic`, hardcoded mid-list.** `linkerKonanFlags.linux_x64` is
> `-Bstatic -lstdc++ -Bdynamic -ldl -lm -lpthread`, and it is emitted after the user's `-static`. The
> `-Bdynamic` switches the linker back to preferring shared libraries, so `-lc` picks up the
> sysroot's `usr/lib/libc.so` — a GNU ld script naming the shared `libc.so.6`, which does not export
> the glibc-internal symbols (`__libc_setup_tls`, `_dl_pagesize`, `_dl_init_static_tls`, …) that
> `libpthread.a`, taken while `-static` still applied, references. The link then fails with seven
> undefined symbols that look like a broken sysroot and are not: that `libc.a` is 28 MB and defines
> all seven. This one a user *can* override, once they know to.
>
> **What it is worth fixing for.** With both worked around by hand — `--no-dynamic-linker` alongside
> `-static`, `-Bdynamic` dropped from `linkerKonanFlags`, and the host's glibc 2.39 as the sysroot —
> the same 2.4.10 compiler produces a **1 618 024-byte static executable, no `PT_INTERP`, no
> `NEEDED`, that runs in `scratch`**: a 684 KB image, in which it still resolves hostnames over DNS
> (checked against the same image with the network removed, where the lookup fails as it should).
> So this is not a corner case for embedded targets. Two flags the user cannot reach are what stands
> between Kotlin/Native and `FROM scratch`, and only the first of them needs a compiler change.
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
> `DYNAMIC_LIBRARY`, `STATIC_LIBRARY` and `EXECUTABLE` today — and would let the same condition drop
> `-Bdynamic` and `-lgcc_s` as well.
>
> **Not in scope here:** against the toolchain's own glibc 2.19 sysroot the binary links once
> `-Bdynamic` is gone but still segfaults at startup, and the musl route reaches the runtime and
> deadlocks (KT-85658). Those are separate; this issue is only about `-static` not meaning static.
>
> **Reproduction:** https://github.com/youndie/sborka/tree/main/docs/research/static-probe
> `./experiments.sh`, sections "route 1 without the hardcoded -Bdynamic" and "static against the
> host glibc". Linux only; it needs a JDK, docker and binutils, and fetches the ~1 GB toolchain on
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
> I cannot tell from here whether that second failure is the runtime or the sysroot the script
> extracts, so please read it as a hint rather than a finding — it is consistent with @aleksei.glushko's
> "a couple more incompatibility problems" above. The deadlock is the solid part: it reproduces on
> x86_64, on a glibc host, with no gcompat and no libraries, in a program that has not done
> anything.
>
> Reproduction: https://github.com/youndie/sborka/tree/main/docs/research/static-probe
> `./experiments.sh`, section "the musl hang against KT-85658".
