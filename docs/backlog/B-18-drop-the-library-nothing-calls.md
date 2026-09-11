---
id: B-18
title: "Link with --as-needed, and delete the COPY line two Dockerfiles carry because of it"
status: open
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
