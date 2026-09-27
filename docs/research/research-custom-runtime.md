---
id: research-custom-runtime
title: Building services on a patched Kotlin/Native distribution — what it would fix, cost and take
type: research
status: draft
date: 2026-09-27
---

# Research: a patched Kotlin/Native distribution for the portfolio's services

**Draft, and a decision not yet taken.** The owner has decided that such a distribution would be
published to our own reposilite **under JetBrains' coordinate**
(`org.jetbrains.kotlin:kotlin-native-prebuilt`) with a version of its own, and that nothing goes
upstream. Whether any service takes it is what this document is for. The patch series, its build
and its controls live in a separate repository (to be named here when it is public).

## What it would fix

Three problems, found separately, each with a workaround today:

| problem | who meets it | workaround on the stock toolchain | what a patched distribution would do |
|---|---|---|---|
| The end-of-marking pause grows with the number of allocator pages: 23–52 ms at p99 at a 1 GB live heap with 16 KiB pages | services with a live heap of hundreds of MB and more | `allocatorPageSize = 256` — only with few allocating threads; with many it costs more than it saves ([conventions](../conventions.md), page size) | two runtime patches take it to 0.9–4.5 ms at any page size |
| `-static` does not mean static without five `-Xoverride-konan-properties` values, which JetBrains calls unstable between patch releases | a service that wants `FROM scratch` | the overrides, pinned per Kotlin version ([research-static-binary](research-static-binary.md)) | those values written into the distribution's own `konan.properties` |
| Ktor's charset layer on Native is glibc `iconv`, which `dlopen`s gconv modules even for UTF-8 | a `scratch` image of a Ktor service | copying the gconv tree from the build stage into the image | not this distribution: a separate `ktor-io` artifact, on Ktor's release cadence |

## Verified facts

| Fact | Where verified |
|---|---|
| At the end of marking, with the world stopped, `PageStore::PrepareForGC` merges two page lists — the second merge walks its source list to the tail — and destroys every empty page with one `munmap` each | `JetBrains/kotlin@v2.4.20!/kotlin-native/runtime/src/alloc/custom/cpp/PageStore.hpp`, `AtomicStack.hpp`, `GCApi.cpp` |
| The merge order that walks the short list was set by `JetBrains/kotlin@ec891474b0` (January 2023) and reversed by `JetBrains/kotlin@7854b01473` two weeks later; 2.4.20 walks the long one | the two commits' diffs of `PageStore.hpp` |
| The compiler's default page size is 128 KiB, not 256 (256 until KT-68909) | `JetBrains/kotlin@v2.4.20!/kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/NativeSecondStageCompilationConfig.kt` |
| The Kotlin Gradle plugin resolves the distribution as `org.jetbrains.kotlin:kotlin-native-<type>:<version>:<host>@tar.gz`, the version from `kotlin.native.version`, and unpacks it to `~/.konan/kotlin-native-prebuilt-<host>-<version>` | `JetBrains/kotlin@v2.4.20!/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/NativeCompilerDownloader.kt`, `.../internal/properties/NativeProperties.kt` (`kotlin.native.distribution.downloadFromMaven` defaults to true) |
| A distribution records its version once, as `compilerVersion` in `konan/konan.properties` | the 2.4.20 distribution, read |
| The runtime is linked into executables only; a klib carries no runtime | how the patched arms were built: one bitcode module swapped in the distribution changed the linked service and nothing else |
| sborka's `wip-snapshots` repository admits only `ru.workinprogress`, `io.github.youndie` and `io.konekt` | [`settings.settings.gradle.kts`](../../build-logic/settings/src/main/kotlin/io/github/youndie/sborka/settings.settings.gradle.kts) |

**Measured, on a synthetic Ktor service with a configurable live heap** (Kotlin 2.4.20, CMS, 16 KiB
pages, one process on a four-core host, end-of-marking pause at p99):

| | 512 MB, 5 threads | 1 GB, 100 threads |
|---|---|---|
| stock | 13–21 ms | 23–52 ms |
| merge order restored only | 8–17 ms | 10–44 ms |
| empty pages freed after resume only | 10–12 ms | 17–20 ms |
| both | **0.8–1.7 ms** | **0.9–4.5 ms** |

Request latency and CPU per request did not get worse at 100 req/s; at 90 % of capacity, freeing
after resume removed latency spikes of 135–307 ms at p99 and the dropped requests. Up to a live
heap of about 128 MB none of this matters: the stock pause is a few milliseconds.

## How a service would take it

Checked on 2026-09-27 on a minimal consumer outside sborka (no conventions, Kotlin 2.4.20, the
distribution in a local Maven repository): with `kotlin.native.version=2.4.20-yrt.1` and the filter in
step 2, the plugin resolved the patched distribution, unpacked it to
`~/.konan/kotlin-native-prebuilt-linux-x86_64-2.4.20-yrt.1`, linked and ran. The binary differed from
the stock one; the same distribution **without** the patches, packaged the same way as a control
version, linked to the stock binary byte for byte, so the difference is the runtime and not the
packaging. Steps 1 and 2 are therefore tried; steps 3 and 4 are not, and nothing has been tried
through sborka itself.

1. **The version.** One line in the service's `gradle.properties`:
   `kotlin.native.version=2.4.20-yrt.1`. A convention plugin cannot set a Gradle property the Kotlin
   plugin reads, so this stays per service, next to where the service already pins what it runs on.
2. **The repository.** sborka's `wip-snapshots` gains exactly the patched versions and nothing else
   of JetBrains':
   ```kotlin
   includeVersionByRegex("org\\.jetbrains\\.kotlin", "kotlin-native-prebuilt", ".*-yrt\\.[0-9]+")
   ```
   so the stock distribution keeps coming from Central, and a patched one can come only from here.
3. **The service says which runtime it runs.** `/version` reports the distribution next to the commit,
   so a crash can be attributed before it is debugged.
4. **Libraries do not move.** Only executables link a runtime; the klibs the portfolio publishes are
   compiled by the same stock compiler either way.

## What it costs

- **A rebase and an acceptance per Kotlin bump.** The portfolio's compiler version lives here, so the
  bump and the patched version move together: apply the series to the new tag, rebuild with the
  control that stock sources reproduce the shipped modules byte for byte, run the allocator's own
  tests and one pause measurement, publish `<kotlin>-yrt.1`.
- **Attribution.** Every crash on a patched service starts with "ours or JetBrains'", and JetBrains
  will not look at a problem that reproduces only there.
- **A coordinate that is not ours.** Publishing under `org.jetbrains.kotlin` in our own repository is
  safe only as long as the repository filter admits nothing else of that group.

## When it stops

Each patch goes when the runtime no longer needs it; the distribution goes with the last one. The
static-linking part is tracked upstream as KT-89362. The allocator patches are not reported upstream.

## Open questions

1. ~~Does the Kotlin plugin accept a suffixed `kotlin.native.version` next to
   `compilerVersion=2.4.20`?~~ Yes, on a minimal consumer (above). Through sborka's own settings plugin
   it has not been tried.
2. Which service needs it now: a live heap of hundreds of MB **and** many allocating threads is the
   shape where no page size helps. Without one, the page-size rule on the stock toolchain is enough.
3. Where the publishing runs, given that the publishing secret is meant to stay in CI.
