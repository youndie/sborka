---
id: B-22
title: "Release the flag and take the COPY line out of the two images that carry it"
status: done
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

## Done, 2026-09-12 — both images, and the rule went with the line

[tracy#34](https://github.com/youndie/tracy/pull/34) (`f3314cc`) and
[katcher#52](https://github.com/youndie/katcher/pull/52) (`731f794`).

| | tracy | katcher |
|---|---|---|
| sborka | 0.4.0.57, already taken | 0.4.0.43 → **0.4.0.58** |
| `readelf -d` **before** the edit | 7 entries, no `libcrypt` | 7 entries, no `libcrypt` |
| image with nothing beside the binary | starts, fails on `TRACY_INGEST_KEY` | starts, reaches its database migrations |

**The application's own refusal is the proof.** A message from the program means the loader found
everything it asked for; anything short of that would have been `cannot open shared object file`,
which is what this item existed to make impossible.

**The order was the item's and it mattered.** Bump, then read the produced binary, then delete the
line. Reversed, it gives an image that builds and a container that exits before it logs — the exact
failure being removed.

**What went with the line is a rule.** The copied file is glibc-version-coupled, so the builder's
glibc had to be no newer than the runtime's; both Dockerfiles carried a paragraph saying so. There is
nothing to copy now, so there is no pair to get wrong, and the paragraph is replaced by what to do if
the image ever does say `cannot open shared object file`: read `readelf -d` on the binary, because it
means the convention did not apply — not add a second `COPY`.

**A neighbouring belief corrected in the same commits.** `distroless/cc` rather than `base` is needed
**not because of glibc** but because Kotlin/Native's exception handling imports thirteen `_Unwind_*`
symbols from `libgcc`, which `base` does not carry. Both files said the former.

tracy's image: 15 107 059 bytes against 15 192 364 with the copy. The size is incidental; the pairing
hazard was the point.
