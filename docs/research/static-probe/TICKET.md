# The upstream report, and how to re-check it without taking my word

Three findings, in the order a reader needs them. Everything below is produced by
[`experiments.sh`](experiments.sh) in one run; nothing here rests on a hand-assembled directory that
exists only on the machine it was found on.

**To reproduce, from a clean checkout:**

```bash
git clone -b docs/the-ticket-and-its-reproduction https://github.com/youndie/sborka
cd sborka/docs/research/static-probe && ./experiments.sh
```

The branch is named on purpose: until it is merged, `main` still carries the script **with** the five
defects listed below, so a clone of `main` reproduces the defects rather than the findings. Once it
is merged, drop `-b`.

Verified from a clean clone on 2026-09-13, run **twice in a row**, on a host wiped of every
hand-assembled sysroot first — output in
[`results/2026-09-13-clean-clone-twice.txt`](results/2026-09-13-clean-clone-twice.txt), which
records **both** passes in full. Both exit 0 and are byte-identical apart from their marker. The one
step that verification did not exercise is the ~1 GB toolchain fetch of a truly first run: the
Kotlin/Native distribution was already under `~/.konan` on the verifying host.

Running it that way is what found five defects in this reproduction, none of which a run on the
machine it was written on could have shown: it called a Gradle wrapper that does not exist in this
directory; its failure report said "LINK FAILED, 0 undefined symbols" for failures that were not
link failures; it quoted the manifest of whichever Kotlin/Native distribution the shell listed
first; it could not run twice, because the container wrote the sysroot as root; and it **never
terminated**, because the base-image matrix runs the hanging binary of finding 3 and neither an
untimed `docker run` nor `timeout 20 docker run` bounds a container.

Needs a Linux host with a JDK and docker; `strace` is optional and buys the last line of finding 3.
The Kotlin/Native toolchain (~1 GB) is fetched by Gradle on the first run; docker is used once, to
take a musl sysroot out of `alpine:3.21` — the alternative is a sysroot assembled by hand, which is
exactly what should not be in a report.

---

## 1. `platform.posix` names libc's libraries in a klib manifest, for every Linux program

`klib/platform/linux_x64/org.jetbrains.kotlin.native.platform.posix/default/manifest`:

```
linkerOpts=-lresolv -lm -lpthread -lutil -lcrypt -lrt
```

`experiments.sh` builds one binary — the hello-world probe in this directory — and prints its ten
`NEEDED` entries. The table below adds three more: two Ktor services and a CLI from the same
portfolio, all declaring the identical ten. **Those three are not rebuilt by the script**, and
saying so matters more than the tidiness of pretending otherwise: what a reader can reproduce here
is one binary's list and the manifest it comes from, which is enough, because the list is the klib's
rather than the application's. To retake the other three:

```bash
./gradlew :server:linkReleaseExecutableLinuxX64          # in any Kotlin/Native service
readelf -d build/bin/linuxX64/releaseExecutable/*.kexe | grep NEEDED
```

Which library actually supplies anything is the load-bearing half, and `experiments.sh` prints it
too: the probe's `nm -D --undefined-only` list intersected with each `NEEDED` library's exports.
**Against which glibc** is part of the answer, so the script does it twice — against the sysroot
the toolchain links with (glibc 2.19) and against the host it runs on (glibc 2.39):

| library | exports, of the 103 the probe imports | |
|---|---|---|
| | *link-time sysroot, glibc 2.19* | *host, glibc 2.39* |
| `libc.so.6` | the rest | the rest |
| `libgcc_s.so.1` | 13 — `_Unwind_*`, the C++ unwinder Kotlin/Native's exceptions use | 13 |
| `libm.so.6` | 2 — `ceil`, `floor` | 2 |
| `libpthread.so.0`, `librt.so.1`, `libdl.so.2` | 30, 1, 1 | **0** — stubs since glibc 2.34 |
| `libcrypt.so.1`, `libresolv.so.2`, `libutil.so.1` | **0** | **0** |

So three of the six names are dead on every glibc, and three more are dead on any glibc from 2.34
on — which includes every distroless image in the matrix below (Debian 13, glibc 2.41). The claim
is not "nothing uses `libpthread`"; it is that the manifest names libraries by a layout glibc gave up
two years before Kotlin 2.4, and a consumer cannot follow.

