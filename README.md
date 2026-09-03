# sborka

**My Gradle conventions, as a plugin.**

The coordinate and the version, the toolchain and the JVM floor, publishing, the formatter, the test
gate, the multiplatform mechanics, the native service, resolution settings and a shared version
catalog — one line each in `gradle.properties` instead of dozens of lines of Kotlin per repository.

```kotlin
// settings.gradle.kts
plugins { id("ru.workinprogress.sborka.settings") version "<version>" }

// a module
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}
```

---

## The plugins

| id | what it does |
|---|---|
| `ru.workinprogress.sborka.settings` | repositories with content filters, the `wip` catalog, the `.editorconfig` check, and `kapkanJoins` — the report of what this repository built and never called — applied in `settings.gradle.kts` |
| `…sborka.base` | group, version, toolchain |
| `…sborka.lint` | ktlint at a pinned version, generated sources excluded, and **kapkan** — three rules that each encode one defect this stack paid a stand run to find |
| `…sborka.test` | JUnit Platform, a failure readable in the run log, an enforced BOM — the check that **every declared `@Test` was executed**, and, for the native and browser suites the comparison cannot reach, the one that **a suite which ran nothing does not pass** |
| `…sborka.jvm` | `base` + `test` + `explicitApi`, `-Werror`, `jvmTarget` taken from the floor |
| `…sborka.kmp` | the same for multiplatform — **except the target list**, which is a repository's argument rather than a convention |
| `…sborka.publish` | the publication, a pom derived from one property, a sources jar, the floor attribute, an `.aar` named with its version |
| `…sborka.mutation` | `mutationTest` on pitest; deliberately not wired into `check` |
| `…sborka.native-service` | the binary's name, staging under `build/native`, `writeNativeDockerfile` |

Plus a composite action, `.github/actions/setup-kotlin`: Java, Gradle and the Kotlin/Native cache in
one step and at one version.

## Quick start

**`settings.gradle.kts`** — the snapshot repository is written out by hand, and it has to be:
`pluginManagement` is evaluated before any settings plugin is applied, including this one.

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://reposilite.kotlin.website/snapshots") {
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

plugins {
    id("ru.workinprogress.sborka.settings") version "<version>"
}
```

**`gradle.properties`** — what used to be code:

```properties
version=0.4.0
sborka.group=ru.workinprogress.mylib
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
| `ru.workinprogress.sborka:conventions` | the project plugins |
| `ru.workinprogress.sborka:settings` | the settings plugin |
| `ru.workinprogress.sborka:core` | what both halves share: the reference `.editorconfig` and the release version |
| `ru.workinprogress.sborka:catalog` | the versions several repositories have to keep identical |

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
