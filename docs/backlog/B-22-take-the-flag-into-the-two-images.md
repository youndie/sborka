---
id: B-22
title: "Release the flag and take the COPY line out of the two images that carry it"
status: open
priority: P1
size: S
stage: stage-6-static-binary
blocked_by: [B-18]
---

# B-22 — Release the flag and take the COPY line out of the two images that carry it

B-18 put `-Wl,--as-needed` into `sborka.kmp` and proved it on sborka's own stand: six `NEEDED`
entries instead of ten, and the binary runs in `gcr.io/distroless/cc-debian13` with nothing copied
beside it. The two repositories that actually ship a native image cannot benefit until they take a
sborka release carrying it.

- **The decision and its reason.** Release sborka, bump tracy and katcher, delete the
  `COPY --from=build /usr/lib/x86_64-linux-gnu/libcrypt.so.1 …` line from both Dockerfiles along
  with the paragraph explaining the glibc pairing it required, and rebuild both images. The
  paragraph goes with the line: it documents a rule that only exists because of the copy.
- **Verify on the produced binary, not on the plan.** `readelf -d` on each service binary has to
  show `libcrypt.so.1` gone before the Dockerfile line is deleted. tracy and katcher declare their
  targets differently — katcher resolves one native target from the build host — so the flag
  reaching one does not prove it reached the other.
- **The order is: bump, check the binary, then edit the Dockerfile.** Deleting the line first gives
  an image that builds and a container that exits before it logs, which is the exact failure this
  whole strand is about removing.
- **Does not cover** metrik and shildik, whose images are out of Brief A's scope entirely — both
  pull `ktor-client-curl`, which is a real dynamic dependency (research §1.1). They still get the
  flag from the bump; it just takes fewer entries off their list.

- AC: neither Dockerfile mentions `libcrypt`; `readelf -d` on both shipped binaries shows six or
  seven `NEEDED` entries; both services start in their cluster and answer their health endpoint.
- Anchors: `tracy/server/Dockerfile`, `katcher/server/Dockerfile`,
  `build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts`
