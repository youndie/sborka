---
id: source-brief-native-image
title: The native-image brief as it arrived — a daemonless image and a load check before push
type: research
status: active
date: 2026-09-29
---

# Source brief: a daemonless, load-checked OCI image for a Kotlin/Native service

The input to the spike, not a finding. The research document this spike writes will list where
the brief turned out to be asking the wrong question, and that comparison is only readable while
the original text is here.

| | |
|---|---|
| Date | 2026-09-29 |
| Repository | the spike: `youndie/sborka`, branch `docs/native-image-spike`, next to `research-static-binary.md` — *decided*; the publishable part: decided by the spike's verdict (§9 item 6) |
| Platforms | none with a UI. Build hosts: macOS (no Docker) and Linux (Docker, used only as ground truth). Target: `linuxX64` |
| Stack | a Gradle plugin or a Jib extension, JVM, run inside Gradle — *undecided until RQ1*, see §8 |
| Documentation | `docs/` inside the repository, docs-bootstrap format |
| Status of this document | the spike's source brief, kept beside its research document as `source-brief-parity.md` and `source-brief-static-binary.md` are |

This is a build tool, not an app. §5 (screens) is empty and there is **no design brief**; §6
describes the Gradle surface (tasks, extension, failure messages) in place of HTTP endpoints.

## 1. Problem and audience

A Kotlin/Native service reaches production as an image, and today two things about that image are
learned only by running it. Whether the base image can load the binary is discovered by a container
that exits before its own logging starts (`cannot open shared object file`). And the image is
described twice — once by Gradle (version, commit, the binary) and once by a Dockerfile (base tag,
entrypoint, environment, labels) — so the two drift, and building it needs a Docker daemon on a
machine that otherwise cross-compiles `linuxX64` without one.

The audience is whoever maintains a native Ktor service built with sborka's conventions (keel is the
template; every clone inherits its image). What changes: the build fails *before push* when
production would reject the binary, the image is built from Gradle alone on any host, and the same
binary always gives the same digest.

## 2. Scope

**In the first version (the spike, one day):**

- RQ0 — whether the release binary is byte-reproducible (same commit, two clean builds; macOS
  cross-compile vs Linux). Everything about "same commit → same digest" rests on it.
- RQ1 — whether Jib builds a working image of keel's native binary with no Docker daemon, and in
  which module shape.
- RQ2 — whether "the base cannot load this binary" is decided statically, before push, by reading
  the ELF and the base image's layers — measured against a corpus of known failures with ground
  truth from `docker run`.
- a verdict: recipe + check (RQ1 GREEN), or plugin (RQ1 RED); and where the check lives.

**Out, on purpose:**

- anything `dlopen`ed — gconv modules, NSS modules — and non-library files such as
  `ca-certificates` or `/usr/share/zoneinfo`: invisible to `NEEDED` by construction. The check says
  so in its output rather than implying coverage.
- multi-arch images and `linuxArm64` — keel ships with it off.
- musl / Alpine bases — Kotlin/Native links glibc.
- building base images (apko territory) and pushing to a specific registry — the spike writes an
  OCI tarball / layout; push is a property of the tool chosen, not of the check.
- the JVM half of keel — zavarnik's job.

## 3. Domain

| Entity | Identified by | Owned by | Notes |
|---|---|---|---|
| `Binary` | path + sha256 of the staged release executable (`build/native-image/<baseName>`) | the service module | ELF facts read from it: `PT_INTERP`, `DT_NEEDED`, `DT_RUNPATH`, `VERNEED` |
| `BaseImage` | registry reference **by digest** | the build script | a tag is never an identity; the digest is an input of every task that reads it |
| `Layer` | diff-id (sha256 of the uncompressed tar) | `BaseImage` or `Image` | whiteouts and symlinks resolved across the stack |
| `Image` | manifest digest | the service module | base layers + a binary layer + an optional files layer; config: entrypoint (exec form), env, OCI labels |
| `LoadVerdict` | (`Binary` sha256, `BaseImage` digest) | the check | closed set: `loads`, `missing-interpreter`, `missing-library`, `missing-version`; each carries the unresolved name and the file that asked for it |

Tenancy: none.

## 4. Features

### feature-daemonless-image: an image from Gradle, on any host

**Overview.** One Gradle task assembles the image from the staged binary and a base pulled by
digest, writing an OCI tarball (or pushing), without a Docker daemon.

**Business rules:**

