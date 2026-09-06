# sborka

**My Gradle conventions, as a plugin.**

The coordinate and the version, the toolchain and the JVM floor, publishing, the formatter, the test
gate, the multiplatform mechanics, the native service, resolution settings and a shared version
catalog — one line each in `gradle.properties` instead of dozens of lines of Kotlin per repository.

```kotlin
// settings.gradle.kts
plugins { id("io.github.youndie.sborka.settings") version "<version>" }

// a module
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
}
```

---

## The plugins

| id | what it does |
|---|---|
| `io.github.youndie.sborka.settings` | repositories with content filters, the `wip` catalog, the `.editorconfig` check, and `kapkanJoins` — the report of what this repository built and never called — applied in `settings.gradle.kts` |
| `…sborka.base` | group, version, toolchain |
| `…sborka.lint` | ktlint at a pinned version, generated sources excluded, and **kapkan** — three rules that each encode one defect this stack paid a stand run to find |
| `…sborka.test` | JUnit Platform, a failure readable in the run log, an enforced BOM — the check that **every declared `@Test` was executed**, and, for the native and browser suites the comparison cannot reach, the one that **a suite which ran nothing does not pass** |
| `…sborka.jvm` | `base` + `test` + `explicitApi`, `-Werror`, `jvmTarget` taken from the floor |
| `…sborka.kmp` | the same for multiplatform — **except the target list**, which is a repository's argument rather than a convention |
| `…sborka.publish` | the publication, a pom derived from one property, a sources jar, the floor attribute, an `.aar` named with its version |
| `…sborka.mutation` | `mutationTest` on pitest; deliberately not wired into `check` |
| `…sborka.native-service` | the binary's name, staging under `build/native`, `writeNativeDockerfile` |

Plus what a repository's CI asks for by name rather than by copy:

| what | where |
|---|---|
| `.github/actions/setup-kotlin` | Java, Gradle and the Kotlin/Native cache in one step and at one version |
| `.github/actions/determine-version` | the head of the version from `gradle.properties`, run number on the tail |
| `.github/workflows/publish-wip.yaml` | the whole snapshot publish, called with `uses:` — checkout, setup, version, the publish, and the proba job that asks the server what a consumer would resolve |

The first two are steps, which is all a composite action can be. The third is a workflow because the
thing being shared is a job and a second job waiting on it, and neither fits in a step.

And one more thing a repository extends by name rather than copies — its Renovate configuration:

```json
{ "extends": ["github>youndie/sborka", "github>youndie/sborka:automerge-harness"] }
```

`default.json` holds what nineteen repositories had already agreed on by writing it separately: one
Monday batch, the kotlin / kotlinx / sborka / ci-actions groups, the guard against
`kotlinx-datetime`'s `-0.6.x-compat` line (which is 0.8.0 built against the OLD API, and Renovate
reads it as an upgrade), and majors going to a human. `automerge-harness.json` is separate because
it has a precondition — it is only safe where a pull request actually runs the build.

## Quick start

**`settings.gradle.kts`** — the snapshot repository is written out by hand, and it has to be:
`pluginManagement` is evaluated before any settings plugin is applied, including this one.

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://reposilite.kotlin.website/snapshots") {
            content {
                // Обе группы. Портфель переезжает на `io.github.youndie`, и sborka уже там —
                // маркер плагина и jar за ним лежат под новой. Старую держат версии библиотек,
                // выложенные до переезда: они с сервера никуда не делись и резолвятся как прежде.
                includeGroupByRegex("io\\.github\\.youndie.*")
                includeGroupByRegex("ru\\.workinprogress.*")
            }
        }
    }
}

plugins {
    id("io.github.youndie.sborka.settings") version "<version>"
}
```

**`gradle.properties`** — what used to be code:

```properties
version=0.4.0
sborka.group=io.github.youndie.mylib
sborka.repository=youndie/mylib
sborka.description=One line about what this library is
sborka.jvmToolchain=25
sborka.jvmFloor=21
```

`sborka.jvmFloor` is chosen **deliberately**: it is the oldest Java a consumer may be on, not the one
the library happened to be built with.

Step by step — [docs/migration.md](docs/migration.md). What each plugin reads and which properties
exist — [docs/conventions.md](docs/conventions.md). Why it is built this way —
[docs/decisions.md](docs/decisions.md). (Those three are in Russian.)

## What is published

| artefact | what it is |
|---|---|
| `io.github.youndie.sborka:conventions` | the project plugins |
| `io.github.youndie.sborka:settings` | the settings plugin |
| `io.github.youndie.sborka:core` | what both halves share: the reference `.editorconfig` and the release version |
| `io.github.youndie.sborka:catalog` | the versions several repositories have to keep identical |

Three jars rather than one, and that is not cosmetic. Gradle picks a classloader by classpath: a
settings plugin and a project plugin shipped in one jar share a loader — the one that has no Kotlin
plugin beneath it — and the project plugin then fails with `NoClassDefFoundError` on a class that is
demonstrably among its own dependencies. The details are in
[docs/decisions.md](docs/decisions.md).

## Checking it

```bash
./gradlew check
```

This builds the plugins, runs their tests, and **applies them in a separate build**: `stand/`, which
asks for the plugins by id through `includeBuild`, the way a repository will ask through a published
marker. The stand publishes into a directory and then reads what landed there — artefact names, class
file versions inside the jars, the floor attribute in the metadata, the contents of the pom. Without
that, "the plugin applied" and "the plugin did the thing it exists for" are two different claims and
nobody is making the second one.

There are things the stand cannot ask, and that is written down rather than left implicit: what it
cannot see, the first build that takes the plugins finds — [the list of what they
found](docs/decisions.md#что-нашли-миграции).

## Licence

MIT.
