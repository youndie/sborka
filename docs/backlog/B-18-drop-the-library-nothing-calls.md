---
id: B-18
title: "Link with --as-needed, and delete the COPY line two Dockerfiles carry because of it"
status: done
priority: P0
size: XS
stage: stage-6-static-binary
---

# B-18 — Link with `--as-needed`, and delete the COPY line two Dockerfiles carry because of it

A Kotlin/Native binary declares ten shared libraries and imports symbols from three
([research-static-binary §1.2](../research/research-static-binary.md)). One of the seven it does not
use is `libcrypt.so.1`, which `gcr.io/distroless/cc` does not carry — so both native services in
this portfolio copy it out of the builder image by hand, and both carry a paragraph explaining that
the builder's glibc must therefore be no newer than the runtime's, with the failure it produced when
it was: `GLIBC_2.38 not found`, the container exiting before any application logging.

Measured: `-Wl,--as-needed` drops `libcrypt.so.1`, `libresolv.so.2` and `libutil.so.1` from
`NEEDED`, and the binary then starts in `gcr.io/distroless/cc-debian13` **with nothing copied
beside it** and resolves a hostname (§1.3, §1.4).

- **The decision and its reason.** The option goes into `sborka.native-service`, and
  `NativeImageReference.dockerfile` loses the `COPY … libcrypt.so.1` line and the glibc-pairing
  paragraph that exists only to make that copy safe. The convention rather than each repository:
  two of them wrote the same line independently, which is what `NativeImageReference` was created
  to stop.
- **What this removes is a hazard, not a line.** With no file dragged between the stages there is no
  pairing to get wrong, and the most confusing failure mode in the native image story — an image
  that builds and a container that dies before it logs — stops being reachable.
- **It is independent of everything else in this stage.** `scratch` is not reachable
  (§1.5, §1.6) and this does not depend on it ever being reachable.
- **Rejected: copying a matching `libcrypt` from the runtime's own distribution instead.** It keeps
  a dependency on a library nothing calls and leaves the pairing rule in place, just with a longer
  comment.
- **Rejected: patching `NEEDED` out of the finished binary.** Tried with `patchelf
  --remove-needed`; the binary then dies in `ld.so` with `_dl_check_map_versions` assertion failures,
  because the version-requirement table still names the removed object. A relink, not a patch.
- **Does not cover** `libpthread`, `librt` and `libdl`, which also supply nothing and survive the
  option (H2). They cost nothing — every glibc since 2.34 ships them as stubs.

- AC: tracy's and katcher's images build and start with no `COPY … libcrypt.so.1` line; `readelf -d`
  on the produced binaries shows seven `NEEDED` entries, not ten; the services answer their health
  endpoint and reach their database by hostname.
- Anchors: `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts`,
  `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/internal/NativeImageReference.kt`,
  `tracy/server/Dockerfile`, `katcher/server/Dockerfile`

Settles hypothesis H1 of the research.

## Done, 2026-09-11 — in `sborka.kmp`, not `sborka.native-service`, and the reason matters

**Deviation from this item's own decision.** It said the option goes into `sborka.native-service`.
That convention is applied by exactly one module in the portfolio — sborka's own
`stand/native-service` — and by none of the repositories that ship a binary; they take
`sborka.kmp`. Putting it where the item said would have shipped it to nobody and left both
Dockerfiles exactly as they are.

So it is in `sborka.kmp`, on Linux native executables only:

```kotlin
targets.withType<KotlinNativeTarget>()
    .matching { it.konanTarget.family == Family.LINUX }
    .configureEach { binaries.withType<Executable>().configureEach { linkerOpts("-Wl,--as-needed") } }
```

**Linux-only is not caution.** `ld64` and `lld-link` do not accept the flag, so an ungated version
fails every Apple and mingw link in the portfolio. `./gradlew -p stand check` on macOS passes, which
is the regression that would have caught it.

Measured on `stand/native-service`, the one module that links a real executable through the
conventions, built on the Linux box:

| | before | after |
|---|---|---|
| `NEEDED` | 10 | **6** — `libdl libm libpthread libgcc_s libc ld-linux` |
| dropped | — | `libcrypt`, `libresolv`, `libutil`, `librt` |
| runs in `gcr.io/distroless/cc-debian13` with nothing copied beside it | — | **yes**, 10 816 122 B image |

Four dropped rather than the three the probe saw (research §1.3): `librt` goes too in a module that
does not reference it. `NativeImageReference` lost the pairing paragraph and gained one saying that
if the image ever fails with `cannot open shared object file`, the answer is that the convention did
not apply — not another `COPY` line.

**Not done, and it is not this item's size:** tracy's and katcher's Dockerfiles still carry the
`COPY … libcrypt.so.1` line, because they cannot drop it until they take a sborka release carrying
this flag. That is a release plus two bumps plus an image rebuild each, and it is B-22.