- the base is referenced by digest; a tag-only reference fails configuration.
- entrypoint is exec form, always (PID 1 must receive `SIGTERM` — kore's ordered shutdown depends on it).
- environment carries what keel's Dockerfile carries today (`MALLOC_ARENA_MAX=2`).
- no JVM classes, resources or jars enter the image of a native service.

**Modules:** the service module (`:server` in keel) or a dedicated `:image` module — RQ1 decides.
**Screens:** none. **Tasks:** `task-image`.

**Scenarios (target):**

- *happy path* — Given keel at `f79f22d` on macOS with no `docker` on `PATH`, when
  `./gradlew :server:<imageTask>` runs, then an OCI tarball is written; loaded on a Linux machine,
  the container answers `/health` with `200` and exits `0` on `SIGTERM`.
- *JVM leak* — Given the same module, when the tarball's layers are listed, then no `.class`,
  `.jar` or `resources/` path is present.

### feature-reproducible-digest: the same binary gives the same digest

**Overview.** Fixed timestamps, ownership and ordering in the layers, so the image digest is a
function of the binary, the base digest and the declared config.

**Business rules:**

- two builds with the same `Binary` sha256 and `BaseImage` digest produce the same manifest digest.
- "same commit → same digest" is claimed only if RQ0 is GREEN; otherwise the claim is "same binary → same digest".

**Scenarios (target):**

- *twice* — Given one staged binary, when the image task runs twice with `--rerun-tasks`, then both
  manifest digests are equal.
- *two hosts* — Given one commit, when the binary is linked on macOS and on Linux, then the image
  digests are equal iff the binaries' sha256 are (RQ0 records which).

### feature-base-load-check: the build fails when the base cannot load the binary

**Overview.** Before push, the binary's ELF requirements are resolved against the files of the
final image's layers (base plus our own). An unresolvable requirement fails the build and names the
library, the file that needed it, and the base digest.

**Business rules:**

- resolved: `PT_INTERP` path; every `DT_NEEDED`, transitively, through `DT_RUNPATH`, the base's
  `/etc/ld.so.cache` if present, and the default paths (`/lib`, `/usr/lib`, the multiarch
  directories); symbol versions (`VERNEED`) against the providing library's `VERDEF`.
- whiteouts delete, symlinks are followed inside the image root, never on the host.
- a library shipped in the image's own layer counts as provided — copying a library is a
  legitimate fix, and the check then checks the copy's own requirements.
- pure JVM ELF reading: no `readelf` on `PATH` required, so it runs on macOS (sborka's current
  report says "readelf is not on PATH, so this is unchecked" there).
- the output ends with what was **not** checked: `dlopen` targets, `ca-certificates`, time zones.

**Modules:** where RQ1 puts it. **Tasks:** `task-check-base`.

**Scenarios (target)** — each is a corpus entry in §5a, and each has a `docker run` verdict as
ground truth:

- *loads* — keel (`--as-needed`) on `cc-debian13` → passes; prints each `NEEDED` → path in layer.
- *no unwinder* — keel on `base-debian13` → fails: `libgcc_s.so.1` needed by the binary, absent in base `<digest>`.
- *new dependency* — a binary with `ktor-client-curl` (needs `libz.so.1`) on `cc-debian12` → fails naming `libz.so.1`.
- *declaration without `--as-needed`* — the same keel linked without it on `cc-debian13` → fails naming `libcrypt.so.1`.
- *copied library, newer glibc* — `libcrypt.so.1` copied from an Ubuntu 24.04 builder onto `cc-debian12` → fails: `GLIBC_2.38` required by `libcrypt.so.1`, base libc defines up to `GLIBC_2.36`.
- *no loader* — keel on `scratch` → fails: interpreter `/lib64/ld-linux-x86-64.so.2` absent.
- *blind spot, stated* — a static binary that needs gconv, on `scratch` with the loader and libc but without gconv → the check passes, the container fails on the first page. The corpus keeps this row so the check's green is sized, not assumed.

### feature-oci-labels: one source of truth for version and revision

**Overview.** `org.opencontainers.image.version` and `.revision` come from the Gradle project
version and the git commit — the same values the service's `/version` reports.

**Scenarios (target):**

- *agree* — Given the running image, when `/version` is requested and the image config is
  inspected, then the version and commit are equal.

## 5. Screens

None.

### 5a. Sample data

| Entity | Values |
|---|---|
| `Binary` | keel release `linuxX64`, `origin/main` `f79f22d` (kore 0.1.10, Ktor 3.6.0); 7 `NEEDED` entries with `--as-needed` (keel's Dockerfile comment), 10 without |
| `Binary` (curl) | keel + `ktor-client-curl` 3.6.0, adds `libz.so.1` |
| `Binary` (static) | the static-probe build from sborka `docs/research/static-probe/` |
| `BaseImage` | `gcr.io/distroless/cc-debian13`, `cc-debian12`, `base-debian13`, `scratch` — digests pinned on the spike's morning and written into the research document |
| Builder (for the copied-library row) | `gradle:9.7.1-jdk25-noble` (glibc 2.39) |
| Today | 2026-09-29 |

## 6. Gradle surface

In place of endpoints. The "errors" column is the failure message, which is the interface.

### task-image

| Task | Inputs | Output | Fails with |
|---|---|---|---|
| `<imageTask>` (Jib's `jibBuildTar`/`jib`, or `publishImage` if RQ1 is RED) | staged binary, base digest, entrypoint, env, labels, optional files dir | OCI tarball / pushed manifest digest | base given by tag only; binary not staged ("nothing staged" is a failure here, not a log line) |

### task-check-base

| Task | Inputs | Output | Fails with |
|---|---|---|---|
| `checkImageBase` (working name) | staged binary (sha256), base digest, own layers | `build/…/load-verdict.txt`: resolution map + the not-checked list | `missing-interpreter <path>`; `missing-library <soname> needed by <file>`; `missing-version <GLIBC_x.y> needed by <file>, <lib> defines up to <v>` — each with the base digest |

The image task depends on the check; push never happens with a failing verdict. Both tasks declare
the base digest and the binary as inputs — a check replayed `FROM-CACHE` against another artefact
is the failure this is for.

## 7. Modules and services

| Module | Role | Stack | Depends on | Publishes | New or existing |
|---|---|---|---|---|---|
| `sborka` `docs/research/image-probe/` | the spike: RQ0–RQ2, the corpus, the runner that produces ground truth | shell + a small Gradle build, like `static-probe/` | keel at `f79f22d`, Docker on the Linux host | results under `image-probe/results/` | new |
| `sborka.native-service` | stages the binary, today logs `NEEDED` | Gradle convention | — | — | existing; the check lands here or beside it if RQ1 is GREEN |
| a plugin repository, pair of zavarnik | `publishImage` + the check | Gradle plugin, JVM | a registry client | Plugin Portal / Central | only if RQ1 is RED |

Deploy: not decided.

## 8. Decisions and hypotheses

| Decision / claim | Why | Verified against / *hypothesis* |
|---|---|---|
| Jib's Gradle plugin needs a `main` source set: in `afterEvaluate` it calls `getByType(SourceSetContainer).getByName("main")` and makes every Jib task depend on its runtime classpath | decides RQ1's shape — "no `java` plugin at all" fails at configuration | read in `JibPlugin.java` on `master`, 2026-09-29 (latest release `v3.5.4-gradle`, 2026-07-14). **Reading, not a run** |
| So RQ1 is really "Jib in a module with `java` applied only to satisfy it" (empty classes, `extraDirectories` = the binary, `container.entrypoint` set) | the cheapest shape that could pass | *hypothesis* — does an empty classpath still add layers? does KMP's `:server` with a `jvm()` target have a `main` source set on Kotlin 2.4.20? |
| Jib 3.5.4 is not configuration-cache compatible | a recipe inherits `--no-configuration-cache` on its tasks; sborka treats CC as a requirement (#76) | verified in zavarnik's Jib path (B-26) |
| Jib layers are reproducible by default (fixed mtimes, epoch creation time) | carries feature-reproducible-digest for free if true | *hypothesis* — the spike runs it twice |
| Jib CLI (`jib build` with a build file) and `crane append` build non-Java images without a daemon | fallback arms for RQ1 if the Gradle plugin is ruled out | *hypothesis* |
| BuildKit can make `docker build` reproducible (`SOURCE_DATE_EPOCH`, timestamp rewrite) | the reproducibility pain may be a flag, not a tool | *hypothesis* — check the BuildKit version on the Linux host |
| **The binary does not need a newer glibc than any Debian distroless has.** Its highest requirement is `GLIBC_2.18` (weak `__cxa_thread_atexit_impl`), hard `memcpy@2.14`, because Kotlin/Native links against its own 2.19 sysroot | corrects the premise "a 2.19 sysroot vs whatever distroless carries": the version failures seen came from *copied* libraries (`libcrypt.so.1`, `GLIBC_2.38`) and from linking against the host's glibc through `-Xoverride-konan-properties` | sborka `research-static-binary.md`; `readelf -V` measured 2026-09-11/14 |
| What actually failed at exec in this portfolio: `libgcc_s` absent in `distroless/base`; `libz` absent in `cc-debian12`; `libcrypt.so.1` absent in `cc`; a copied `libcrypt` needing `GLIBC_2.38` on a 2.36 base; missing gconv under `scratch` | these are the corpus rows; four of five are within a static check, the fifth is not | keel `Dockerfile` comments at `f79f22d`; sborka research; the corpus re-measures each on the spike |
| sborka decided `NEEDED` is a log line, **not a gate**, because a gate against an expected list becomes a rubber stamp | the check here is a different gate: against the base's actual files, so a legitimate new dependency fails only when the base truly cannot load it — and the fix is a base change or a copied layer, not an edited list | `native-service.gradle.kts` on `origin/main` `36e0c52`, the `stageNativeImage` comment |
| sborka decided the Dockerfile is written once and owned by the repository, because the runtime image is a decision a person reads | moving the image into Gradle revisits that; the decision is still in a reviewed file (`build.gradle.kts`), and the prose the Dockerfile carried (libgcc_s, libcrypt, `MALLOC_ARENA_MAX`) becomes the check plus env | `writeNativeDockerfile` on `origin/main` `36e0c52` — open to revision, decided on item 6 (§10 Q2) |
| "Nobody checks NEEDED against the base" | the claim the publishable part stands on | *hypothesis* — nearest prior art to read first: melange's SCA (emits `so:` dependencies from NEEDED, resolved by apko at package level), container-structure-test's tar driver (file-existence lists, hand-written) |
| The Docker daemon is a real blocker | a macOS host cross-compiles `linuxX64`; but keel's current image builds the binary *inside* a builder image, and the CI and Linux hosts have Docker | *hypothesis* — is cross-compiling keel on macOS with its cinterop dependencies (sqlx4k) even green? RQ0 answers it on the way |
| `/version` from kore returns the commit | feature-oci-labels compares against it | *hypothesis* — read kore's `/version` before the scenario is kept |

## 9. Backlog seeds

The day covers items 1–6; 7–8 are conditional on the verdict.

| Stage id | Stage | What it is |
|---|---|---|
| `stage-1-ground-truth` | ground truth | the corpus exists and every row has a `docker run` verdict, before any checking code |
| `stage-2-spike` | spike | RQ0, RQ1, RQ2 answered, verdict written |
| `stage-3-ship` | ship | the publishable part, in the home the verdict chose |

| # | Title | Priority | Size | Stage | Feature | Blocked by | Acceptance |
|---|---|---|---|---|---|---|---|
| 1 | corpus: 7 rows of §4 feature-base-load-check, bases pinned by digest | P1 | M | `stage-1-ground-truth` | feature-base-load-check | — | each row has an image and a recorded `docker run` result (exit code + first stderr line); the gconv row fails at runtime, the negative row serves `/health` |
| 2 | RQ0: binary reproducibility | P1 | S | `stage-2-spike` | feature-reproducible-digest | — | sha256 of two clean links on Linux, and of a macOS cross-link, recorded; verdict names which claim §4 keeps |
| 3 | RQ1: Jib arms on keel | P1 | M | `stage-2-spike` | feature-daemonless-image | — | per arm (`:server` as is; empty-`java` `:image`; Jib CLI; `crane append`): tarball built on macOS without `docker`, layer listing has no JVM paths, container passes the happy-path scenario, digest equal across two runs, CC compatibility noted |
| 4 | RQ2: static check prototype | P1 | M | `stage-2-spike` | feature-base-load-check | 1 | run over the corpus: 5 positive rows fail with the right name, the negative passes, the gconv row passes (declared blind spot); verdict matrix equals ground truth except that row |
| 5 | prior art read | P2 | S | `stage-2-spike` | feature-base-load-check | — | melange SCA and container-structure-test assessed: does either fail a build on this corpus without a hand-written list? |
| 6 | verdict and home | P1 | S | `stage-2-spike` | — | 2, 3, 4, 5 | research document in sborka states RQ0–RQ2 GREEN/RED with the numbers and names the home: sborka convention / Jib extension / new plugin |
| 7 | ship the check (RQ2 GREEN) | P1 | M | `stage-3-ship` | feature-base-load-check | 6 | keel's image task depends on it; keel's corpus rows run in CI |
| 8 | image task in keel, Dockerfile removed (RQ1 GREEN) or `publishImage` plugin (RQ1 RED) | P2 | M | `stage-3-ship` | feature-daemonless-image, feature-oci-labels | 6 | keel's image built without `docker build`; `/version` and labels agree; `rename.sh` clone still passes `make gate` |

## 10. Open questions

- [x] Q1 — the spike's home: sborka `docs/research/`, beside the static-binary research and its base-image matrix — decided by the owner, 2026-09-29
- [ ] Q2 — sborka's "Dockerfile belongs to the repository" and "NEEDED is not a gate": the owner is open to revising both (2026-09-29); the decision is taken on item 6's numbers, not before — owner
- [ ] Q3 — name, if the verdict is a separate plugin — owner, after item 6
- [ ] Q4 — is a warning (not a gate) for known `dlopen`/data paths found by `strings` — gconv, `ca-certificates.crt`, `zoneinfo` — worth it, or is it a heuristic that will be read as coverage? — decided on item 4's numbers