**Why it costs something.** `gcr.io/distroless/cc` does not carry `libcrypt.so.1`. A program that
declares it and never calls it therefore fails at exec with `cannot open shared object file`, and the
usual fix is to copy the library out of the builder image — which couples the two stages by glibc
version, because that file is version-coupled. Two repositories here did exactly that and carried a
rule saying the builder's glibc must be no newer than the runtime's; when it was not, the image built
and the container died with `GLIBC_2.38 not found` before any of its own logging ran.

Linking with `-Wl,--as-needed` drops the unused declarations and the copy with them. That is a
workaround: **no property overrides a klib manifest**, so a consumer cannot ask for a libc's library
list to be anything else.

## 2. `-dynamic-linker` is emitted unconditionally, so `-static` cannot produce a static binary

The argv `ld.lld` is actually given, recorded by a shim in place of `linker.linux_x64`:

```
 1  --sysroot=<the sysroot>
 7  -dynamic-linker
 8  /lib64/ld-linux-x86-64.so.2      <- glibc's loader, whatever the sysroot is
23  -static
```

The interpreter is emitted **before** `-static`, regardless of `targetSysRoot`. Its *path* is a
property — `dynamicLinker.linux_x64` in `konan.properties`, and `-Xoverride-konan-properties` can
point it elsewhere — but its *emission* is not: `GccBasedLinker.finalLinkCommands` in
`native/utils/src/org/jetbrains/kotlin/konan/target/Linker.kt` (tag `v2.4.10`) adds
`-dynamic-linker` and the value unconditionally, ahead of the user's `linkerArgs`, so no value of
that property and none of `targetSysRoot`, `libGcc`, `linkerGccFlags` or `linkerKonanFlags` can make
`-static` mean static.

**What it costs.** A binary linked with `-linker-option -static` still carries a `PT_INTERP`, so the
kernel hands a statically linked musl program to **glibc's** dynamic loader, which relocates it as
though it were dynamic. The result is a segfault with no output — a failure that names nothing and
looks like a Kotlin/Native runtime fault. Passing `--no-dynamic-linker` alongside `-static` removes
it and produces a genuine static binary: no `PT_INTERP`, no `NEEDED`, 430 904 bytes for the probe.

## 3. With both worked around, the runtime does not finish starting against musl

The static musl binary **hangs before its first `println`** — `timeout 20` reports 124, and nothing
is printed, so the Kotlin/Native runtime does not complete start-up.

It is a deadlock, not a spin. Under `strace -f` the process makes about fifty system calls in five
seconds and no `write(2)`; it creates `GC Timer thread` and `Main GC thread`, and then all three
threads are asleep in `FUTEX_WAIT` with zero CPU. The main thread blocks on a futex immediately
after the GC thread is named, before anything the program itself does. The script prints those
counts when `strace` is on the host.

**And it hangs in `scratch` too**, which is the environment the whole question was about. With the
interpreter of finding 2 removed, the kernel *executes* it there rather than refusing it: the last
four rows of the base-image matrix show the binary starting in `cc`, `base`, `static` and `scratch`
alike and printing nothing in five seconds. Every other variant in those rows fails at the loader
with a message; this one gets past the loader and stops inside the runtime.

This is where the investigation stops. It is reported third and last on purpose: findings 1 and 2 are
small, precise and independently fixable, and they are what makes this one worth an afternoon.

---

## What the report asks for

Not "support musl", which is not a change that lands. Two smaller things:

1. **let a klib's `linkerOpts` be overridden**, or stop `platform.posix` from naming a libc's
   libraries at all — the six it names are declared for every Linux program and used by almost none;
2. **let `-dynamic-linker` follow `-static`**, so that `-linker-option -static` means what it says.

The third finding is offered as what happens once they are, not as a request.

## The sysroot, so that "assembled by hand" is not part of the report

`experiments.sh` takes it from an `alpine:3.21` image, where `g++` builds `libstdc++.a` against musl.
That matters twice over: it is reproducible on any host with docker, and Alpine happens to ship
`libcrypt.a`, `libresolv.a`, `libutil.a` and `librt.a` — which is what lets the link finish at all,
since finding 1's six library names cannot be overridden away.

An earlier attempt faked those with empty archives and a **glibc-built** `libstdc++.a`, and the
resulting segfault was blamed on the C++ runtime. It was not: replacing the shim with Alpine's real
musl-built one changed nothing, and the segfault was finding 2. That correction is why the
reproduction uses a real sysroot and why this file names the hypothesis that was wrong.
