# The upstream report, and how to re-check it without taking my word

Three findings, in the order a reader needs them. Everything below is produced by
[`experiments.sh`](experiments.sh) in one run; nothing here rests on a hand-assembled directory that
exists only on the machine it was found on.

**To reproduce, from a clean checkout:**

```bash
git clone https://github.com/youndie/sborka && cd sborka/docs/research/static-probe
./experiments.sh
```

Verified from a clean clone on 2026-09-12 with every trace of the earlier hand-made state removed
from the host first — output in
[`results/2026-09-12-clean-clone.txt`](results/2026-09-12-clean-clone.txt). That run is also what
found three defects in this reproduction: it used a Gradle wrapper that does not exist in this
directory, it reported "LINK FAILED, 0 undefined symbols" for failures that were not link failures,
and it quoted the manifest of whichever Kotlin/Native distribution the shell listed first, which on a
host with two was the older one.

Needs a Linux host with a JDK and docker. The Kotlin/Native toolchain (~1 GB) is fetched by Gradle on
the first run; docker is used once, to take a musl sysroot out of `alpine:3.21` — the alternative is
a sysroot assembled by hand, which is exactly what should not be in a report.

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

Which library actually supplies anything is the load-bearing half, and it is the same command over
`nm -D --undefined-only` against each library's exports:

| library | symbols the binary actually imports |
|---|---|
| `libc.so.6` | the rest |
| `libgcc_s.so.1` | 13 — `_Unwind_*`, the C++ unwinder Kotlin/Native's exceptions use |
| `libm.so.6` | 2 — `log`, `pow` |
| `libcrypt`, `libresolv`, `libutil`, `librt`, `libdl`, `libpthread` | **0** |

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

The interpreter is emitted **before** `-static`, regardless of `targetSysRoot`, and is reachable from
none of `targetSysRoot`, `libGcc`, `linkerGccFlags` or `linkerKonanFlags`.

**What it costs.** A binary linked with `-linker-option -static` still carries a `PT_INTERP`, so the
kernel hands a statically linked musl program to **glibc's** dynamic loader, which relocates it as
though it were dynamic. The result is a segfault with no output — a failure that names nothing and
looks like a Kotlin/Native runtime fault. Passing `--no-dynamic-linker` alongside `-static` removes
it and produces a genuine static binary: no `PT_INTERP`, no `NEEDED`, 430 904 bytes for the probe.

## 3. With both worked around, the runtime does not finish starting against musl

The static musl binary **hangs before its first `println`** — `timeout 20` reports 124, and nothing
is printed, so the Kotlin/Native runtime does not complete start-up.

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
